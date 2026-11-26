package com.gamagerecruiters.marketplace.modules.notification.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationExecutorConfigTest {
    private CountDownLatch releaseWorker;
    private ThreadPoolExecutor executor;

    @AfterEach
    void releasesBlockedWorker() {
        if (releaseWorker != null) releaseWorker.countDown();
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void tinyBoundedExecutorRejectsTaskWhenWorkerAndQueueAreFull() throws Exception {
        executor = NotificationExecutorConfig.newBoundedExecutor("notification-test", 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            awaitRelease();
        });
        workerStarted.await(1, TimeUnit.SECONDS);
        executor.execute(() -> { });

        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
    }

    @Test
    void configuredExecutorsUseAbortPolicy() {
        NotificationExecutorConfig config = new NotificationExecutorConfig();
        ThreadPoolExecutor saveExecutor = (ThreadPoolExecutor) config.notificationExecutor();
        ThreadPoolExecutor emailExecutor = (ThreadPoolExecutor) config.notificationEmailExecutor();
        assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, saveExecutor.getRejectedExecutionHandler());
        assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, emailExecutor.getRejectedExecutionHandler());
        saveExecutor.shutdownNow();
        emailExecutor.shutdownNow();
    }

    private void awaitRelease() {
        try {
            releaseWorker.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
