package com.marlabs.assessment.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Request validation failed"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> statusError(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value() ? "UNKNOWN_CALLER" : "INVALID_REQUEST";
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("code", code, "message", exception.getReason()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> dependencyFailure(RestClientException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("code", "DEPENDENCY_FAILURE", "message", "Policy service unavailable"));
    }
}