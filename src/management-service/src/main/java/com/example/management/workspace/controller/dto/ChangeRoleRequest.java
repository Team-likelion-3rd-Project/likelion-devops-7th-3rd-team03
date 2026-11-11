package com.example.management.workspace.controller.dto;

import com.example.management.workspace.domain.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/** 멤버 역할 변경 요청. ADMIN 또는 MEMBER */
public record ChangeRoleRequest(
        @NotNull WorkspaceRole role
) {
}