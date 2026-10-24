package com.dumio.backend.exception;

import com.dumio.backend.dto.response.ErrorResponse;
import com.dumio.backend.dto.response.FieldErrorResponse;
import com.dumio.backend.enums.ErrorCode;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorResponse> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(
                        error.getField().equals("passwordConfirmed") ? "confirmPassword" : error.getField(),
                        error.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldErrorResponse::field).thenComparing(FieldErrorResponse::message))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return new ResponseEntity<>(new ErrorResponse(code.getCode(), code.getMessage(), fields),
                headers, code.getHttpStatus());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Never include parser messages: they may contain passwords or OTP values from the request.
        return new ResponseEntity<>(ErrorResponse.from(ErrorCode.VALIDATION_ERROR),
                headers, ErrorCode.VALIDATION_ERROR.getHttpStatus());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthException ex) {
        return response(ex.getErrorCode());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"uk_users_email\"")) {
                return response(ErrorCode.EMAIL_ALREADY_EXISTS);
            }
            cause = cause.getCause();
        }
        return internalError(ex);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleUnavailable(Exception ex) {
        log.error("Authentication storage unavailable: {}", ex.getClass().getSimpleName());
        return response(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return internalError(ex);
    }

    private ResponseEntity<ErrorResponse> internalError(Exception ex) {
        log.error("Unexpected authentication error: {}", ex.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode code) {
        return ResponseEntity.status(code.getHttpStatus()).body(ErrorResponse.from(code));
    }
}
