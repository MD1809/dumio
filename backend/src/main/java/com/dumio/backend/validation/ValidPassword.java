package com.dumio.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordValidator.class)
public @interface ValidPassword {

    String message() default "Mật khẩu cần ít nhất 8 ký tự, có chữ hoa, chữ thường, chữ số, ký hiệu đặc biệt và tối đa 72 byte UTF-8";

    boolean checkComplexity() default true;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
