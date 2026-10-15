package com.example.transactions.api;

import com.example.transactions.service.IdempotencyConflictException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String,Object>> conflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","IDEMPOTENCY_CONFLICT","message",ex.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> invalid(MethodArgumentNotValidException ex) {
        List<String> errors=ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField()+": "+e.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(Map.of("error","VALIDATION_FAILED","details",errors));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String,Object>> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error","NOT_FOUND","message",ex.getMessage()));
    }
}
