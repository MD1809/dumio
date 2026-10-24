package com.dumio.backend.dto.response;

import com.dumio.backend.enums.UserRole;
import java.util.UUID;

public record UserResponse(UUID id, String username, String email, UserRole role) {
}
