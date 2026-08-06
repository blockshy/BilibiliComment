package com.hy.bilicomment.infrastructure.persistence;

import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CommentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CommentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InsertResult insert(long taskId, List<CommentRecord> comments) {
        if (comments.isEmpty()) {
            return new InsertResult(0, 0, 0);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(comments);
        } catch (Exception exception) {
            throw new DomainException("COMMENT_SERIALIZATION_FAILED", "评论批次无法序列化", exception);
        }
        return jdbcTemplate.queryForObject(
                "SELECT * FROM app.insert_comments(?, CAST(? AS jsonb))",
                (resultSet, rowNumber) -> new InsertResult(
                        resultSet.getLong("received_count"),
                        resultSet.getLong("inserted_count"),
                        resultSet.getLong("duplicate_count")),
                taskId,
                payload);
    }

    @Transactional
    public List<CommentView> findPage(long taskId, Instant beforeTime, Long beforeRpid, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM app.query_comments_page(?, ?, ?, ?)",
                (resultSet, rowNumber) -> mapComment(resultSet),
                taskId,
                beforeTime == null ? null : beforeTime.atOffset(ZoneOffset.UTC),
                beforeRpid,
                limit);
    }

    @Transactional
    public long count(long taskId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT app.count_task_comments(?)",
                Long.class,
                taskId);
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public long captureSnapshot(long taskId) {
        Long snapshot = jdbcTemplate.queryForObject(
                "SELECT app.capture_comment_snapshot(?)",
                Long.class,
                taskId);
        return snapshot == null ? 0 : snapshot;
    }

    @Transactional(readOnly = true)
    public List<CommentView> search(SearchSpec spec) {
        CommentFilter filter = spec.filter();
        return jdbcTemplate.query(
                """
                SELECT * FROM app.search_comments_page(
                    ?, ?, ?, ?, ?, ?, CAST(? AS smallint), CAST(? AS smallint),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                (resultSet, rowNumber) -> mapComment(resultSet),
                spec.taskId(),
                spec.snapshotMaxCommentId(),
                filter.keyword(),
                filter.mid(),
                filter.uname(),
                filter.unknownLevelOnly(),
                filter.levelMin(),
                filter.levelMax(),
                timestamp(filter.ctimeFrom()),
                timestamp(filter.ctimeBefore()),
                filter.rpid(),
                filter.parentRpid(),
                filter.replyScope().name(),
                spec.sort().name(),
                timestamp(spec.afterTime()),
                spec.afterRpid(),
                spec.limit());
    }

    @Transactional(readOnly = true)
    public long countFiltered(long taskId, long snapshotMaxCommentId, CommentFilter filter) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT app.count_filtered_comments(
                    ?, ?, ?, ?, ?, ?, CAST(? AS smallint), CAST(? AS smallint),
                    ?, ?, ?, ?, ?
                )
                """,
                Long.class,
                taskId,
                snapshotMaxCommentId,
                filter.keyword(),
                filter.mid(),
                filter.uname(),
                filter.unknownLevelOnly(),
                filter.levelMin(),
                filter.levelMax(),
                timestamp(filter.ctimeFrom()),
                timestamp(filter.ctimeBefore()),
                filter.rpid(),
                filter.parentRpid(),
                filter.replyScope().name());
        return count == null ? 0 : count;
    }

    private CommentView mapComment(ResultSet resultSet) throws SQLException {
        long parent = resultSet.getLong("parent_rpid");
        Long parentRpid = resultSet.wasNull() ? null : parent;
        int level = resultSet.getInt("current_level");
        Integer currentLevel = resultSet.wasNull() ? null : level;
        return new CommentView(
                resultSet.getLong("comment_id"),
                resultSet.getString("mid"),
                resultSet.getString("uname"),
                resultSet.getString("avatar"),
                currentLevel,
                resultSet.getString("content"),
                readInstant(resultSet, "ctime"),
                resultSet.getLong("rpid"),
                parentRpid,
                readInstant(resultSet, "created_at"));
    }

    private Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    public record InsertResult(long received, long inserted, long duplicates) {}

    public record CommentView(
            long commentId,
            String mid,
            String uname,
            String avatar,
            Integer currentLevel,
            String content,
            Instant ctime,
            long rpid,
            Long parentRpid,
            Instant createdAt) {}

    public record SearchSpec(
            long taskId,
            CommentFilter filter,
            CommentSort sort,
            long snapshotMaxCommentId,
            Instant afterTime,
            Long afterRpid,
            int limit) {}
}
