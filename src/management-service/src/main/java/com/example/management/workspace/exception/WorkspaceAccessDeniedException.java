package com.example.management.workspace.exception;

/** 멤버이지만 관리자 권한이 필요한 작업을 요청했을 때 사용한다. */
public class WorkspaceAccessDeniedException extends RuntimeException {

    public WorkspaceAccessDeniedException() {
        super("워크스페이스 관리자만 할 수 있습니다.");
    }
}
