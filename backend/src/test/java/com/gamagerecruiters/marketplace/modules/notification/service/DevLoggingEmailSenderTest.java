package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.HashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DevLoggingEmailSenderTest {

    private static final String EMAIL = "person@example.com";
    private static final String VERIFICATION_LINK = "https://example.com/verify?token=verification-secret";
    private static final String RESET_LINK = "https://example.com/reset?token=reset-secret";

    private DevLoggingEmailSender sender;
    private Logger logger;
    private ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        sender = new DevLoggingEmailSender();
        logger = (Logger) LoggerFactory.getLogger(DevLoggingEmailSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void validVerificationRequestSucceedsAndLogsOnlyMaskedRecipient() {
        EmailResult result = sender.sendEmail(
                EmailType.ACCOUNT_VERIFICATION,
                EMAIL,
                data("verificationLink", VERIFICATION_LINK, 1440)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).startsWith("dev-");
        assertThat(result.errorMessage()).isNull();
        assertThat(logMessage()).isEqualTo(
                "email type=ACCOUNT_VERIFICATION, recipient=p***@example.com, dev mode, not sent"
        );
        assertSafeOutput(result, logMessage(), VERIFICATION_LINK);
    }

    @Test
    void validResetRequestSucceedsAndMasksShortLocalPart() {
        EmailResult result = sender.sendEmail(
                EmailType.PASSWORD_RESET,
                "a@x.com",
                data("resetLink", RESET_LINK, "30")
        );

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).startsWith("dev-");
        assertThat(logMessage()).contains("recipient=***@x.com");
        assertSafeOutput(result, logMessage(), RESET_LINK);
    }

    @Test
    void invalidRequestsReturnFailureWithoutThrowing() {
        assertFailure(null, EMAIL, verificationData(), "Email type is required");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, null, verificationData(), "Recipient email is required");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, " ", verificationData(), "Recipient email is required");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, "not-an-email", verificationData(), "Recipient email is invalid");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, EMAIL, null, "Email data is required");

        for (String key : new String[]{"name", "verificationLink", "expiresInMinutes"}) {
            Map<String, Object> data = verificationData();
            data.remove(key);
            assertFailure(EmailType.ACCOUNT_VERIFICATION, EMAIL, data, "Missing required data: " + key);
        }
        for (String key : new String[]{"name", "resetLink", "expiresInMinutes"}) {
            Map<String, Object> data = resetData();
            data.remove(key);
            assertFailure(EmailType.PASSWORD_RESET, EMAIL, data, "Missing required data: " + key);
        }

        Map<String, Object> blankName = verificationData();
        blankName.put("name", " ");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, EMAIL, blankName, "Required data is blank: name");

        Map<String, Object> blankLink = verificationData();
        blankLink.put("verificationLink", "");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, EMAIL, blankLink, "Required data is blank: verificationLink");

        Map<String, Object> invalidExpiry = verificationData();
        invalidExpiry.put("expiresInMinutes", "not-a-number");
        assertFailure(EmailType.ACCOUNT_VERIFICATION, EMAIL, invalidExpiry, "expiresInMinutes must be numeric");
    }

    @Test
    void invalidRecipientFailureDoesNotContainLinkTokenOrRecipient() {
        EmailResult result = sender.sendEmail(
                EmailType.ACCOUNT_VERIFICATION,
                "not-an-email",
                verificationData()
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("Recipient email is invalid")
                .doesNotContain(VERIFICATION_LINK, "verification-secret", "not-an-email");
        assertNoSensitiveLogs(VERIFICATION_LINK, "verification-secret", "not-an-email");
    }

    @Test
    void missingDataFailureContainsOnlyMissingKey() {
        Map<String, Object> data = verificationData();
        data.remove("name");

        EmailResult result = sender.sendEmail(EmailType.ACCOUNT_VERIFICATION, EMAIL, data);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("Missing required data: name")
                .doesNotContain(VERIFICATION_LINK, "verification-secret", EMAIL, "Person");
        assertNoSensitiveLogs(VERIFICATION_LINK, "verification-secret", EMAIL);
    }

    @Test
    void singleCharacterLocalPartIsMasked() {
        EmailResult result = sender.sendEmail(
                EmailType.ACCOUNT_VERIFICATION,
                "z@domain.test",
                verificationData()
        );

        assertThat(result.success()).isTrue();
        assertThat(logMessage()).isEqualTo(
                "email type=ACCOUNT_VERIFICATION, recipient=***@domain.test, dev mode, not sent"
        );
        assertThat(logMessage()).doesNotContain("z@domain.test");
    }

    @Test
    void successLogDoesNotContainLinkTokenOrFullEmail() {
        Map<String, Object> data = verificationData();
        data.put("verificationLink", VERIFICATION_LINK);
        EmailResult result = sender.sendEmail(EmailType.ACCOUNT_VERIFICATION, EMAIL, data);

        assertThat(result.errorMessage()).isNull();
        assertThat(logMessage()).doesNotContain(VERIFICATION_LINK, "verification-secret", EMAIL);
    }

    @Test
    void unexpectedRuntimeFailureReturnsFailureWithoutThrowing() {
        Map<String, Object> throwingData = new HashMap<>() {
            @Override
            public boolean containsKey(Object key) {
                throw new RuntimeException("unexpected test failure");
            }

            @Override
            public Object get(Object key) {
                throw new RuntimeException("unexpected test failure");
            }
        };

        EmailResult result = assertDoesNotThrow(() -> sender.sendEmail(
                EmailType.ACCOUNT_VERIFICATION,
                EMAIL,
                throwingData
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("Email could not be processed")
                .doesNotContain(EMAIL);
        assertNoSensitiveLogs(EMAIL, VERIFICATION_LINK, "verification-secret");
    }

    private void assertFailure(EmailType type, String email, Map<String, Object> data, String message) {
        EmailResult result = sender.sendEmail(type, email, data);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo(message);
        assertNoSensitiveLogs(
                VERIFICATION_LINK,
                "verification-secret",
                RESET_LINK,
                "reset-secret",
                email
        );
    }

    private Map<String, Object> verificationData() {
        return data("verificationLink", VERIFICATION_LINK, 1440);
    }

    private Map<String, Object> resetData() {
        return data("resetLink", RESET_LINK, 30);
    }

    private Map<String, Object> data(String linkKey, Object link, Object expiresInMinutes) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Person");
        data.put(linkKey, link);
        data.put("expiresInMinutes", expiresInMinutes);
        return data;
    }

    private String logMessage() {
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        return logAppender.list.get(0).getFormattedMessage();
    }

    private void assertNoSensitiveLogs(String... sensitiveValues) {
        assertThat(logAppender.list)
                .allSatisfy(event -> {
                    for (String sensitiveValue : sensitiveValues) {
                        if (sensitiveValue != null) {
                            assertThat(event.getFormattedMessage()).doesNotContain(sensitiveValue);
                        }
                    }
                });
    }

    private void assertSafeOutput(EmailResult result, String logMessage, String link) {
        assertThat(result.errorMessage()).isNull();
        assertThat(logMessage).doesNotContain(link, EMAIL);
    }
}
