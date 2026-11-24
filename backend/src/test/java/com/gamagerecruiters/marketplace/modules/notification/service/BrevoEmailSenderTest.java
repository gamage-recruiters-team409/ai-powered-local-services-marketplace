package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class BrevoEmailSenderTest {

    @Test
    void startupFailsWhenApiKeyIsBlank() {
        BrevoEmailSender sender = sender("", "sender@example.com");

        assertThatThrownBy(sender::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.brevo.api-key");
    }

    @Test
    void startupFailsWhenSenderAddressIsBlank() {
        BrevoEmailSender sender = sender("key", " ");

        assertThatThrownBy(sender::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.from-address");
    }

    @Test
    void startupFailsWhenFrontendBaseUrlIsBlank() {
        BrevoEmailSender sender = new BrevoEmailSender(RestClient.builder(), new EmailTemplateBuilder(), "key",
                "sender@example.com", "Marketplace", 1, " ");

        assertThatThrownBy(sender::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.frontend.base-url");
    }

    @Test
    void invalidInputReturnsFailureWithoutThrowing() {
        BrevoEmailSender sender = sender("key", "sender@example.com");
        sender.initialize();

        EmailResult result = sender.sendEmail(EmailType.NOTIFICATION, "not-an-email", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).doesNotContain("not-an-email", "key");
    }

    @Test
    void successfulRequestSendsApiKeyAndReturnsProviderMessageId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(POST))
                .andExpect(header("api-key", "key"))
                .andRespond(withSuccess("{\"messageId\":\"provider-id\"}", MediaType.APPLICATION_JSON));
        BrevoEmailSender sender = new BrevoEmailSender(builder.build(), new EmailTemplateBuilder(), "key",
                "sender@example.com", "Marketplace", 10, "https://app.example");
        sender.initialize();

        EmailResult result = sender.sendEmail(EmailType.NOTIFICATION, "person@example.com",
                Map.of("name", "Person", "title", "Title", "message", "Message"));

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("provider-id");
        server.verify();
    }

    @Test
    void notificationWithConnectionFailureReturnsSafeFailure() {
        BrevoEmailSender sender = sender("key", "sender@example.com");
        sender.initialize();

        EmailResult result = sender.sendEmail(EmailType.NOTIFICATION, "person@example.com",
                Map.of("name", "Person", "title", "Title", "message", "Message"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Email could not be sent");
    }

    @Test
    void verificationLinkOutsideFrontendBaseUrlReturnsSafeFailure() {
        BrevoEmailSender sender = sender("key", "sender@example.com");
        sender.initialize();

        EmailResult result = sender.sendEmail(EmailType.ACCOUNT_VERIFICATION, "person@example.com",
                Map.of("name", "Person", "verificationLink", "https://outside.example/verify", "expiresInMinutes", 10));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Email could not be sent");
    }

    @Test
    void unsafeVerificationLinksReturnSafeFailure() {
        BrevoEmailSender sender = sender("key", "sender@example.com");
        sender.initialize();

        for (String link : new String[]{"http://localhost:5173.evil.com/x", "http://localhost:5173@evil.com",
                "https://evil.com", "javascript:alert(1)", "http://localhost:5173/verify link"}) {
            EmailResult result = sender.sendEmail(EmailType.ACCOUNT_VERIFICATION, "person@example.com",
                    Map.of("name", "Person", "verificationLink", link, "expiresInMinutes", 10));
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("Email could not be sent");
        }
    }

    private static BrevoEmailSender sender(String apiKey, String fromAddress) {
        return new BrevoEmailSender(RestClient.builder(), new EmailTemplateBuilder(), apiKey, fromAddress,
                "Marketplace", 1, "https://app.example");
    }
}
