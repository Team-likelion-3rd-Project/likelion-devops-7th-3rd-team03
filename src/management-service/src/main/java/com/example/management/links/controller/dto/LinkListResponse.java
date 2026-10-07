package com.example.management.links.controller.dto;

import java.util.List;

public record LinkListResponse(
        List<LinkListItemResponse> links,
        PaginationResponse pagination
) {
}
