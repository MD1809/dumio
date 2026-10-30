package com.dumio.backend.dto.request;

import com.dumio.backend.exception.VocabularyException;
import com.dumio.backend.validation.VocabularyInputNormalizer;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record VocabularyTopicQuery(String q, String archived, PageRequest pageable) {
    private static final Set<String> FIELDS = Set.of("q", "archived", "page", "size", "sort");

    public static VocabularyTopicQuery from(MultiValueMap<String, String> parameters) {
        parameters.forEach((field, values) -> {
            if (!FIELDS.contains(field) || values.size() != 1) {
                throw VocabularyException.invalid(field, "Tham số không hợp lệ hoặc bị lặp");
            }
        });
        String q = VocabularyInputNormalizer.text(parameters.getFirst("q"));
        if (q != null && (q.codePointCount(0, q.length()) > 200 || q.indexOf('\0') >= 0)) {
            throw VocabularyException.invalid("q", "Tìm kiếm tối đa 200 ký tự và không chứa ký tự không hợp lệ");
        }
        String archived = parameters.getFirst("archived");
        if (archived == null) archived = "false";
        if (!Set.of("false", "true", "all").contains(archived)) {
            throw VocabularyException.invalid("archived", "Bộ lọc phải là false, true hoặc all");
        }
        int page = integer(parameters.getFirst("page"), "page", 0, 0, Integer.MAX_VALUE);
        int size = integer(parameters.getFirst("size"), "size", 20, 1, 100);
        String sortValue = parameters.getFirst("sort");
        if (sortValue == null) sortValue = "name,asc";
        String[] sort = sortValue.split(",", -1);
        if (sort.length != 2 || !Set.of("name", "createdAt", "updatedAt").contains(sort[0])
                || !Set.of("asc", "desc").contains(sort[1])) {
            throw VocabularyException.invalid("sort", "Thứ tự không hợp lệ; dùng name/createdAt/updatedAt,asc hoặc desc");
        }
        Sort.Order order = new Sort.Order("asc".equals(sort[1]) ? Sort.Direction.ASC : Sort.Direction.DESC, sort[0]);
        if (sort[0].equals("name")) order = order.ignoreCase();
        return new VocabularyTopicQuery(q, archived, PageRequest.of(page, size, Sort.by(order, Sort.Order.asc("id"))));
    }

    private static int integer(String value, String field, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException exception) {
            throw VocabularyException.invalid(field, "Giá trị phân trang không hợp lệ");
        }
    }
}
