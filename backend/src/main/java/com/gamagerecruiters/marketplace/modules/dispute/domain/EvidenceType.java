package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.util.Optional;

/**
 * The evidence file types that are accepted, detected from the file's first bytes ("magic bytes"), never from the
 * file name or the client's content type.
 */
public enum EvidenceType {
    JPG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    private final String mediaType;
    private final String format;

    EvidenceType(String mediaType, String format) {
        this.mediaType = mediaType;
        this.format = format;
    }

    public String mediaType() {
        return mediaType;
    }

    /** File extension / storage format. */
    public String format() {
        return format;
    }

    /** The type whose signature the content starts with; empty for anything else. */
    public static Optional<EvidenceType> detect(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPG);
        }
        if (startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // RIFF....WEBP
        if (startsWith(content, 0, 'R', 'I', 'F', 'F') && startsWith(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        if (startsWith(content, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
