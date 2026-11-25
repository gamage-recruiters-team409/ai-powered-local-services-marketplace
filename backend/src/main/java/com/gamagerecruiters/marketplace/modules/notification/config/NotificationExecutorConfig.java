package com.gamagerecruiters.marketplace.modules.notification.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationExecutorConfig {
    private static final AtomicInteger EMAIL_THREAD_SEQUENCE = new AtomicInteger();

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        return newBoundedExecutor("notification-save", 1000);
    }

    @Bean(name = "notificationEmailExecutor")
    public Executor notificationEmailExecutor() {
        return newBoundedExecutor("notification-email", 2, 500, runnable -> {
            Thread thread = new Thread(runnable, "notification-email-" + EMAIL_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    static ThreadPoolExecutor newBoundedExecutor(String threadName, int queueCapacity) {
        return newBoundedExecutor(threadName, 1, queueCapacity, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ThreadPoolExecutor newBoundedExecutor(String threadName, int threadCount, int queueCapacity,
            java.util.concurrent.ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }
}
