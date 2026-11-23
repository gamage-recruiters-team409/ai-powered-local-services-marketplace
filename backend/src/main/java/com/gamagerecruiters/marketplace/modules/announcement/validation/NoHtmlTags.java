package com.gamagerecruiters.marketplace.modules.announcement.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects raw HTML in text that is stored as markdown or plain text: tags such as {@code <script>}, {@code <img ...>},
 * {@code </b>}, comments, doctype and processing instructions. Markdown autolinks such as {@code <https://...>} and a
 * plain {@code a < b} are allowed. Null is valid (use @NotBlank for required fields).
 */
@Documented
@Constraint(validatedBy = NoHtmlTagsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoHtmlTags {

    String message() default "must not contain HTML tags";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
