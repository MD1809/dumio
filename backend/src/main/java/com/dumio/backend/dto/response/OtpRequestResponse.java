package com.dumio.backend.dto.response;

public record OtpRequestResponse(String message, long expiresInSeconds) {
}
