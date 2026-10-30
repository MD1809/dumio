package com.dumio.backend.dto.request;

import com.dumio.backend.validation.VocabularyInputNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record UpdateVocabularyTopicRequest(
        @NotBlank(message = "Tên chủ đề không được để trống")
        @Pattern(regexp = "[^\\x00]*", message = "Tên chủ đề chứa ký tự không hợp lệ")
        @CodePointLength(max = 150, message = "Tên chủ đề tối đa 150 ký tự") String name,
        @Pattern(regexp = "[^\\x00]*", message = "Mô tả chứa ký tự không hợp lệ")
        @CodePointLength(max = 2000, message = "Mô tả tối đa 2.000 ký tự") String description,
        @NotNull(message = "Vui lòng cung cấp phiên bản dữ liệu")
        @PositiveOrZero(message = "Phiên bản dữ liệu không hợp lệ") Long version) {
    public UpdateVocabularyTopicRequest {
        name = VocabularyInputNormalizer.text(name);
        description = VocabularyInputNormalizer.text(description);
    }

}
