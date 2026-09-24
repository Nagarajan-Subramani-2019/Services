package com.oxygenraj.transactionui;

import com.oxygenraj.uiplatform.UiPlatformException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler(UiPlatformException.class)
    ResponseEntity<?> platform(UiPlatformException e) { return error(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return error(400,"INVALID_REQUEST","Use valid fields, a positive user ID, and valid pagination."); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"errors",Map.of(),"timestamp",Instant.now()));
    }
}
