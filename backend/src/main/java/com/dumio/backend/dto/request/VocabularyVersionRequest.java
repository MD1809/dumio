package com.dumio.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record VocabularyVersionRequest(
        @NotNull(message = "Vui lòng cung cấp phiên bản dữ liệu")
        @PositiveOrZero(message = "Phiên bản dữ liệu không hợp lệ") Long version) {
}
