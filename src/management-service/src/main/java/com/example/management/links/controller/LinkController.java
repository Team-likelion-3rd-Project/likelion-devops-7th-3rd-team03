package com.example.management.links.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.common.api.ApiResponse;
import com.example.management.links.controller.dto.CreateLinkRequest;
import com.example.management.links.controller.dto.LinkResponse;
import com.example.management.links.controller.dto.LinkListResponse;
import com.example.management.links.controller.dto.LinkListQuery;
import com.example.management.links.controller.dto.UpdateLinkRequest;
import com.example.management.links.controller.dto.UpdateLinkResponse;
import com.example.management.links.service.LinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 링크 관리 API. @CurrentUser가 JWT subject를 내부 users.id로 변환해 주입한다. */
@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @PostMapping
    public ResponseEntity<ApiResponse<LinkResponse>> create(
            @CurrentUser Long userId,
            @Valid @RequestBody CreateLinkRequest request
    ) {
        LinkResponse response = linkService.create(userId, request);
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크가 생성되었습니다."));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LinkListResponse>> getList(
            @CurrentUser Long userId,
            @Valid @ModelAttribute LinkListQuery query
    ) {
        LinkListResponse response = linkService.getList(userId, query.page(), query.size());
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크 목록 조회 성공"));
    }

    @PatchMapping("/{linkId}")
    public ResponseEntity<ApiResponse<UpdateLinkResponse>> update(
            @CurrentUser Long userId,
            @PathVariable String linkId,
            @Valid @RequestBody UpdateLinkRequest request
    ) {
        UpdateLinkResponse response = linkService.update(userId, linkId, request);
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크가 수정되었습니다."));
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @CurrentUser Long userId,
            @PathVariable String linkId
    ) {
        linkService.delete(userId, linkId);
        return ResponseEntity.ok(ApiResponse.success(200, null, "링크가 삭제되었습니다."));
    }
}
