package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContactMasker {

    private static final String MASK = "[contact hidden]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.!#$%&'*+/=?^_`{|}~-]+@[\\w-]+(?:\\.[\\w-]+)+(?![\\w.-])");
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<![\\d.+-])(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])(?![\\d.+-])");

    public ContactMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String masked = EMAIL.matcher(text).replaceAll(MASK);
        Matcher dates = ISO_DATE.matcher(masked);
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (dates.find()) {
            result.append(maskNonDatePiece(masked.substring(position, dates.start())));
            result.append(dates.group());
            position = dates.end();
        }
        result.append(maskNonDatePiece(masked.substring(position)));
        return result.toString();
    }

    private static String maskNonDatePiece(String text) {
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (position < text.length()) {
            char current = text.charAt(position);
            if (Character.isDigit(current)
                    || (current == '(' && followedWithinFewCharactersByDigit(text, position))
                    || (current == '+' && position + 1 < text.length()
                            && Character.isDigit(text.charAt(position + 1)))) {
                int end = position;
                if (text.charAt(end) == '+') {
                    end++;
                }
                while (end < text.length() && isCandidateCharacter(text.charAt(end))) {
                    end++;
                }
                int candidateEnd = end;
                while (candidateEnd > position && isTrailingSeparator(text.charAt(candidateEnd - 1))) {
                    candidateEnd--;
                }
                if (candidateEnd == position) {
                    result.append(current);
                    position++;
                    continue;
                }
                result.append(maskCandidate(text.substring(position, candidateEnd)));
                position = candidateEnd;
            } else {
                result.append(current);
                position++;
            }
        }
        return result.toString();
    }

    private static boolean followedWithinFewCharactersByDigit(String text, int position) {
        int end = Math.min(text.length(), position + 4);
        for (int i = position + 1; i < end; i++) {
            char character = text.charAt(i);
            if (character == ' ' || character == '\t') {
                continue;
            }
            return Character.isDigit(character);
        }
        return false;
    }

    private static boolean isCandidateCharacter(char character) {
        return Character.isDigit(character) || character == ' ' || character == '\t'
                || character == '.' || character == '-' || character == '(' || character == ')'
                || character == '[' || character == ']';
    }

    private static boolean isTrailingSeparator(char character) {
        return character == ' ' || character == '\t' || character == '.'
                || character == '-' || character == '(' || character == ')' || character == '[' || character == ']';
    }

    private static String maskCandidate(String candidate) {
        if (countDigits(candidate) < 9) {
            return candidate;
        }

        String[] whitespaceParts = candidate.split("\\s+");
        if (whitespaceParts.length > 1) {
            boolean eachPartLooksLikeAFullNumber = true;
            for (String part : whitespaceParts) {
                if (countDigits(part) < 7) {
                    eachPartLooksLikeAFullNumber = false;
                    break;
                }
            }
            if (eachPartLooksLikeAFullNumber) {
                StringBuilder separated = new StringBuilder();
                for (int i = 0; i < candidate.length();) {
                    while (i < candidate.length() && !Character.isWhitespace(candidate.charAt(i))) {
                        i++;
                    }
                    separated.append(MASK);
                    while (i < candidate.length() && Character.isWhitespace(candidate.charAt(i))) {
                        separated.append(candidate.charAt(i));
                        i++;
                    }
                }
                return separated.toString();
            }
        }
        return MASK;
    }

    private static int countDigits(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
