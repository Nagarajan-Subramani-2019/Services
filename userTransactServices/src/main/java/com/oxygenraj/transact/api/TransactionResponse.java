package com.oxygenraj.transact.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
        long id,
        long userId,
        String monthName,
        int monthCount,
        BigDecimal amount,
        OffsetDateTime createdAt,
        OffsetDateTime modifiedAt,
        long version) {}
