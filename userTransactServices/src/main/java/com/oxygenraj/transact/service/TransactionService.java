package com.oxygenraj.transact.service;

import com.oxygenraj.transact.api.CreateTransactionRequest;
import com.oxygenraj.transact.api.TransactionPage;
import com.oxygenraj.transact.api.TransactionResponse;
import com.oxygenraj.transact.api.UpdateTransactionRequest;
import com.oxygenraj.transact.domain.Transaction;
import com.oxygenraj.transact.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {
    private final TransactionRepository repository;

    public TransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TransactionResponse create(CreateTransactionRequest request) {
        // Cross-service user existence validation is intentionally deferred.
        return repository.create(request.userId(), request.monthName(), request.monthCount(), request.amount()).toResponse();
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(long id) {
        return requireTransaction(id).toResponse();
    }

    @Transactional(readOnly = true)
    public TransactionPage list(int page, int size, Long userId) {
        var items = repository.findPage(page, size, userId).stream().map(Transaction::toResponse).toList();
        long total = repository.count(userId);
        long pages = total / size + (total % size == 0 ? 0 : 1);
        return new TransactionPage(items, page, size, total, pages);
    }

    @Transactional
    public TransactionResponse update(long id, UpdateTransactionRequest request) {
        int changed = repository.update(id, request.version(), request.userId(), request.monthName(),
                request.monthCount(), request.amount());
        if (changed == 0) {
            requireTransaction(id);
            throw new VersionConflictException();
        }
        return requireTransaction(id).toResponse();
    }

    private Transaction requireTransaction(long id) {
        return repository.findById(id).orElseThrow(TransactionNotFoundException::new);
    }

    public static class TransactionNotFoundException extends RuntimeException {}

    public static class VersionConflictException extends RuntimeException {}
}
