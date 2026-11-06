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

/**
 * 링크 관리 API. @CurrentUser가 JWT subject를 내부 users.id로 변환해 주입한다.
 * 링크는 워크스페이스가 소유하므로 모든 경로가 워크스페이스 아래에 있다(#204). 경로의 workspaceId는 외부 노출용 UUID다.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/links")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @PostMapping
    public ResponseEntity<ApiResponse<LinkResponse>> create(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateLinkRequest request
    ) {
        LinkResponse response = linkService.create(userId, workspaceId, request);
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크가 생성되었습니다."));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LinkListResponse>> getList(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @Valid @ModelAttribute LinkListQuery query
    ) {
        LinkListResponse response = linkService.getList(userId, workspaceId, query.page(), query.size());
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크 목록 조회 성공"));
    }

    @PatchMapping("/{linkId}")
    public ResponseEntity<ApiResponse<UpdateLinkResponse>> update(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @PathVariable String linkId,
            @Valid @RequestBody UpdateLinkRequest request
    ) {
        UpdateLinkResponse response = linkService.update(userId, workspaceId, linkId, request);
        return ResponseEntity.ok(ApiResponse.success(200, response, "링크가 수정되었습니다."));
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @PathVariable String linkId
    ) {
        linkService.delete(userId, workspaceId, linkId);
        return ResponseEntity.ok(ApiResponse.success(200, null, "링크가 삭제되었습니다."));
    }
}
