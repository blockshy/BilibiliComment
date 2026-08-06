package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.config.AppProperties;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ExecutionInstanceIdentity {

    private final String instanceId;

    public ExecutionInstanceIdentity(AppProperties properties) {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        this.instanceId = properties.getEnvironment() + ":" + runtimeName + ":"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    public String newLeaseOwner(long executionId) {
        return instanceId + ":" + executionId + ":" + UUID.randomUUID();
    }
}
