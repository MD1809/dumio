package com.dumio.backend.dto.request;

import com.dumio.backend.validation.AuthInputNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        @Pattern(regexp = "[^\\s\\p{Z}\\p{Cc}]+", message = "Email không đúng định dạng")
        String email) {

    public ResendVerificationRequest {
        email = AuthInputNormalizer.email(email);
    }
}
