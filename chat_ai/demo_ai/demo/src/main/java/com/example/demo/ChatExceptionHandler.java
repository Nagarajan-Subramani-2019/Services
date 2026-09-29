package com.example.demo;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler({ResourceAccessException.class, TransientAiException.class})
    public ResponseEntity<ApiError> handleOllamaUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("Ollama is unavailable. Start Ollama and pull the configured model."));
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ApiError> handleOllamaRequestFailure(NonTransientAiException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("Ollama rejected the request or the configured model is unavailable."));
    }

    @ExceptionHandler(EmptyModelResponseException.class)
    public ResponseEntity<ApiError> handleEmptyModelResponse(EmptyModelResponseException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("The local model did not return a final answer. Please try again."));
    }

    public record ApiError(String error) {
    }
}
