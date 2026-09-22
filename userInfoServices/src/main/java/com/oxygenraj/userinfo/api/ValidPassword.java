package com.oxygenraj.userinfo.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidPassword.Validator.class)
public @interface ValidPassword {
    String message() default "Password must have at least 10 characters and at most 72 UTF-8 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value != null && !value.isBlank() && value.codePointCount(0, value.length()) >= 10
                    && value.length() <= 72 && value.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
}
