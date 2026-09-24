package com.oxygenraj.transact.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Locale;

public record CreateTransactionRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Pattern(regexp = CreateTransactionRequest.MONTH_NAMES,
                message = "must be a full English calendar month name") String monthName,
        @NotNull @Positive Integer monthCount,
        @NotNull @DecimalMin(value = "0.00", message = "must be zero or greater")
        @Digits(integer = 17, fraction = 2,
                message = "must have at most 17 integer digits and 2 decimal places") BigDecimal amount) {

    static final String MONTH_NAMES = "JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER";

    public CreateTransactionRequest {
        monthName = normalizeMonth(monthName);
    }

    static String normalizeMonth(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
