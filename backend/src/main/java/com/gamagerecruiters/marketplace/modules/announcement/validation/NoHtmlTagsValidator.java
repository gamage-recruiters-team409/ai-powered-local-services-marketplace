package com.gamagerecruiters.marketplace.modules.announcement.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoHtmlTagsValidator implements ConstraintValidator<NoHtmlTags, String> {

    /**
     * An HTML start/end tag (a letter right after {@code <} or {@code </}, like browsers parse it, including
     * {@code <img/src=x>}), a comment, a doctype/declaration or a processing instruction. Text such as
     * {@code a < b and c > d} or a markdown autolink {@code <https://...>} is not a tag.
     */
    static final Pattern HTML = Pattern.compile(
            "</?[A-Za-z][A-Za-z0-9-]*(?:[\\s/][^<>]*)?>|<!--|<![A-Za-z]|<\\?");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return !containsHtml(value);
    }

    public static boolean containsHtml(String value) {
        return value != null && HTML.matcher(value).find();
    }
}
