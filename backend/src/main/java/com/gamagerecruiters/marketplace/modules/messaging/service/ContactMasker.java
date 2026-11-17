package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContactMasker {

    private static final String MASK = "[contact hidden]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.!#$%&'*+/=?^_`{|}~-]+@[\\w-]+(?:\\.[\\w-]+)+(?![\\w.-])");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "(?<!\\d)(?:\\+?[\\d(][\\d(). -]*\\d)(?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public ContactMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String masked = EMAIL.matcher(text).replaceAll(MASK);
        Matcher phones = PHONE_CANDIDATE.matcher(masked);
        StringBuffer result = new StringBuffer();
        while (phones.find()) {
            String candidate = phones.group();
            long digits = candidate.chars().filter(Character::isDigit).count();
            if (digits >= 9 && digits <= 15
                    && !candidate.matches(".* {2,}.*")
                    && !ISO_DATE.matcher(candidate).find()) {
                phones.appendReplacement(result, Matcher.quoteReplacement(MASK));
            } else {
                phones.appendReplacement(result, Matcher.quoteReplacement(candidate));
            }
        }
        phones.appendTail(result);
        return result.toString();
    }
}
