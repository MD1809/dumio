package com.dumio.backend.enums;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ"),
    INVALID_OTP(HttpStatus.BAD_REQUEST, "INVALID_OTP", "Mã xác nhận không hợp lệ hoặc đã hết hạn"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Email hoặc mật khẩu không hợp lệ"),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Thông tin xác thực không hợp lệ hoặc đã hết hạn"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Vui lòng đăng nhập lại"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF_INVALID", "Yêu cầu xác thực không hợp lệ, vui lòng thử lại"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Bạn không có quyền thực hiện thao tác này"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email đã được sử dụng"),
    VOCABULARY_TOPIC_NAME_EXISTS(HttpStatus.CONFLICT, "VOCABULARY_TOPIC_NAME_EXISTS", "Tên chủ đề đã được sử dụng"),
    VOCABULARY_VERSION_CONFLICT(HttpStatus.CONFLICT, "VOCABULARY_VERSION_CONFLICT", "Dữ liệu đã thay đổi. Vui lòng tải lại trước khi thao tác"),
    VOCABULARY_STATE_CONFLICT(HttpStatus.CONFLICT, "VOCABULARY_STATE_CONFLICT", "Trạng thái dữ liệu đã thay đổi. Vui lòng tải lại trước khi thao tác"),
    VOCABULARY_ARCHIVED(HttpStatus.CONFLICT, "VOCABULARY_ARCHIVED", "Dữ liệu đã lưu trữ. Vui lòng khôi phục trước khi chỉnh sửa"),
    VOCABULARY_NOT_FOUND(HttpStatus.NOT_FOUND, "VOCABULARY_NOT_FOUND", "Không tìm thấy dữ liệu từ vựng"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Dịch vụ tạm thời không khả dụng"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Đã xảy ra lỗi hệ thống");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
