package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recovers abandoned upload reservations after application startup and
 * periodically while the application is running.
 *
 * Only reservations whose lease has expired are removed. Active uploads renew
 * their own leases and therefore remain protected.
 */
@Component
public class VerificationUploadReservationRecovery implements DisposableBean {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VerificationUploadReservationRecovery.class);

    private static final long RECOVERY_INTERVAL_SECONDS = 60L;

    private final VerificationAtomicUpdates verificationAtomicUpdates;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final ScheduledExecutorService recoveryExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(
                                    runnable,
                                    "verification-upload-reservation-recovery"
                            );
                            thread.setDaemon(true);
                            return thread;
                        }
                    }
            );

    public VerificationUploadReservationRecovery(
            VerificationAtomicUpdates verificationAtomicUpdates
    ) {
        this.verificationAtomicUpdates = verificationAtomicUpdates;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startRecovery() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        // Recover expired leases immediately after the backend becomes ready.
        recoverSafely();

        // Continue checking so expired reservations are also recovered while
        // the application remains running.
        recoveryExecutor.scheduleWithFixedDelay(
                this::recoverSafely,
                RECOVERY_INTERVAL_SECONDS,
                RECOVERY_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void recoverSafely() {
        try {
            int recoveredCount =
                    verificationAtomicUpdates.recoverExpiredUploadReservations(
                            Instant.now()
                    );

            if (recoveredCount > 0) {
                LOGGER.info(
                        "Recovered {} expired provider verification upload reservation(s)",
                        recoveredCount
                );
            }
        } catch (RuntimeException exception) {
            // A recovery failure must not prevent the application from starting.
            // The next scheduled run or the next provider/admin workflow action
            // will retry recovery.
            LOGGER.error(
                    "Unable to recover expired provider verification upload reservations",
                    exception
            );
        }
    }

    @Override
    public void destroy() {
        recoveryExecutor.shutdownNow();
    }
}
