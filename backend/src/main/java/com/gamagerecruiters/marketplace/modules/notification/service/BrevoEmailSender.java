package com.gamagerecruiters.marketplace.modules.notification.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final RestClient.Builder builder;
    private final EmailTemplateBuilder templates;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final String frontendBaseUrl;
    private final int timeoutSeconds;
    private RestClient client;

    public BrevoEmailSender(RestClient.Builder builder, EmailTemplateBuilder templates,
            @Value("${app.email.brevo.api-key:}") String apiKey,
            @Value("${app.email.from-address:}") String fromAddress,
            @Value("${app.email.from-name:}") String fromName,
            @Value("${app.email.brevo.timeout-seconds:10}") int timeoutSeconds,
            @Value("${app.frontend.base-url:}") String frontendBaseUrl) {
        this.builder = builder; this.templates = templates; this.apiKey = apiKey; this.fromAddress = fromAddress;
        this.fromName = fromName; this.timeoutSeconds = timeoutSeconds; this.frontendBaseUrl = frontendBaseUrl;
    }

    BrevoEmailSender(RestClient client, EmailTemplateBuilder templates, String apiKey, String fromAddress,
            String fromName, int timeoutSeconds, String frontendBaseUrl) {
        this.builder = null; this.client = client; this.templates = templates; this.apiKey = apiKey;
        this.fromAddress = fromAddress; this.fromName = fromName; this.timeoutSeconds = timeoutSeconds;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @PostConstruct
    void initialize() {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Missing required property: app.email.brevo.api-key");
        if (fromAddress == null || fromAddress.isBlank()) throw new IllegalStateException("Missing required property: app.email.from-address");
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) throw new IllegalStateException("Missing required property: app.frontend.base-url");
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            client = builder.requestFactory(factory).build();
        }
    }

    @Override
    public EmailResult sendEmail(EmailType type, String recipientEmail, Map<String, Object> data) {
        try {
            String error = validate(type, recipientEmail, data);
            if (error != null) return EmailResult.failure(error);
            EmailTemplateBuilder.EmailTemplate template = templates.build(type, data, frontendBaseUrl);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sender", Map.of("email", fromAddress, "name", fromName));
            payload.put("to", List.of(Map.of("email", recipientEmail)));
            payload.put("subject", type == EmailType.NOTIFICATION ? data.get("title") : type == EmailType.ACCOUNT_VERIFICATION ? "Verify your email" : "Reset your password");
            payload.put("htmlContent", template.htmlContent()); payload.put("textContent", template.textContent());
            Map<?, ?> response = client.post().uri("https://api.brevo.com/v3/smtp/email").header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);
            Object messageId = response == null ? null : response.get("messageId");
            return EmailResult.success(messageId == null ? "brevo-accepted" : messageId.toString());
        } catch (RestClientResponseException ex) {
            log.warn("Brevo email failed type={} status={}", type, ex.getStatusCode().value());
            return EmailResult.failure("Email could not be sent");
        } catch (Exception ex) {
            log.warn("Brevo email failed type={} status={}", type, "unavailable");
            return EmailResult.failure("Email could not be sent");
        }
    }

    private String validate(EmailType type, String recipient, Map<String, Object> data) {
        if (type == null) return "Email type is required";
        if (recipient == null || recipient.isBlank()) return "Recipient email is required";
        if (!EMAIL.matcher(recipient).matches()) return "Recipient email is invalid";
        if (data == null) return "Email data is required";
        String[] keys = switch (type) { case ACCOUNT_VERIFICATION -> new String[]{"name", "verificationLink", "expiresInMinutes"}; case PASSWORD_RESET -> new String[]{"name", "resetLink", "expiresInMinutes"}; case NOTIFICATION -> new String[]{"name", "title", "message"}; };
        for (String key : keys) { Object value = data.get(key); if (!data.containsKey(key)) return "Missing required data: " + key; if (value == null || value instanceof String s && s.isBlank()) return "Required data is blank: " + key; if ("expiresInMinutes".equals(key) && !positiveWhole(value)) return "expiresInMinutes must be a positive whole number"; }
        return null;
    }
    private boolean positiveWhole(Object value) { if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return ((Number) value).longValue() > 0; if (value instanceof String text && text.matches("\\d+")) try { return Long.parseLong(text) > 0; } catch (NumberFormatException ex) { return false; } return false; }
}
