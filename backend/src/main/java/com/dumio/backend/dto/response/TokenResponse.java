package com.dumio.backend.dto.response;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
