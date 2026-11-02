package com.example.management.workspace.repository;

import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceRole;

/** 내 워크스페이스 목록 조회 결과. 워크스페이스와 그 안에서의 내 역할 */
public record WorkspaceWithRole(Workspace workspace, WorkspaceRole role) {
}
