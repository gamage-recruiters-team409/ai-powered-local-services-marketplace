package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class BrevoEmailSenderTest {
    private static final String API_KEY = "test-api-key";
    private static final String RECIPIENT = "person@example.com";

    @Test
    void startupFailsWhenApiKeyIsBlank() {
        SenderFixture fixture = sender("", "sender@example.com");
        assertThatThrownBy(fixture.sender()::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.brevo.api-key");
    }

    @Test
    void startupFailsWhenSenderAddressIsBlank() {
        SenderFixture fixture = sender(API_KEY, " ");
        assertThatThrownBy(fixture.sender()::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.from-address");
    }

    @Test
    void startupFailsWhenFrontendBaseUrlIsBlank() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com", " ");
        assertThatThrownBy(fixture.sender()::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.frontend.base-url");
    }

    @Test
    void invalidInputReturnsFailureWithoutThrowing() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.sender().initialize();
        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, "not-an-email", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).doesNotContain("not-an-email", API_KEY);
    }

    @Test
    void successfulRequestSendsApiKeyAndReturnsProviderMessageId() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email")).andExpect(method(POST))
                .andExpect(header("api-key", API_KEY))
                .andRespond(withSuccess("{\"messageId\":\"provider-id\"}", MediaType.APPLICATION_JSON));
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("provider-id");
        fixture.server().verify();
    }

    @Test
    void notificationWithConnectionFailureReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(request -> {
            throw new ResourceAccessException("connection failure", new IOException("network unavailable"));
        });
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertSafeFailure(result, "network unavailable");
        fixture.server().verify();
    }

    @Test
    void timeoutFailureReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("timed out"));
        });
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertSafeFailure(result, "timed out");
        fixture.server().verify();
    }

    @Test
    void unauthorizedResponseReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("provider says invalid credential"));
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertSafeFailure(result, "provider says invalid credential");
        fixture.server().verify();
    }

    @Test
    void serverErrorResponseReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("provider internal detail"));
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertSafeFailure(result, "provider internal detail");
        fixture.server().verify();
    }

    @Test
    void malformedSuccessResponseReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.server().expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withSuccess("not json response", MediaType.APPLICATION_JSON));
        fixture.sender().initialize();

        EmailResult result = fixture.sender().sendEmail(EmailType.NOTIFICATION, RECIPIENT, notificationData());

        assertSafeFailure(result, "not json response");
        fixture.server().verify();
    }

    @Test
    void verificationLinkOutsideFrontendBaseUrlReturnsSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.sender().initialize();
        EmailResult result = fixture.sender().sendEmail(EmailType.ACCOUNT_VERIFICATION, RECIPIENT,
                Map.of("name", "Person", "verificationLink", "https://outside.example/verify", "expiresInMinutes", 10));

        assertSafeFailure(result, "outside.example");
    }

    @Test
    void unsafeVerificationLinksReturnSafeFailure() {
        SenderFixture fixture = sender(API_KEY, "sender@example.com");
        fixture.sender().initialize();
        for (String link : new String[]{"http://localhost:5173.evil.com/x", "http://localhost:5173@evil.com",
                "https://evil.com", "javascript:alert(1)", "http://localhost:5173/verify link"}) {
            EmailResult result = fixture.sender().sendEmail(EmailType.ACCOUNT_VERIFICATION, RECIPIENT,
                    Map.of("name", "Person", "verificationLink", link, "expiresInMinutes", 10));
            assertSafeFailure(result, link);
        }
    }

    private static void assertSafeFailure(EmailResult result, String responseDetail) {
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Email could not be sent");
        assertThat(result.errorMessage()).doesNotContain(API_KEY, RECIPIENT, responseDetail);
    }

    private static Map<String, Object> notificationData() {
        return Map.of("name", "Person", "title", "Title", "message", "Message");
    }

    private static SenderFixture sender(String apiKey, String fromAddress) {
        return sender(apiKey, fromAddress, "https://app.example");
    }

    private static SenderFixture sender(String apiKey, String fromAddress, String frontendBaseUrl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(builder.build(), new EmailTemplateBuilder(), apiKey, fromAddress,
                "Marketplace", 1, frontendBaseUrl);
        return new SenderFixture(sender, server);
    }

    private record SenderFixture(BrevoEmailSender sender, MockRestServiceServer server) {
    }
}
