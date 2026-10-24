package com.dumio.backend.dto.response;

import com.dumio.backend.enums.ErrorCode;
import java.util.List;

public record ErrorResponse(String code, String message, List<FieldErrorResponse> fieldErrors) {

    public ErrorResponse {
        fieldErrors = List.copyOf(fieldErrors);
    }

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
    }
}
