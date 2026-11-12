package com.gamagerecruiters.marketplace.modules.auth.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

/**
 * ExecutionCondition that gates real-MongoDB Testcontainers integration tests.
 *
 * <ul>
 *   <li>If SKIP_DOCKER_TESTS=true (env or system property), the test is skipped.</li>
 *   <li>If Docker is not reachable via DockerClientFactory, the test fails explicitly with a clear hint.</li>
 *   <li>Otherwise the test runs normally.</li>
 * </ul>
 */
class DockerRequiredCondition implements ExecutionCondition {

    private static final Logger log = LoggerFactory.getLogger(DockerRequiredCondition.class);

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker environment is reachable.");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String skipSysProp = System.getProperty("SKIP_DOCKER_TESTS");
        String skipEnv = System.getenv("SKIP_DOCKER_TESTS");
        if ("true".equalsIgnoreCase(skipSysProp) || "true".equalsIgnoreCase(skipEnv)) {
            return ConditionEvaluationResult.disabled(
                    "Skipped: SKIP_DOCKER_TESTS is set to true."
            );
        }

        try {
            boolean available = DockerClientFactory.instance().isDockerAvailable();
            if (!available) {
                String failureMessage = "Docker environment is NOT available. "
                        + "This integration test requires a running Docker daemon. "
                        + "Start Docker Desktop or run Maven with -DSKIP_DOCKER_TESTS=true.";
                log.error("================================================================================");
                log.error("DOCKER TEST FAILURE: {}", failureMessage);
                log.error("================================================================================");
                throw new IllegalStateException(failureMessage);
            }
            return ENABLED;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String failureMessage = "Docker environment check failed: " + e.getMessage()
                    + ". This integration test requires a running Docker daemon. "
                    + "Start Docker Desktop or run Maven with -DSKIP_DOCKER_TESTS=true.";
            log.error("================================================================================");
            log.error("DOCKER TEST FAILURE: {}", failureMessage, e);
            log.error("================================================================================");
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
