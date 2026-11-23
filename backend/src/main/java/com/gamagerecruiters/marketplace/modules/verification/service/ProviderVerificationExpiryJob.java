
package com.gamagerecruiters.marketplace.modules.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(
        name = "app.provider-verification.expiry.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProviderVerificationExpiryJob {

    private static final Logger log =
            LoggerFactory.getLogger(ProviderVerificationExpiryJob.class);

    private final ProviderVerificationExpiryService expiryService;

    public ProviderVerificationExpiryJob(
            ProviderVerificationExpiryService expiryService
    ) {
        this.expiryService = expiryService;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.provider-verification.expiry.interval:PT1H}",
            initialDelayString =
                    "${app.provider-verification.expiry.initial-delay:PT1M}"
    )
    public void run() {

        try {

            int expiredCount =
                    expiryService.processExpiredVerifications();

            if (expiredCount > 0) {

                log.info(
                        "Verification expiry job completed. Expired providers: {}",
                        expiredCount
                );
            }

        } catch (RuntimeException exception) {

            log.error(
                    "Provider verification expiry job failed",
                    exception
            );
        }
    }
}
