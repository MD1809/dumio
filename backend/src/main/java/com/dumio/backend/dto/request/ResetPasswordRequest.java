package com.dumio.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.dumio.backend.validation.AuthInputNormalizer;
import com.dumio.backend.validation.ValidPassword;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        @Pattern(regexp = "[^\\s\\p{Z}\\p{Cc}]+", message = "Email không đúng định dạng")
        String email,
        @NotBlank(message = "Mã xác nhận không được để trống")
        @Pattern(regexp = "[0-9]{6}", message = "Mã xác nhận phải gồm đúng 6 chữ số")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String code,
        @NotBlank(message = "Mật khẩu mới không được để trống")
        @ValidPassword
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String newPassword,
        @NotBlank(message = "Vui lòng xác nhận mật khẩu mới")
        @Size(max = 72, message = "Mật khẩu xác nhận quá dài")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String confirmPassword) {

    public ResetPasswordRequest {
        email = AuthInputNormalizer.email(email);
    }

    @AssertTrue(message = "Mật khẩu xác nhận không khớp")
    @JsonIgnore
    public boolean isPasswordConfirmed() {
        return newPassword == null || confirmPassword == null || newPassword.equals(confirmPassword);
    }

    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
