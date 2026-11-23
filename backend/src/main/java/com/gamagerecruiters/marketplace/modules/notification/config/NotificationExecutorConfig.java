package com.gamagerecruiters.marketplace.modules.notification.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationExecutorConfig {
    private static final Logger log = LoggerFactory.getLogger(NotificationExecutorConfig.class);

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000), runnable -> {
            Thread thread = new Thread(runnable, "notification-save");
            thread.setDaemon(true);
            return thread;
        }, (runnable, executor) -> log.warn("Notification task rejected"));
    }
}
