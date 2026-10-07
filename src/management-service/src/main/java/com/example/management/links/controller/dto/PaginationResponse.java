package com.example.management.links.controller.dto;

public record PaginationResponse(
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
