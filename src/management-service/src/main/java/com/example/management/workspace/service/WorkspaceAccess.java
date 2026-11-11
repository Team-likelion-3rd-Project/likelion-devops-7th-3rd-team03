package com.example.management.workspace.service;

import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceMember;

/** 권한 검사를 통과한 워크스페이스와 요청한 사용자의 멤버 정보 */
public record WorkspaceAccess(Workspace workspace, WorkspaceMember member) {
}
