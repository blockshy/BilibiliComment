package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentExportFormat;
import com.hy.bilicomment.domain.comment.CommentExportStatus;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommentExportService {

    public static final List<CommentExportColumn> DEFAULT_COLUMNS = List.of(
            CommentExportColumn.RPID,
            CommentExportColumn.PARENT_RPID,
            CommentExportColumn.MID,
            CommentExportColumn.UNAME,
            CommentExportColumn.CURRENT_LEVEL,
            CommentExportColumn.CONTENT,
            CommentExportColumn.CTIME);

    private final TaskService taskService;
    private final CommentRepository commentRepository;
    private final CommentFilterService filterService;
    private final CommentCursorCodec cursorCodec;
    private final CommentExportJobMapper mapper;
    private final AppProperties.Exports properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommentExportService(
            TaskService taskService,
            CommentRepository commentRepository,
            CommentFilterService filterService,
            CommentCursorCodec cursorCodec,
            CommentExportJobMapper mapper,
            AppProperties appProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.taskService = taskService;
        this.commentRepository = commentRepository;
        this.filterService = filterService;
        this.cursorCodec = cursorCodec;
        this.mapper = mapper;
        this.properties = appProperties.getExports();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CommentExportJobRow create(
            long taskId,
            String requestedBy,
            CommentFilter requestedFilter,
            CommentSort requestedSort,
            CommentExportFormat format,
            List<CommentExportColumn> requestedColumns) {
        requireContentTask(taskId);
        if (!properties.isEnabled()) {
            throw new DomainException("COMMENT_EXPORT_DISABLED", "评论导出功能当前未启用");
        }
        if (requestedBy == null || requestedBy.isBlank() || requestedBy.length() > 128) {
            throw new DomainException("COMMENT_EXPORT_ACTOR_INVALID", "导出请求用户无效");
        }
        if (format == null) {
            throw new DomainException("COMMENT_EXPORT_FORMAT_INVALID", "必须指定导出格式");
        }

        CommentFilter filter = filterService.normalize(requestedFilter);
        CommentSort sort = requestedSort == null ? CommentSort.CTIME_DESC : requestedSort;
        List<CommentExportColumn> columns = normalizeColumns(requestedColumns);

        mapper.lockQueue();
        if (mapper.countQueued() >= properties.getMaximumQueued()) {
            throw new DomainException("COMMENT_EXPORT_QUEUE_FULL", "评论导出队列已满，请稍后重试");
        }

        long snapshot = commentRepository.captureSnapshot(taskId);
        return mapper.insert(
                taskId,
                requestedBy,
                format.name(),
                serialize(columns, "导出列"),
                serialize(filter, "评论筛选条件"),
                cursorCodec.fingerprint(filter, sort),
                sort.name(),
                snapshot,
                properties.getMaximumRows(),
                properties.getMaximumBytes(),
                clock.instant().plus(properties.getTtl()));
    }

    @Transactional(readOnly = true)
    public List<CommentExportJobRow> list(long taskId, int limit) {
        requireContentTask(taskId);
        if (limit < 1 || limit > 100) {
            throw new DomainException("COMMENT_EXPORT_LIMIT_INVALID", "导出任务列表大小必须在 1 到 100 之间");
        }
        return mapper.findByTask(taskId, limit);
    }

    @Transactional(readOnly = true)
    public CommentExportJobRow require(long exportId) {
        return mapper.findById(exportId)
                .orElseThrow(() -> new DomainException("COMMENT_EXPORT_NOT_FOUND", "评论导出任务不存在"));
    }

    @Transactional
    public void cancel(long exportId) {
        CommentExportJobRow job = require(exportId);
        CommentExportStatus status = status(job);
        if (status == CommentExportStatus.EXPIRED) {
            throw new DomainException("COMMENT_EXPORT_EXPIRED", "评论导出任务已过期");
        }
        if (status == CommentExportStatus.QUEUED || status == CommentExportStatus.RUNNING) {
            mapper.requestCancellation(exportId);
        }
    }

    public CommentExportStatus status(CommentExportJobRow job) {
        CommentExportStatus status = CommentExportStatus.valueOf(job.status());
        if (status != CommentExportStatus.RUNNING
                && !job.expiresAt().isAfter(clock.instant())) {
            return CommentExportStatus.EXPIRED;
        }
        return status;
    }

    public boolean downloadReady(CommentExportJobRow job) {
        return status(job) == CommentExportStatus.SUCCEEDED
                && job.fileKey() != null
                && job.encryptedSha256() != null;
    }

    CommentFilter deserializeFilter(CommentExportJobRow job) {
        try {
            return filterService.normalize(objectMapper.readValue(job.filterJson(), CommentFilter.class));
        } catch (Exception exception) {
            throw new DomainException("COMMENT_EXPORT_FILTER_INVALID", "已保存的导出筛选条件无效", exception);
        }
    }

    public List<CommentExportColumn> deserializeColumns(CommentExportJobRow job) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, CommentExportColumn.class);
            List<CommentExportColumn> columns = objectMapper.readValue(job.exportColumnsJson(), type);
            return normalizeColumns(columns);
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DomainException("COMMENT_EXPORT_COLUMNS_INVALID", "已保存的导出列无效", exception);
        }
    }

    private void requireContentTask(long taskId) {
        if (taskService.require(taskId).taskType() != TaskType.CONTENT_COMMENTS) {
            throw new DomainException("COMMENTS_NOT_APPLICABLE", "UP 主监控任务没有独立评论表");
        }
    }

    private List<CommentExportColumn> normalizeColumns(List<CommentExportColumn> requested) {
        if (requested == null) {
            return DEFAULT_COLUMNS;
        }
        if (requested.isEmpty()) {
            throw new DomainException("COMMENT_EXPORT_COLUMNS_INVALID", "导出列不能为空");
        }
        EnumSet<CommentExportColumn> seen = EnumSet.noneOf(CommentExportColumn.class);
        List<CommentExportColumn> normalized = new ArrayList<>(requested.size());
        for (CommentExportColumn column : requested) {
            if (column == null || !seen.add(column)) {
                throw new DomainException("COMMENT_EXPORT_COLUMNS_INVALID", "导出列无效或重复");
            }
            normalized.add(column);
        }
        return List.copyOf(normalized);
    }

    private String serialize(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new DomainException("COMMENT_EXPORT_SERIALIZATION_FAILED", label + "无法序列化", exception);
        }
    }
}
