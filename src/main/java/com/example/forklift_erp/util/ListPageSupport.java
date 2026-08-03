package com.example.forklift_erp.util;

import com.example.forklift_erp.common.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ListPageSupport {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private ListPageSupport() {
    }

    public static int page(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    public static int size(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public static Pageable pageRequest(Integer page, Integer size) {
        return PageRequest.of(page(page), size(size));
    }

    public static Pageable pageRequest(Integer page, Integer size, Sort sort) {
        return PageRequest.of(page(page), size(size), sort);
    }

    public static <T> PageResult<T> page(List<T> rows, Integer page, Integer size) {
        int normalizedPage = page(page);
        int normalizedSize = size(size);
        long offset = (long) normalizedPage * normalizedSize;
        int fromIndex = (int) Math.min(offset, rows.size());
        int toIndex = Math.min(fromIndex + normalizedSize, rows.size());
        return PageResult.of(rows.subList(fromIndex, toIndex), normalizedPage, normalizedSize, rows.size());
    }

    public static <T> List<T> filter(List<T> rows, String keyword, Function<T, Stream<String>> textProvider) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> textProvider.apply(row)
                        .filter(Objects::nonNull)
                        .map(ListPageSupport::normalize)
                        .anyMatch(value -> value.contains(normalized)))
                .toList();
    }

    public static Stream<String> text(Object... values) {
        return Arrays.stream(values).map(value -> value == null ? "" : String.valueOf(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
