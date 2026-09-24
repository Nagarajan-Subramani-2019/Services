package com.oxygenraj.transact.domain;

import com.oxygenraj.transact.api.TransactionResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Transaction(
        long id,
        long userId,
        String monthName,
        int monthCount,
        BigDecimal amount,
        OffsetDateTime createdAt,
        OffsetDateTime modifiedAt,
        long version) {

    public TransactionResponse toResponse() {
        return new TransactionResponse(id, userId, monthName, monthCount, amount, createdAt, modifiedAt, version);
    }
}
