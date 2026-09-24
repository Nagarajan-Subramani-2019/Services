package com.oxygenraj.transact.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdateTransactionRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Pattern(regexp = CreateTransactionRequest.MONTH_NAMES,
                message = "must be a full English calendar month name") String monthName,
        @NotNull @Positive Integer monthCount,
        @NotNull @DecimalMin(value = "0.00", message = "must be zero or greater")
        @Digits(integer = 17, fraction = 2,
                message = "must have at most 17 integer digits and 2 decimal places") BigDecimal amount,
        @NotNull @PositiveOrZero Long version) {

    public UpdateTransactionRequest {
        monthName = CreateTransactionRequest.normalizeMonth(monthName);
    }
}
