CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX idx_comment_table_template_mid_cursor
    ON comment_data.comment_table_template (mid, ctime DESC, rpid DESC);

CREATE INDEX idx_comment_table_template_parent_cursor
    ON comment_data.comment_table_template (parent_rpid, ctime DESC, rpid DESC)
    WHERE parent_rpid IS NOT NULL;

CREATE INDEX idx_comment_table_template_content_trgm
    ON comment_data.comment_table_template
    USING gin (content public.gin_trgm_ops)
    WHERE content IS NOT NULL;

CREATE INDEX idx_comment_table_template_uname_trgm
    ON comment_data.comment_table_template
    USING gin ((pg_catalog.lower(uname)) public.gin_trgm_ops)
    WHERE uname IS NOT NULL;

DO $indexes$
DECLARE
    v_task_id bigint;
    v_table_name name;
    v_relation regclass;
BEGIN
    FOR v_task_id IN
        SELECT task.task_id
          FROM app.task_definition AS task
         WHERE task.task_type = 'CONTENT_COMMENTS'
         ORDER BY task.task_id
    LOOP
        v_table_name := app.comment_table_name(v_task_id);
        v_relation := pg_catalog.to_regclass(
            pg_catalog.format('%I.%I', 'comment_data', v_table_name)
        );
        CONTINUE WHEN v_relation IS NULL;

        IF NOT EXISTS (
            SELECT 1
              FROM pg_catalog.pg_class AS relation
             WHERE relation.oid = v_relation
               AND relation.relkind = 'r'
        ) THEN
            RAISE EXCEPTION 'comment relation for task % is not an ordinary table', v_task_id
                USING ERRCODE = '42809';
        END IF;

        EXECUTE pg_catalog.format(
            'CREATE INDEX IF NOT EXISTS %I ON comment_data.%I (mid, ctime DESC, rpid DESC)',
            v_table_name || '_mid_cursor_idx',
            v_table_name
        );
        EXECUTE pg_catalog.format(
            'CREATE INDEX IF NOT EXISTS %I ON comment_data.%I '
                || '(parent_rpid, ctime DESC, rpid DESC) WHERE parent_rpid IS NOT NULL',
            v_table_name || '_parent_cursor_idx',
            v_table_name
        );
        EXECUTE pg_catalog.format(
            'CREATE INDEX IF NOT EXISTS %I ON comment_data.%I '
                || 'USING gin (content public.gin_trgm_ops) WHERE content IS NOT NULL',
            v_table_name || '_content_trgm_idx',
            v_table_name
        );
        EXECUTE pg_catalog.format(
            'CREATE INDEX IF NOT EXISTS %I ON comment_data.%I '
                || 'USING gin ((pg_catalog.lower(uname)) public.gin_trgm_ops) '
                || 'WHERE uname IS NOT NULL',
            v_table_name || '_uname_trgm_idx',
            v_table_name
        );
    END LOOP;
END
$indexes$;

CREATE FUNCTION app.find_comment_table(p_task_id bigint)
RETURNS regclass
LANGUAGE plpgsql
STABLE
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, app, comment_data, pg_temp
AS $function$
DECLARE
    v_task_type varchar(32);
    v_table_name name;
    v_relation regclass;
    v_relation_kind "char";
BEGIN
    SELECT task.task_type
      INTO v_task_type
      FROM app.task_definition AS task
     WHERE task.task_id = p_task_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'task % does not exist', p_task_id
            USING ERRCODE = 'P0002';
    END IF;
    IF v_task_type <> 'CONTENT_COMMENTS' THEN
        RAISE EXCEPTION 'task % does not own a comment table', p_task_id
            USING ERRCODE = '22023';
    END IF;

    v_table_name := app.comment_table_name(p_task_id);
    v_relation := pg_catalog.to_regclass(
        pg_catalog.format('%I.%I', 'comment_data', v_table_name)
    );
    IF v_relation IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT relation.relkind
      INTO v_relation_kind
      FROM pg_catalog.pg_class AS relation
     WHERE relation.oid = v_relation;
    IF v_relation_kind <> 'r' THEN
        RAISE EXCEPTION 'comment relation for task % is not an ordinary table', p_task_id
            USING ERRCODE = '42809';
    END IF;
    RETURN v_relation;
END;
$function$;

CREATE FUNCTION app.capture_comment_snapshot(p_task_id bigint)
RETURNS bigint
LANGUAGE plpgsql
STRICT
SECURITY DEFINER
SET search_path = pg_catalog, app, comment_data, pg_temp
AS $function$
DECLARE
    v_relation regclass;
    v_snapshot bigint;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('bilibili-comment-table:' || p_task_id::text, 0)
    );
    v_relation := app.find_comment_table(p_task_id);
    IF v_relation IS NULL THEN
        RETURN 0;
    END IF;

    EXECUTE pg_catalog.format('SELECT COALESCE(max(comment_id), 0) FROM %s', v_relation)
       INTO v_snapshot;
    RETURN v_snapshot;
END;
$function$;

CREATE FUNCTION app.search_comments_page(
    p_task_id bigint,
    p_snapshot_max_comment_id bigint,
    p_keyword text,
    p_mid text,
    p_uname text,
    p_unknown_level_only boolean,
    p_level_min smallint,
    p_level_max smallint,
    p_ctime_from timestamptz,
    p_ctime_before timestamptz,
    p_rpid bigint,
    p_parent_rpid bigint,
    p_reply_scope varchar,
    p_sort varchar,
    p_after_ctime timestamptz,
    p_after_rpid bigint,
    p_limit integer
)
RETURNS TABLE (
    comment_id bigint,
    mid varchar(32),
    uname varchar(255),
    avatar text,
    current_level smallint,
    content text,
    ctime timestamptz,
    rpid bigint,
    parent_rpid bigint,
    created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app, comment_data, pg_temp
AS $function$
DECLARE
    v_relation regclass;
    v_keyword_pattern text;
    v_uname_pattern text;
    v_cursor_operator text;
    v_order text;
    v_sql text;
BEGIN
    IF p_snapshot_max_comment_id IS NULL OR p_snapshot_max_comment_id < 0 THEN
        RAISE EXCEPTION 'snapshot ID must not be negative' USING ERRCODE = '22023';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 5000 THEN
        RAISE EXCEPTION 'page limit must be between 1 and 5000' USING ERRCODE = '22023';
    END IF;
    IF p_unknown_level_only IS NULL OR p_reply_scope IS NULL OR p_sort IS NULL THEN
        RAISE EXCEPTION 'search flags, reply scope and sort are required' USING ERRCODE = '22023';
    END IF;
    IF (p_after_ctime IS NULL) <> (p_after_rpid IS NULL) THEN
        RAISE EXCEPTION 'cursor values must be both null or both present' USING ERRCODE = '22023';
    END IF;
    IF p_keyword IS NOT NULL AND pg_catalog.length(p_keyword) > 200
       OR p_uname IS NOT NULL AND pg_catalog.length(p_uname) > 255
       OR p_mid IS NOT NULL AND p_mid !~ '^[1-9][0-9]{0,31}$'
       OR p_rpid IS NOT NULL AND p_rpid <= 0
       OR p_parent_rpid IS NOT NULL AND p_parent_rpid <= 0
       OR p_after_rpid IS NOT NULL AND p_after_rpid <= 0 THEN
        RAISE EXCEPTION 'invalid comment search value' USING ERRCODE = '22023';
    END IF;
    IF p_level_min IS NOT NULL AND (p_level_min < 0 OR p_level_min > 6)
       OR p_level_max IS NOT NULL AND (p_level_max < 0 OR p_level_max > 6)
       OR p_level_min IS NOT NULL AND p_level_max IS NOT NULL AND p_level_min > p_level_max THEN
        RAISE EXCEPTION 'invalid level range' USING ERRCODE = '22023';
    END IF;
    IF p_unknown_level_only AND (p_level_min IS NOT NULL OR p_level_max IS NOT NULL) THEN
        RAISE EXCEPTION 'unknown level cannot be combined with a level range' USING ERRCODE = '22023';
    END IF;
    IF p_ctime_from IS NOT NULL AND p_ctime_before IS NOT NULL
       AND p_ctime_from >= p_ctime_before THEN
        RAISE EXCEPTION 'invalid comment time range' USING ERRCODE = '22023';
    END IF;
    IF p_reply_scope NOT IN ('ALL', 'ROOT', 'REPLIES') THEN
        RAISE EXCEPTION 'invalid reply scope' USING ERRCODE = '22023';
    END IF;
    IF p_parent_rpid IS NOT NULL AND p_reply_scope <> 'REPLIES' THEN
        RAISE EXCEPTION 'parent rpid requires REPLIES scope' USING ERRCODE = '22023';
    END IF;
    IF p_sort NOT IN ('CTIME_DESC', 'CTIME_ASC') THEN
        RAISE EXCEPTION 'invalid comment sort' USING ERRCODE = '22023';
    END IF;

    IF p_keyword IS NOT NULL THEN
        v_keyword_pattern := '%' || pg_catalog.replace(
            pg_catalog.replace(pg_catalog.replace(p_keyword, E'\\', E'\\\\'), '%', E'\\%'),
            '_',
            E'\\_'
        ) || '%';
    END IF;
    IF p_uname IS NOT NULL THEN
        v_uname_pattern := '%' || pg_catalog.replace(
            pg_catalog.replace(
                pg_catalog.replace(pg_catalog.lower(p_uname), E'\\', E'\\\\'),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%';
    END IF;

    v_relation := app.find_comment_table(p_task_id);
    IF v_relation IS NULL OR p_snapshot_max_comment_id = 0 THEN
        RETURN;
    END IF;

    IF p_sort = 'CTIME_DESC' THEN
        v_cursor_operator := '<';
        v_order := 'DESC';
    ELSE
        v_cursor_operator := '>';
        v_order := 'ASC';
    END IF;

    v_sql := pg_catalog.format(
        $sql$
        SELECT comment.comment_id,
               comment.mid,
               comment.uname,
               comment.avatar,
               comment.current_level,
               comment.content,
               comment.ctime,
               comment.rpid,
               comment.parent_rpid,
               comment.created_at
          FROM %s AS comment
         WHERE comment.comment_id <= $1
           AND ($2::text IS NULL
                OR comment.content ILIKE $2 ESCAPE '\'
                OR pg_catalog.lower(comment.uname) LIKE pg_catalog.lower($2) ESCAPE '\')
           AND ($3::text IS NULL OR comment.mid = $3)
           AND ($4::text IS NULL OR pg_catalog.lower(comment.uname) LIKE $4 ESCAPE '\')
           AND (($5 AND comment.current_level IS NULL)
                OR (NOT $5
                    AND ($6::smallint IS NULL OR comment.current_level >= $6)
                    AND ($7::smallint IS NULL OR comment.current_level <= $7)))
           AND ($8::timestamptz IS NULL OR comment.ctime >= $8)
           AND ($9::timestamptz IS NULL OR comment.ctime < $9)
           AND ($10::bigint IS NULL OR comment.rpid = $10)
           AND ($11::bigint IS NULL OR comment.parent_rpid = $11)
           AND ($12 = 'ALL'
                OR ($12 = 'ROOT' AND comment.parent_rpid IS NULL)
                OR ($12 = 'REPLIES' AND comment.parent_rpid IS NOT NULL))
           AND ($13::timestamptz IS NULL
                OR (comment.ctime, comment.rpid) %s ($13, $14::bigint))
         ORDER BY comment.ctime %s, comment.rpid %s
         LIMIT $15
        $sql$,
        v_relation,
        v_cursor_operator,
        v_order,
        v_order
    );

    RETURN QUERY EXECUTE v_sql USING
        p_snapshot_max_comment_id,
        v_keyword_pattern,
        p_mid,
        v_uname_pattern,
        p_unknown_level_only,
        p_level_min,
        p_level_max,
        p_ctime_from,
        p_ctime_before,
        p_rpid,
        p_parent_rpid,
        p_reply_scope,
        p_after_ctime,
        p_after_rpid,
        p_limit;
END;
$function$;

CREATE FUNCTION app.count_filtered_comments(
    p_task_id bigint,
    p_snapshot_max_comment_id bigint,
    p_keyword text,
    p_mid text,
    p_uname text,
    p_unknown_level_only boolean,
    p_level_min smallint,
    p_level_max smallint,
    p_ctime_from timestamptz,
    p_ctime_before timestamptz,
    p_rpid bigint,
    p_parent_rpid bigint,
    p_reply_scope varchar
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app, comment_data, pg_temp
AS $function$
DECLARE
    v_relation regclass;
    v_keyword_pattern text;
    v_uname_pattern text;
    v_count bigint;
BEGIN
    IF p_snapshot_max_comment_id IS NULL OR p_snapshot_max_comment_id < 0
       OR p_unknown_level_only IS NULL OR p_reply_scope IS NULL
       OR p_reply_scope NOT IN ('ALL', 'ROOT', 'REPLIES') THEN
        RAISE EXCEPTION 'invalid filtered count arguments' USING ERRCODE = '22023';
    END IF;
    IF p_keyword IS NOT NULL AND pg_catalog.length(p_keyword) > 200
       OR p_uname IS NOT NULL AND pg_catalog.length(p_uname) > 255
       OR p_mid IS NOT NULL AND p_mid !~ '^[1-9][0-9]{0,31}$'
       OR p_rpid IS NOT NULL AND p_rpid <= 0
       OR p_parent_rpid IS NOT NULL AND p_parent_rpid <= 0 THEN
        RAISE EXCEPTION 'invalid filtered count value' USING ERRCODE = '22023';
    END IF;
    IF p_level_min IS NOT NULL AND (p_level_min < 0 OR p_level_min > 6)
       OR p_level_max IS NOT NULL AND (p_level_max < 0 OR p_level_max > 6)
       OR p_level_min IS NOT NULL AND p_level_max IS NOT NULL AND p_level_min > p_level_max
       OR p_unknown_level_only AND (p_level_min IS NOT NULL OR p_level_max IS NOT NULL)
       OR p_ctime_from IS NOT NULL AND p_ctime_before IS NOT NULL
           AND p_ctime_from >= p_ctime_before
       OR p_parent_rpid IS NOT NULL AND p_reply_scope <> 'REPLIES' THEN
        RAISE EXCEPTION 'invalid filtered count combination' USING ERRCODE = '22023';
    END IF;
    IF p_keyword IS NOT NULL THEN
        v_keyword_pattern := '%' || pg_catalog.replace(
            pg_catalog.replace(pg_catalog.replace(p_keyword, E'\\', E'\\\\'), '%', E'\\%'),
            '_',
            E'\\_'
        ) || '%';
    END IF;
    IF p_uname IS NOT NULL THEN
        v_uname_pattern := '%' || pg_catalog.replace(
            pg_catalog.replace(
                pg_catalog.replace(pg_catalog.lower(p_uname), E'\\', E'\\\\'),
                '%',
                E'\\%'
            ),
            '_',
            E'\\_'
        ) || '%';
    END IF;
    v_relation := app.find_comment_table(p_task_id);
    IF v_relation IS NULL OR p_snapshot_max_comment_id = 0 THEN
        RETURN 0;
    END IF;

    EXECUTE pg_catalog.format(
        $sql$
        SELECT count(*)::bigint
          FROM %s AS comment
         WHERE comment.comment_id <= $1
           AND ($2::text IS NULL
                OR comment.content ILIKE $2 ESCAPE '\'
                OR pg_catalog.lower(comment.uname) LIKE pg_catalog.lower($2) ESCAPE '\')
           AND ($3::text IS NULL OR comment.mid = $3)
           AND ($4::text IS NULL OR pg_catalog.lower(comment.uname) LIKE $4 ESCAPE '\')
           AND (($5 AND comment.current_level IS NULL)
                OR (NOT $5
                    AND ($6::smallint IS NULL OR comment.current_level >= $6)
                    AND ($7::smallint IS NULL OR comment.current_level <= $7)))
           AND ($8::timestamptz IS NULL OR comment.ctime >= $8)
           AND ($9::timestamptz IS NULL OR comment.ctime < $9)
           AND ($10::bigint IS NULL OR comment.rpid = $10)
           AND ($11::bigint IS NULL OR comment.parent_rpid = $11)
           AND ($12 = 'ALL'
                OR ($12 = 'ROOT' AND comment.parent_rpid IS NULL)
                OR ($12 = 'REPLIES' AND comment.parent_rpid IS NOT NULL))
        $sql$,
        v_relation
    ) INTO v_count USING
        p_snapshot_max_comment_id,
        v_keyword_pattern,
        p_mid,
        v_uname_pattern,
        p_unknown_level_only,
        p_level_min,
        p_level_max,
        p_ctime_from,
        p_ctime_before,
        p_rpid,
        p_parent_rpid,
        p_reply_scope;
    RETURN v_count;
END;
$function$;

REVOKE ALL ON FUNCTION app.find_comment_table(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.capture_comment_snapshot(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.search_comments_page(
    bigint, bigint, text, text, text, boolean, smallint, smallint,
    timestamptz, timestamptz, bigint, bigint, varchar, varchar,
    timestamptz, bigint, integer
) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.count_filtered_comments(
    bigint, bigint, text, text, text, boolean, smallint, smallint,
    timestamptz, timestamptz, bigint, bigint, varchar
) FROM PUBLIC;
