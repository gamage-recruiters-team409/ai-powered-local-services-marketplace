package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EmailTemplateBuilder {
    public record EmailTemplate(String textContent, String htmlContent) { }

    public EmailTemplate build(EmailType type, Map<String, Object> data, String frontendBaseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(frontendBaseUrl);
        String name = escape(String.valueOf(data.get("name")));
        String title = type == EmailType.NOTIFICATION ? escape(String.valueOf(data.get("title"))) : subject(type, data);
        String message = type == EmailType.NOTIFICATION ? escape(String.valueOf(data.get("message"))) : defaultMessage(type, data);
        String target = type == EmailType.NOTIFICATION ? normalizedBaseUrl : link(type, data);
        if (!isSafeTarget(target, normalizedBaseUrl)) throw new IllegalArgumentException("Invalid email link");
        String escapedTarget = escape(target);
        String text = title + "\n\nHello " + name + ",\n" + message + "\n\n" + target + "\n\nAI-Powered Local Services Marketplace";
        String html = "<html><body style=\"font-family:Arial,sans-serif;color:#1f2937\"><h2>" + title
                + "</h2><p>Hello " + name + ",</p><p>" + message
                + "</p><p><a style=\"background:#0F766E;color:#fff;padding:12px 18px;text-decoration:none;border-radius:4px\" href=\""
                + escapedTarget + "\">Open marketplace</a></p><p style=\"font-size:12px\">AI-Powered Local Services Marketplace</p></body></html>";
        return new EmailTemplate(text, html);
    }
    private String subject(EmailType type, Map<String, Object> data) { return type == EmailType.ACCOUNT_VERIFICATION ? "Verify your email" : "Reset your password"; }
    private String defaultMessage(EmailType type, Map<String, Object> data) { return type == EmailType.ACCOUNT_VERIFICATION ? "Please verify your email address." : "Please reset your password."; }
    private String link(EmailType type, Map<String, Object> data) { return String.valueOf(data.get(type == EmailType.ACCOUNT_VERIFICATION ? "verificationLink" : "resetLink")); }
    private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) throw new IllegalArgumentException("Invalid frontend base URL");
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank() || !(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new IllegalArgumentException("Invalid frontend base URL");
        }
        return normalized;
    }
    private boolean isSafeTarget(String target, String normalizedBaseUrl) {
        if (target == null || target.indexOf('\\') >= 0) return false;
        for (int index = 0; index < target.length(); index++) if (Character.isWhitespace(target.charAt(index)) || Character.isISOControl(target.charAt(index))) return false;
        if (target.equals(normalizedBaseUrl)) return true;
        if (!target.startsWith(normalizedBaseUrl) || target.length() == normalizedBaseUrl.length()) return false;
        char separator = target.charAt(normalizedBaseUrl.length());
        return separator == '/' || separator == '?' || separator == '#';
    }
}
