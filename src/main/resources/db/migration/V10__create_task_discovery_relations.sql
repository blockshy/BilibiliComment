CREATE TABLE app.task_discovery_relation (
    parent_task_id bigint NOT NULL,
    child_task_id bigint NOT NULL,
    relation_mode varchar(16) NOT NULL,
    first_discovered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_discovered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    first_discovered_execution_id bigint,
    last_discovered_execution_id bigint,
    PRIMARY KEY (parent_task_id, child_task_id),
    CONSTRAINT fk_task_discovery_relation_parent
        FOREIGN KEY (parent_task_id)
        REFERENCES app.task_definition (task_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_task_discovery_relation_child
        FOREIGN KEY (child_task_id)
        REFERENCES app.task_definition (task_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_task_discovery_relation_first_execution
        FOREIGN KEY (first_discovered_execution_id, parent_task_id)
        REFERENCES app.task_execution (execution_id, task_id),
    CONSTRAINT fk_task_discovery_relation_last_execution
        FOREIGN KEY (last_discovered_execution_id, parent_task_id)
        REFERENCES app.task_execution (execution_id, task_id),
    CONSTRAINT ck_task_discovery_relation_distinct_tasks
        CHECK (parent_task_id <> child_task_id),
    CONSTRAINT ck_task_discovery_relation_mode
        CHECK (relation_mode IN ('MANAGED', 'REFERENCED')),
    CONSTRAINT ck_task_discovery_relation_times
        CHECK (last_discovered_at >= first_discovered_at),
    CONSTRAINT ck_task_discovery_relation_execution_ids
        CHECK (
            (first_discovered_execution_id IS NULL OR first_discovered_execution_id > 0)
            AND (last_discovered_execution_id IS NULL OR last_discovered_execution_id > 0)
        )
);

CREATE INDEX idx_task_discovery_relation_parent_page
    ON app.task_discovery_relation
        (parent_task_id, first_discovered_at DESC, child_task_id DESC);

CREATE INDEX idx_task_discovery_relation_child
    ON app.task_discovery_relation
        (child_task_id, relation_mode, first_discovered_at, parent_task_id);

CREATE FUNCTION app.enforce_task_discovery_relation_endpoints()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, app
AS $$
DECLARE
    v_parent_task_type varchar(32);
    v_parent_source_type varchar(16);
    v_child_task_type varchar(32);
    v_child_source_type varchar(16);
BEGIN
    SELECT task.task_type, task.source_type
      INTO v_parent_task_type, v_parent_source_type
      FROM app.task_definition AS task
     WHERE task.task_id = NEW.parent_task_id
       FOR SHARE;

    -- Let the foreign key report an unknown parent with SQLSTATE 23503.
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    SELECT task.task_type, task.source_type
      INTO v_child_task_type, v_child_source_type
      FROM app.task_definition AS task
     WHERE task.task_id = NEW.child_task_id
       FOR SHARE;

    -- Let the foreign key report an unknown child with SQLSTATE 23503.
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF v_parent_task_type <> 'CREATOR_WATCH'
            OR v_parent_source_type <> 'CREATOR'
            OR v_child_task_type <> 'CONTENT_COMMENTS'
            OR v_child_source_type NOT IN ('VIDEO', 'DYNAMIC') THEN
        RAISE EXCEPTION
            'invalid task discovery relation endpoints: parent % must be CREATOR_WATCH/CREATOR and child % must be CONTENT_COMMENTS/VIDEO or CONTENT_COMMENTS/DYNAMIC',
            NEW.parent_task_id,
            NEW.child_task_id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_task_discovery_relation_endpoint_types';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION app.enforce_task_discovery_relation_endpoints() FROM PUBLIC;

CREATE TRIGGER trg_task_discovery_relation_endpoints
BEFORE INSERT OR UPDATE OF parent_task_id, child_task_id
ON app.task_discovery_relation
FOR EACH ROW
EXECUTE FUNCTION app.enforce_task_discovery_relation_endpoints();

CREATE FUNCTION app.protect_task_discovery_relation_endpoints()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, app
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM app.task_discovery_relation AS relation
         WHERE relation.parent_task_id = NEW.task_id
    ) AND (NEW.task_type <> 'CREATOR_WATCH' OR NEW.source_type <> 'CREATOR') THEN
        RAISE EXCEPTION
            'invalid task discovery relation parent endpoint: task % must remain CREATOR_WATCH/CREATOR',
            NEW.task_id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_task_discovery_relation_endpoint_types';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM app.task_discovery_relation AS relation
         WHERE relation.child_task_id = NEW.task_id
    ) AND (
        NEW.task_type <> 'CONTENT_COMMENTS'
        OR NEW.source_type NOT IN ('VIDEO', 'DYNAMIC')
    ) THEN
        RAISE EXCEPTION
            'invalid task discovery relation child endpoint: task % must remain CONTENT_COMMENTS/VIDEO or CONTENT_COMMENTS/DYNAMIC',
            NEW.task_id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_task_discovery_relation_endpoint_types';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION app.protect_task_discovery_relation_endpoints() FROM PUBLIC;

CREATE TRIGGER trg_task_definition_discovery_endpoints
BEFORE UPDATE OF task_type, source_type
ON app.task_definition
FOR EACH ROW
WHEN (
    OLD.task_type IS DISTINCT FROM NEW.task_type
    OR OLD.source_type IS DISTINCT FROM NEW.source_type
)
EXECUTE FUNCTION app.protect_task_discovery_relation_endpoints();

-- Legacy discovered children only recorded their creator task in JSON metadata.
-- Backfill values only when the identifier is a safe bigint and both task types
-- form a valid creator-to-content relationship. Execution IDs remain nullable
-- when no parent execution can be proven to cover the child's creation instant.
WITH legacy_candidates AS (
    SELECT
        child.task_id AS child_task_id,
        child.created_at AS discovered_at,
        CASE
            WHEN child.source_metadata ->> 'discoveredByTaskId' ~ '^[1-9][0-9]*$'
                AND (
                    length(child.source_metadata ->> 'discoveredByTaskId') < 19
                    OR (
                        length(child.source_metadata ->> 'discoveredByTaskId') = 19
                        AND (child.source_metadata ->> 'discoveredByTaskId') COLLATE "C"
                            <= '9223372036854775807'
                    )
                )
            THEN (child.source_metadata ->> 'discoveredByTaskId')::bigint
        END AS parent_task_id
    FROM app.task_definition AS child
    WHERE child.task_type = 'CONTENT_COMMENTS'
      AND child.source_type IN ('VIDEO', 'DYNAMIC')
      AND child.source_metadata ? 'discoveredByTaskId'
), valid_candidates AS (
    SELECT candidate.*
    FROM legacy_candidates AS candidate
    JOIN app.task_definition AS parent
      ON parent.task_id = candidate.parent_task_id
     AND parent.task_type = 'CREATOR_WATCH'
     AND parent.source_type = 'CREATOR'
    WHERE candidate.parent_task_id IS NOT NULL
      AND candidate.parent_task_id <> candidate.child_task_id
)
INSERT INTO app.task_discovery_relation (
    parent_task_id,
    child_task_id,
    relation_mode,
    first_discovered_at,
    last_discovered_at,
    first_discovered_execution_id,
    last_discovered_execution_id
)
SELECT
    candidate.parent_task_id,
    candidate.child_task_id,
    'MANAGED',
    candidate.discovered_at,
    candidate.discovered_at,
    covering_execution.execution_id,
    covering_execution.execution_id
FROM valid_candidates AS candidate
LEFT JOIN LATERAL (
    SELECT execution.execution_id
    FROM app.task_execution AS execution
    WHERE execution.task_id = candidate.parent_task_id
      AND COALESCE(execution.started_at, execution.queued_at, execution.created_at)
            <= candidate.discovered_at
      AND COALESCE(execution.finished_at, execution.updated_at)
            >= candidate.discovered_at
    ORDER BY COALESCE(execution.started_at, execution.queued_at, execution.created_at) DESC,
             execution.execution_id DESC
    LIMIT 1
) AS covering_execution ON true
ON CONFLICT (parent_task_id, child_task_id) DO NOTHING;

COMMENT ON TABLE app.task_discovery_relation IS
    'Durable creator-watch relationship to content-comment tasks';
COMMENT ON COLUMN app.task_discovery_relation.relation_mode IS
    'MANAGED for discovery-managed children; REFERENCED for independently existing tasks';
