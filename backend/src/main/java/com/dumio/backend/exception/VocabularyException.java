package com.dumio.backend.exception;

import com.dumio.backend.dto.response.FieldErrorResponse;
import com.dumio.backend.enums.ErrorCode;
import java.util.List;
import lombok.Getter;

@Getter
public class VocabularyException extends RuntimeException {
    private final ErrorCode errorCode;
    private final List<FieldErrorResponse> fieldErrors;

    public VocabularyException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), List.of());
    }

    public VocabularyException(ErrorCode errorCode, String message, List<FieldErrorResponse> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static VocabularyException invalid(String field, String message) {
        return new VocabularyException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(),
                List.of(new FieldErrorResponse(field, message)));
    }
}
