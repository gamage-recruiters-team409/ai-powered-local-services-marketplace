package com.gamagerecruiters.marketplace.modules.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.review.dto.CreateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ReviewRequestValidationTest {

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

    @Test
    void validRequestHasNoViolations() {
        Set<ConstraintViolation<CreateReviewRequest>> violations =
                validator.validate(new CreateReviewRequest("b1", 4, "Good service"));
        assertTrue(violations.isEmpty());
    }

    @Test
    void ratingOutOfRangeIsRejected() {
        assertEquals(1, validator.validate(new CreateReviewRequest("b1", 0, null)).size());
        assertEquals(1, validator.validate(new CreateReviewRequest("b1", 6, null)).size());
    }

    @Test
    void missingBookingIdAndRatingAreRejected() {
        assertEquals(2, validator.validate(new CreateReviewRequest(" ", null, null)).size());
    }

    @Test
    void commentLongerThan1000CharactersIsRejected() {
        String longComment = "a".repeat(1001);
        assertEquals(1, validator.validate(new CreateReviewRequest("b1", 5, longComment)).size());
    }

    @Test
    void blankProviderReplyIsRejected() {
        assertEquals(1, validator.validate(new ProviderReplyRequest("")).size());
    }
}
