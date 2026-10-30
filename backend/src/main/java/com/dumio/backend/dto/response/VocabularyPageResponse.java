package com.dumio.backend.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record VocabularyPageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public VocabularyPageResponse {
        items = List.copyOf(items);
    }

    public static <T> VocabularyPageResponse<T> from(Page<T> result) {
        return new VocabularyPageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
