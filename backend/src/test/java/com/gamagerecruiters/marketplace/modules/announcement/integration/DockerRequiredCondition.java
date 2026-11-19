package com.gamagerecruiters.marketplace.modules.announcement.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Same gate as the other modules' integration tests: skipped when SKIP_DOCKER_TESTS=true (system property or
 * environment variable); fails with a clear hint when Docker is not reachable; otherwise runs.
 */
class DockerRequiredCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if ("true".equalsIgnoreCase(System.getProperty("SKIP_DOCKER_TESTS"))
                || "true".equalsIgnoreCase(System.getenv("SKIP_DOCKER_TESTS"))) {
            return ConditionEvaluationResult.disabled("Skipped: SKIP_DOCKER_TESTS is set to true.");
        }
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            available = false;
        }
        if (!available) {
            throw new IllegalStateException("Docker environment is NOT available. This integration test requires a "
                    + "running Docker daemon. Start Docker Desktop or run Maven with -DSKIP_DOCKER_TESTS=true.");
        }
        return ConditionEvaluationResult.enabled("Docker environment is reachable.");
    }
}
