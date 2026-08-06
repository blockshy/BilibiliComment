package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.task.TaskDiscoveryRelationService;
import com.hy.bilicomment.domain.error.DomainException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ManagedDiscoveryInitialExecutionRecovery {

    static final int BATCH_SIZE = 50;

    private static final Logger log =
            LoggerFactory.getLogger(ManagedDiscoveryInitialExecutionRecovery.class);

    private final TaskDiscoveryRelationService relationService;
    private final TaskExecutionService executionService;
    private final ThreadPoolTaskScheduler scheduler;

    public ManagedDiscoveryInitialExecutionRecovery(
            TaskDiscoveryRelationService relationService,
            TaskExecutionService executionService,
            ThreadPoolTaskScheduler scheduler) {
        this.relationService = relationService;
        this.executionService = executionService;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            scheduler.execute(this::recoverAllBatches);
        } catch (RuntimeException exception) {
            log.warn(
                    "Unable to submit managed task initial execution recovery errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    void recoverAllBatches() {
        List<Long> previousBatch = List.of();
        int batchCount = 0;
        int inspectedTotal = 0;
        int queuedTotal = 0;
        int unchangedTotal = 0;
        int failedTotal = 0;

        while (true) {
            List<Long> childTaskIds = findNextBatch();
            if (childTaskIds == null || childTaskIds.isEmpty()) {
                break;
            }
            if (childTaskIds.equals(previousBatch)) {
                log.warn(
                        "Stopping managed task initial execution recovery because the same batch was returned again size={}",
                        childTaskIds.size());
                break;
            }

            BatchResult result = recoverBatch(childTaskIds);
            batchCount++;
            inspectedTotal += childTaskIds.size();
            queuedTotal += result.queued();
            unchangedTotal += result.unchanged();
            failedTotal += result.failed();

            if (childTaskIds.size() < BATCH_SIZE) {
                break;
            }
            if (result.queued() == 0) {
                log.warn(
                        "Stopping managed task initial execution recovery because a full batch made no progress size={} unchanged={} failed={}",
                        childTaskIds.size(),
                        result.unchanged(),
                        result.failed());
                break;
            }
            previousBatch = List.copyOf(childTaskIds);
        }

        log.info(
                "Managed task initial execution recovery batches={} inspected={} queued={} unchanged={} failed={}",
                batchCount, inspectedTotal, queuedTotal, unchangedTotal, failedTotal);
    }

    private List<Long> findNextBatch() {
        try {
            return relationService.findManagedChildrenWithoutExecution(BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn(
                    "Unable to inspect managed tasks missing an initial execution errorType={}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private BatchResult recoverBatch(List<Long> childTaskIds) {
        int queued = 0;
        int unchanged = 0;
        int failed = 0;
        for (Long childTaskId : childTaskIds) {
            try {
                if (executionService.ensureInitialExecution(childTaskId).isPresent()) {
                    queued++;
                } else {
                    unchanged++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn(
                        "Unable to recover managed task initial execution taskId={} code={}",
                        childTaskId,
                        errorCode(exception));
            }
        }
        return new BatchResult(queued, unchanged, failed);
    }

    private String errorCode(RuntimeException exception) {
        return exception instanceof DomainException domainException
                ? domainException.getCode()
                : exception.getClass().getSimpleName();
    }

    private record BatchResult(int queued, int unchanged, int failed) {}
}
