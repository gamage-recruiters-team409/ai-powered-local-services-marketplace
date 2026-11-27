package com.gamagerecruiters.marketplace.common.validation;

import com.gamagerecruiters.marketplace.common.util.ValidationUtils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidFullNameValidator implements ConstraintValidator<ValidFullName, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Let @NotBlank handle null checks
        }
        return ValidationUtils.isValidFullName(value);
    }
}
