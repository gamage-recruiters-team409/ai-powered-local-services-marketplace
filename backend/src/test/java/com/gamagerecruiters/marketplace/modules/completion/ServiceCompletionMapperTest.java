package com.gamagerecruiters.marketplace.modules.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.mapper.ServiceCompletionMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ServiceCompletionMapperTest {

    @Test
    void mapsCompletionToResponse() {
        Instant now = Instant.now();
        ServiceCompletion completion = new ServiceCompletion();
        completion.setId("sc1");
        completion.setBookingId("b1");
        completion.setStartedAt(now);
        completion.setCompletionNote("Cleaned 3 rooms");
        completion.setPhotoUrls(List.of("https://example.com/p1.jpg"));
        completion.setMarkedCompletedAt(now);
        completion.setConfirmedAt(now);
        completion.setConfirmationType(CompletionConfirmationType.CUSTOMER);

        ServiceCompletionResponse response = ServiceCompletionMapper.toResponse(completion);

        assertEquals("sc1", response.id());
        assertEquals("b1", response.bookingId());
        assertEquals(1, response.photoUrls().size());
        assertEquals(CompletionConfirmationType.CUSTOMER, response.confirmationType());
    }

    @Test
    void newCompletionHasEmptyPhotoList() {
        ServiceCompletionResponse response = ServiceCompletionMapper.toResponse(new ServiceCompletion());
        assertTrue(response.photoUrls().isEmpty());
        assertNull(response.confirmedAt());
    }

    @Test
    void moreThanFivePhotosIsRejected() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            List<String> sixPhotos = List.of("a", "b", "c", "d", "e", "f");
            assertEquals(1, validator.validate(new MarkCompletedRequest("note", sixPhotos)).size());
        }
    }
}
