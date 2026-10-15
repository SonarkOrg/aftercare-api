package com.aftercare.aftercareapi.Models.Dto;

import java.util.List;

/** {items, page (0-based), size, hasMore}; no total count. */
public record PageResponse<T>(List<T> items, int page, int size, boolean hasMore) {
}
