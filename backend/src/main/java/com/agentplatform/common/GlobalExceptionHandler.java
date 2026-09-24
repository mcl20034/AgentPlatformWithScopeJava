package com.agentplatform.common;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.core.AuthenticationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException exception) {
        return ResponseEntity.status(401).body(ApiError.of("INVALID_CREDENTIALS", "用户名或密码不正确"));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception) {
        return ResponseEntity.status(exception.status()).body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        FieldError error = exception.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = error == null ? "请求参数不正确" : error.getDefaultMessage();
        return ResponseEntity.unprocessableEntity().body(ApiError.of("VALIDATION_FAILED", message));
    }
}
