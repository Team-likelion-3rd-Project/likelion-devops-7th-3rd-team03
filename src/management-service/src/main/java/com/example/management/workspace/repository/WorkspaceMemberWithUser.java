package com.example.management.workspace.repository;

import com.example.management.auth.domain.User;
import com.example.management.workspace.domain.WorkspaceMember;

/** 멤버 목록 조회 결과. 멤버 정보와 그 사용자 */
public record WorkspaceMemberWithUser(WorkspaceMember member, User user) {
}