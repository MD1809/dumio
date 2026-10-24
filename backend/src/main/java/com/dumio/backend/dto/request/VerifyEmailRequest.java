package com.dumio.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.dumio.backend.validation.AuthInputNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        @Pattern(regexp = "[^\\s\\p{Z}\\p{Cc}]+", message = "Email không đúng định dạng")
        String email,
        @NotBlank(message = "Mã xác nhận không được để trống")
        @Pattern(regexp = "[0-9]{6}", message = "Mã xác nhận phải gồm đúng 6 chữ số")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String code) {

    public VerifyEmailRequest {
        email = AuthInputNormalizer.email(email);
    }

    @Override
    public String toString() {
        return "VerifyEmailRequest[redacted]";
    }
}
