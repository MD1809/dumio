package com.dumio.backend.mapper;

import com.dumio.backend.dto.response.UserResponse;
import com.dumio.backend.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
    }
}
