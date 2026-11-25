package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EmailTemplateBuilderTest {

    private final EmailTemplateBuilder builder = new EmailTemplateBuilder();

    @Test
    void escapesScriptAndQuotesInNotificationValues() {
        EmailTemplateBuilder.EmailTemplate template = builder.build(EmailType.NOTIFICATION,
                Map.of("name", "<script>name</script>", "title", "\"title\"", "message", "<script>message</script>"),
                "https://app.example");

        assertThat(template.htmlContent()).contains("&lt;script&gt;name&lt;/script&gt;", "&quot;title&quot;",
                "&lt;script&gt;message&lt;/script&gt;");
    }

    @Test
    void notificationButtonAlwaysUsesFrontendBaseUrl() {
        EmailTemplateBuilder.EmailTemplate template = builder.build(EmailType.NOTIFICATION,
                Map.of("name", "Person", "title", "Title", "message", "Message", "link", "https://bad.example"),
                "https://app.example");

        assertThat(template.htmlContent()).contains("href=\"https://app.example\"")
                .doesNotContain("https://bad.example");
    }

    @Test
    void notificationButtonUsesNormalizedBaseUrl() {
        EmailTemplateBuilder.EmailTemplate template = builder.build(EmailType.NOTIFICATION,
                Map.of("name", "Person", "title", "Title", "message", "Message"),
                "https://app.example/");

        assertThat(template.htmlContent()).contains("href=\"https://app.example\"");
    }

    @Test
    void acceptsOnlyLinksWithinTheExactFrontendBasePath() {
        EmailTemplateBuilder.EmailTemplate template = builder.build(EmailType.ACCOUNT_VERIFICATION,
                Map.of("name", "Person", "verificationLink", "http://localhost:5173/verify?token=abc",
                        "expiresInMinutes", 10),
                "http://localhost:5173/");

        assertThat(template.htmlContent()).contains("http://localhost:5173/verify?token=abc");
    }

    @Test
    void rejectsUnsafeOrLookAlikeVerificationLinks() {
        for (String link : new String[]{"http://localhost:5173.evil.com/x", "http://localhost:5173@evil.com",
                "https://evil.com", "javascript:alert(1)", "http://localhost:5173/verify link"}) {
            assertThatThrownBy(() -> builder.build(EmailType.ACCOUNT_VERIFICATION,
                    Map.of("name", "Person", "verificationLink", link, "expiresInMinutes", 10),
                    "http://localhost:5173"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsEveryLinkWhenFrontendBaseUrlIsBlank() {
        assertThatThrownBy(() -> builder.build(EmailType.ACCOUNT_VERIFICATION,
                Map.of("name", "Person", "verificationLink", "http://localhost:5173/verify", "expiresInMinutes", 10),
                " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
