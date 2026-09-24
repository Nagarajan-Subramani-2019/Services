package com.oxygenraj.transact.api;

import java.util.List;

public record TransactionPage(
        List<TransactionResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages) {

    public TransactionPage {
        items = List.copyOf(items);
    }
}
