package com.hy.bilicomment.application.audit;

import com.hy.bilicomment.infrastructure.persistence.mapper.OperationAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OperationAuditService {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditService.class);

    private final OperationAuditMapper mapper;

    public OperationAuditService(OperationAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void record(AuditEntry entry) {
        try {
            mapper.insert(
                    entry.actor(),
                    entry.action(),
                    entry.targetType(),
                    entry.targetId(),
                    entry.outcome(),
                    entry.traceId(),
                    entry.sourceIp());
        } catch (RuntimeException exception) {
            log.warn("Unable to persist operation audit action={} outcome={}",
                    entry.action(), entry.outcome());
        }
    }

    public record AuditEntry(
            String actor,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            String sourceIp) {}
}
