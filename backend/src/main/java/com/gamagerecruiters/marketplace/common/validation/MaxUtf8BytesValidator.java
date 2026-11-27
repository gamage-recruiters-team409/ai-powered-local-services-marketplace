package com.gamagerecruiters.marketplace.common.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes constraintAnnotation) {
        this.maxBytes = constraintAnnotation.value() != 72 ? constraintAnnotation.value() : constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Let @NotBlank handle null checks
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
