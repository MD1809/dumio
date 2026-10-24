package com.dumio.backend.exception;

import com.dumio.backend.enums.ErrorCode;
import java.util.Objects;
import lombok.Getter;

@Getter
public class AuthException extends RuntimeException {

    private final ErrorCode errorCode;

    public AuthException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).getMessage());
        this.errorCode = errorCode;
    }
}
