package com.example.management.links.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 목록 조회의 기본 페이지는 0, 기본 크기는 20이다. */
public record LinkListQuery(
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size
) {
    public LinkListQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}