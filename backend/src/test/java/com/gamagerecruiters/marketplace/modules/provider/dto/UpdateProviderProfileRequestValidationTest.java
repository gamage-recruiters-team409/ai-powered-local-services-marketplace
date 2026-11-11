package com.gamagerecruiters.marketplace.modules.provider.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class UpdateProviderProfileRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Valid partial update allows omitted mandatory fields")
    void validPartialUpdateAllowsOmittedFields() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setExperienceYears(4);

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Blank display name is rejected when supplied")
    void blankDisplayNameIsRejected() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setDisplayName("   ");

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .anyMatch(violation ->
                        "displayName".equals(
                                violation.getPropertyPath().toString()
                        )
                        && "Display name must not be blank".equals(
                                violation.getMessage()
                        )
                );
    }

    @Test
    @DisplayName("Blank district is rejected when supplied")
    void blankDistrictIsRejected() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setDistrict("");

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .anyMatch(violation ->
                        "district".equals(
                                violation.getPropertyPath().toString()
                        )
                        && "District must not be blank".equals(
                                violation.getMessage()
                        )
                );
    }

    @Test
    @DisplayName("Whitespace-only city is rejected when supplied")
    void blankCityIsRejected() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setCity("   ");

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .anyMatch(violation ->
                        "city".equals(
                                violation.getPropertyPath().toString()
                        )
                        && "City must not be blank".equals(
                                violation.getMessage()
                        )
                );
    }

    @Test
    @DisplayName("Valid supplied mandatory fields pass validation")
    void validMandatoryFieldsPassValidation() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setDisplayName("Hiba Services");
        request.setDistrict("Colombo");
        request.setCity("Colombo");

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("District and city length limits are enforced")
    void districtAndCityLengthLimitsAreEnforced() {
        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setDistrict("A".repeat(101));
        request.setCity("B".repeat(101));

        Set<ConstraintViolation<UpdateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .extracting(violation ->
                        violation.getPropertyPath().toString()
                )
                .contains("district", "city");
    }
}