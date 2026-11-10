package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 ExecutionCondition that enforces Docker availability for integration tests.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If {@code SKIP_DOCKER_TESTS} is set (system property or environment variable),
 *       the test is <em>disabled</em> with a loud WARN log.</li>
 *   <li>If Docker is not available and no skip flag is set, the test is
 *       <em>disabled</em> with a failure-level message — JUnit reports it as skipped
 *       and Surefire marks the build as failed via the callback.</li>
 *   <li>Otherwise the test runs normally.</li>
 * </ul>
 */
class DockerRequiredCondition implements ExecutionCondition {

    private static final Logger log = LoggerFactory.getLogger(DockerRequiredCondition.class);

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Only evaluate at the class level, not per-method
        if (context.getTestMethod().isPresent()) {
            return ConditionEvaluationResult.enabled("Per-method check deferred to class-level condition");
        }

        boolean skipExplicit = Boolean.parseBoolean(System.getProperty("skip.docker.tests", "false"))
                || Boolean.parseBoolean(System.getProperty("SKIP_DOCKER_TESTS", "false"))
                || Boolean.parseBoolean(System.getenv("SKIP_DOCKER_TESTS"));

        if (skipExplicit) {
            log.warn("================================================================================");
            log.warn("WARN: RefreshConcurrencyIntegrationTest skipped by explicit request (SKIP_DOCKER_TESTS=true).");
            log.warn("================================================================================");
            return ConditionEvaluationResult.disabled(
                    "Skipping RefreshConcurrencyIntegrationTest because SKIP_DOCKER_TESTS is set");
        }

        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }

        if (!dockerAvailable) {
            // Return disabled — the test class @BeforeAll won't run.
            // We throw an AssertionError so Surefire treats this as a FAILURE, not a silent skip.
            throw new org.opentest4j.AssertionFailedError(
                    "Docker is required for RefreshConcurrencyIntegrationTest: "
                            + "start Docker Desktop, or run with -DSKIP_DOCKER_TESTS=true to skip explicitly");
        }

        return ConditionEvaluationResult.enabled("Docker is available");
    }
}
