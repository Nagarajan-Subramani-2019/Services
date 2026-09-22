package com.oxygenraj.userinfo.api;

import com.oxygenraj.userinfo.service.UserService.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
        // Include field names/messages only, never rejected values such as passwords.
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Check the request fields", fields, Instant.now()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidInput(Exception exception) {
        return error(400, "INVALID_REQUEST", "Invalid JSON, path parameter or pagination value");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException exception) {
        return error(409, "USER_EXISTS", "A user with that username or email already exists");
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ApiError> notFound(UserNotFoundException exception) {
        return error(404, "NOT_FOUND", "User not found");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthorized(AuthenticationException exception) {
        return error(401, "INVALID_CREDENTIALS", "Invalid username or password");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(DataAccessException exception) {
        return error(503, "DATABASE_UNAVAILABLE", "The database request could not be completed");
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Map.of(), Instant.now()));
    }

    public record ApiError(String code, String message, Map<String, String> errors, Instant timestamp) {}
}
