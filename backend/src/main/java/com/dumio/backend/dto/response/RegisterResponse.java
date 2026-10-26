package com.dumio.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record RegisterResponse(@JsonIgnore UUID registrationId, boolean verificationRequired, long expiresInSeconds) {
    @Override
    public String toString() { return "RegisterResponse[verificationRequired=" + verificationRequired + "]"; }
}
