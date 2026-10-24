package com.dumio.backend.dto.response;

public record RegisterResponse(UserResponse user, boolean verificationRequired, long expiresInSeconds) {
}
