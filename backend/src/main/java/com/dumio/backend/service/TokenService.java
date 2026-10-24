package com.dumio.backend.service;

import com.dumio.backend.dto.response.TokenResponse;
import com.dumio.backend.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnore;

public interface TokenService {

    TokenPair issue(User user);

    TokenPair rotate(String refreshToken);

    void revokeForLogout(String refreshToken);

    // Internal result only; the controller sends response() as JSON and the refresh token as a cookie.
    record TokenPair(TokenResponse response, @JsonIgnore String refreshToken) {
        @Override
        public String toString() {
            return "TokenPair[redacted]";
        }
    }
}
