package com.example.management.workspace.controller.dto;

import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceRole;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 워크스페이스 응답. workspaceId는 외부 노출용 UUID이고, role은 요청한 사용자의 역할이다 */
public record WorkspaceResponse(
        String workspaceId,
        String name,
        boolean personal,
        WorkspaceRole role,
        OffsetDateTime createdAt
) {
    public static WorkspaceResponse of(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(
                workspace.getWorkspaceId(),
                workspace.getName(),
                workspace.isPersonal(),
                role,
                toUtcOffsetDateTime(workspace.getCreatedAt())
        );
    }

    private static OffsetDateTime toUtcOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.UTC);
    }
}
