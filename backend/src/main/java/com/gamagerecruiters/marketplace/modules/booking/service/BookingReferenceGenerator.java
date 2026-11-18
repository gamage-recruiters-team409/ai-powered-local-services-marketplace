package com.gamagerecruiters.marketplace.modules.booking.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/**
 * Human-readable booking reference (FR-BOOK-006), e.g. {@code BK-20261123-7K3QXP}.
 * Uses letters and digits without look-alikes (no 0/O, 1/I/L). Uniqueness is guaranteed by the
 * unique {@code ref_1} index; the caller retries on the rare collision.
 */
@Component
public class BookingReferenceGenerator {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RANDOM_LENGTH = 6;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final SecureRandom random = new SecureRandom();

    public String next(Instant createdAt) {
        StringBuilder code = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return "BK-" + DATE.format(createdAt) + "-" + code;
    }
}
