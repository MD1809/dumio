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
import org.hibernate.validator.constraints.CodePointLength;

public record RegisterRequest(
        @NotBlank(message = "Tên người dùng không được để trống")
        @CodePointLength(max = 100, message = "Tên người dùng tối đa 100 ký tự")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "Tên người dùng không được chứa ký tự đach biệt")
        String username,

        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        @Pattern(regexp = "[^\\s\\p{Z}\\p{Cc}]+", message = "Email không đúng định dạng")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @ValidPassword
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,

        @NotBlank(message = "Vui lòng xác nhận mật khẩu")
        @Size(max = 72, message = "Mật khẩu xác nhận quá dài")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String confirmPassword) {

    public RegisterRequest {
        username = AuthInputNormalizer.username(username);
        email = AuthInputNormalizer.email(email);
    }

    @AssertTrue(message = "Mật khẩu xác nhận không khớp")
    @JsonIgnore
    public boolean isPasswordConfirmed() {
        return password == null || confirmPassword == null || password.equals(confirmPassword);
    }

    @Override
    public String toString() {
        return "RegisterRequest[redacted]";
    }
}
