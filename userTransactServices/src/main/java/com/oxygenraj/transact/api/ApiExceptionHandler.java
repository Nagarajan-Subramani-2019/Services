package com.oxygenraj.transact.api;

import com.oxygenraj.transact.service.TransactionService.TransactionNotFoundException;
import com.oxygenraj.transact.service.TransactionService.VersionConflictException;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Check the request fields",
                fields, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalidInput(Exception exception) {
        return error(400, "INVALID_REQUEST", "Invalid JSON, path parameter or query parameter");
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ResponseEntity<ApiError> notFound(TransactionNotFoundException exception) {
        return error(404, "TRANSACTION_NOT_FOUND", "Transaction not found");
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<ApiError> staleVersion(VersionConflictException exception) {
        return error(409, "VERSION_CONFLICT", "Transaction has changed; retrieve its latest version before updating");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> unavailable(RuntimeException exception) {
        // Do not expose or log SQL, connection details or database exception messages.
        return error(503, "DATABASE_UNAVAILABLE", "The database request could not be completed");
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Map.of(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    public record ApiError(String code, String message, Map<String, String> errors, OffsetDateTime timestamp) {}
}
