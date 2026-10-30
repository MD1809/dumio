package com.dumio.backend.dto.request;

import com.dumio.backend.validation.VocabularyInputNormalizer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record RestoreVocabularyTopicRequest(
        @NotNull(message = "Vui lòng cung cấp phiên bản dữ liệu")
        @PositiveOrZero(message = "Phiên bản dữ liệu không hợp lệ") Long version,
        @Pattern(regexp = "[^\\x00]*", message = "Tên chủ đề chứa ký tự không hợp lệ")
        @CodePointLength(min = 1, max = 150, message = "Tên chủ đề phải có nội dung và tối đa 150 ký tự") String name) {
    public RestoreVocabularyTopicRequest {
        if (name != null) name = java.util.Objects.requireNonNullElse(VocabularyInputNormalizer.text(name), "");
    }

}
