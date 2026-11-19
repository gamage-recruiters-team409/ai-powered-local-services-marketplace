package com.gamagerecruiters.marketplace.modules.announcement.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementIntent;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** Request limits and the raw-HTML rejection (markdown is allowed). */
class AnnouncementRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AnnouncementRequest request(String title, String message) {
        return new AnnouncementRequest(title, message, AnnouncementAudience.ALL_USERS, null, null, null,
                AnnouncementIntent.DRAFT);
    }

    private static Set<String> invalidFields(AnnouncementRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("limits: title max 80, message max 5000; both required; audience and intent required")
    void limits() {
        assertThat(invalidFields(request("t".repeat(80), "m".repeat(5000)))).isEmpty();
        assertThat(invalidFields(request("t".repeat(81), "m"))).containsExactly("title");
        assertThat(invalidFields(request("t", "m".repeat(5001)))).containsExactly("message");
        assertThat(invalidFields(request("  ", "  "))).containsExactlyInAnyOrder("title", "message");
        assertThat(invalidFields(new AnnouncementRequest("t", "m", null, null, null, null, null)))
                .containsExactlyInAnyOrder("audience", "intent");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "Hello <b>bold</b>",
            "<img src=x onerror=alert(1)>",
            "text </div> end",
            "<a href=\"https://evil.example\">click</a>",
            "<svg onload=alert(1)>",
            "<!-- hidden -->",
            "<!DOCTYPE html>",
            "<?xml version=\"1.0\"?>",
            "<iframe/src=x>"})
    @DisplayName("raw HTML tags in the message are rejected")
    void htmlRejected(String message) {
        assertThat(invalidFields(request("Title", message))).containsExactly("message");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "**Bold** and _italic_ with a [link](https://example.com)",
            "Visit <https://example.com> for details",
            "Prices: a < b and c > d",
            "# Heading\n\n- item 1\n- item 2\n\n`code < 5`",
            "Use 2<3 as an example"})
    @DisplayName("markdown (links, autolinks, comparisons, code) is allowed")
    void markdownAllowed(String message) {
        assertThat(invalidFields(request("Title", message))).isEmpty();
    }

    @Test
    @DisplayName("raw HTML in the title is rejected too")
    void htmlTitleRejected() {
        assertThat(invalidFields(request("<b>Sale</b>", "ok"))).containsExactly("title");
    }
}
