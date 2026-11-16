package com.gamagerecruiters.marketplace.modules.provider.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class CreateProviderProfileRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Valid provider profile request passes validation")
    void validRequestPassesValidation() {
        CreateProviderProfileRequest request = validRequest();

        Set<ConstraintViolation<CreateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("District longer than 100 characters is rejected")
    void districtLongerThan100CharactersIsRejected() {
        CreateProviderProfileRequest request = validRequest();

        request.setDistrict("A".repeat(101));

        Set<ConstraintViolation<CreateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .anyMatch(violation ->
                        "district".equals(
                                violation.getPropertyPath().toString()
                        )
                        &&
                        "District must not exceed 100 characters".equals(
                                violation.getMessage()
                        )
                );
    }

    @Test
    @DisplayName("City longer than 100 characters is rejected")
    void cityLongerThan100CharactersIsRejected() {
        CreateProviderProfileRequest request = validRequest();

        request.setCity("B".repeat(101));

        Set<ConstraintViolation<CreateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .anyMatch(violation ->
                        "city".equals(
                                violation.getPropertyPath().toString()
                        )
                        &&
                        "City must not exceed 100 characters".equals(
                                violation.getMessage()
                        )
                );
    }

    @Test
    @DisplayName("District at 100 characters is accepted")
    void districtAt100CharactersIsAccepted() {
        CreateProviderProfileRequest request = validRequest();

        request.setDistrict("A".repeat(100));

        Set<ConstraintViolation<CreateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .noneMatch(violation ->
                        "district".equals(
                                violation.getPropertyPath().toString()
                        )
                );
    }

    @Test
    @DisplayName("City at 100 characters is accepted")
    void cityAt100CharactersIsAccepted() {
        CreateProviderProfileRequest request = validRequest();

        request.setCity("B".repeat(100));

        Set<ConstraintViolation<CreateProviderProfileRequest>> violations =
                validator.validate(request);

        assertThat(violations)
                .noneMatch(violation ->
                        "city".equals(
                                violation.getPropertyPath().toString()
                        )
                );
    }

    private CreateProviderProfileRequest validRequest() {
        CreateProviderProfileRequest request =
                new CreateProviderProfileRequest();

        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Hiba Services");
        request.setBio("Professional local service provider.");
        request.setDistrict("Colombo");
        request.setCity("Colombo");
        request.setExperienceYears(2);
        request.setProfileImageUrl(
                "https://example.com/profile.jpg"
        );

        return request;
    }
}