package com.example.management.workspace.domain;

/** 워크스페이스 안에서의 역할. 권한 차이는 기획 문서의 역할표를 따른다 */
public enum WorkspaceRole {
    /** 링크·프로젝트 관리 + 초대 코드, 멤버 역할 변경·내보내기, 팀 설정·삭제 */
    ADMIN,
    /** 링크·프로젝트 관리 */
    MEMBER
}
