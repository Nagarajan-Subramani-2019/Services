package com.oxygenraj.userui.api;

import com.oxygenraj.userui.client.UpstreamFailure;
import com.oxygenraj.uiplatform.UiPlatformException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Check the request fields.", fields, Instant.now()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(400, "INVALID_REQUEST", "Use valid JSON and a positive user ID.");
    }

    @ExceptionHandler(UpstreamFailure.class)
    public ResponseEntity<ApiError> upstream(UpstreamFailure exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(UiPlatformException.class)
    public ResponseEntity<ApiError> platform(UiPlatformException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Map.of(), Instant.now()));
    }

    public record ApiError(String code, String message, Map<String, String> errors, Instant timestamp) { }
}
