package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.ApiException;
import com.chris64233.cc.documentquorum.service.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponses.ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getCode().getStatus())
                .body(new ApiResponses.ApiError(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponses.ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.VALIDATION.getStatus())
                .body(new ApiResponses.ApiError(ErrorCode.VALIDATION.name(), message));
    }
}
