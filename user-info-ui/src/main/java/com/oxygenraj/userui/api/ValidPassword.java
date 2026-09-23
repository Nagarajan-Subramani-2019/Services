package com.oxygenraj.userui.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPassword.Validator.class)
public @interface ValidPassword {
    String message() default "Use at least 10 characters and no more than 72 UTF-8 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value != null && !value.isBlank() && value.length() <= 72
                    && value.codePointCount(0, value.length()) >= 10
                    && value.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
}
