package com.example.management.workspace.controller.dto;

import com.example.management.auth.domain.User;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.domain.WorkspaceRole;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 워크스페이스 멤버 응답. userId는 외부 노출용 UUID다.
 * 닉네임은 중복될 수 있어 같은 팀원끼리 구분할 수 있도록 이메일을 함께 내려준다.
 * me는 요청한 사용자 본인인지 여부다.
 */
public record WorkspaceMemberResponse(
        String userId,
        String nickname,
        String email,
        String profileImageUrl,
        WorkspaceRole role,
        OffsetDateTime joinedAt,
        boolean me
) {
    public static WorkspaceMemberResponse of(WorkspaceMember member, User user, Long requesterId) {
        return new WorkspaceMemberResponse(
                user.getUserId(),
                user.getNickname(),
                user.getEmail(),
                user.getProfileImageUrl(),
                member.getRole(),
                toUtcOffsetDateTime(member.getJoinedAt()),
                user.getId().equals(requesterId)
        );
    }

    private static OffsetDateTime toUtcOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.UTC);
    }
}