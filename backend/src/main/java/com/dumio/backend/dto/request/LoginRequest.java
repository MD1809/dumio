package com.dumio.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.dumio.backend.validation.AuthInputNormalizer;
import com.dumio.backend.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        @Pattern(regexp = "[^\\s\\p{Z}\\p{Cc}]+", message = "Email không đúng định dạng")
        String email,
        @NotBlank(message = "Mật khẩu không được để trống")
        @ValidPassword(checkComplexity = false, message = "Mật khẩu không được vượt quá 72 byte UTF-8")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password) {

    public LoginRequest {
        email = AuthInputNormalizer.email(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
