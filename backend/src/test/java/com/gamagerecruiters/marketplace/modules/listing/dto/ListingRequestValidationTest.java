package com.gamagerecruiters.marketplace.modules.listing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ListingRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("Valid CreateListingRequest passes Jakarta Bean Validation cleanly")
    void validRequestPasses() {
        CreateListingRequest request = new CreateListingRequest(
                "cat-1",
                "Emergency Residential Plumbing",
                "Complete pipe leak inspection, valve replacement, and pressure testing.",
                PricingModel.FIXED,
                new BigDecimal("2500.00"),
                60,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Nugegoda",
                new GeoPointDto(79.888, 6.865),
                15,
                List.of("Nugegoda"),
                List.of("https://cdn.example.com/plumb.jpg"),
                null,
                List.of(new AddOnItemRequest("a1", "Extra fitting", new BigDecimal("500.00"), 15, true)),
                false
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("Rejects out-of-range coordinates, negative price, >50km radius, and nested invalid add-on")
    void invalidConstraintsFail() {
        CreateListingRequest invalid = new CreateListingRequest(
                "cat-1",
                "Electrical Repair",
                "Desc",
                PricingModel.HOURLY,
                new BigDecimal("50.00"),
                10,
                ExecutionModel.WORKSHOP,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(181.0, -95.0),
                60,
                List.of(),
                List.of(),
                null,
                List.of(new AddOnItemRequest("a1", "X", new BigDecimal("0.00"), -1, true)),
                true
        );

        Set<ConstraintViolation<CreateListingRequest>> violations = validator.validate(invalid);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains(
                        "basePrice",
                        "durationMin",
                        "location.longitude",
                        "location.latitude",
                        "coverageRadiusKm",
                        "addOns[0].title",
                        "addOns[0].extraPrice",
                        "addOns[0].extraDurationMin"
                );
    }

    @Test
    @DisplayName("Step 1/2 UI alignment: allows add-on with +0 mins & description, and allows Step 1 draft without pricing/location")
    void allowsZeroDurationAddOnWithDescriptionAndStep1Draft() {
        CreateListingRequest step1Draft = new CreateListingRequest(
                "cat-1",
                "Emergency Pipe Leak Repair",
                null,
                null,
                null,
                null,
                null,
                "Colombo",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new AddOnItemRequest(
                        "a1",
                        "Emergency Night Callout",
                        "Expedited response outside typical daylight business hours (8 PM - 6 AM).",
                        new BigDecimal("1500.00"),
                        0,
                        true
                )),
                true
        );

        assertThat(validator.validate(step1Draft)).isEmpty();
    }
}
