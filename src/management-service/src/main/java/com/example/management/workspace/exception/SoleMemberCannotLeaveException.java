package com.example.management.workspace.exception;

/** 혼자 남은 멤버가 나가면 관리자가 없는 빈 워크스페이스가 남으므로 삭제를 안내한다. */
public class SoleMemberCannotLeaveException extends RuntimeException {
    public SoleMemberCannotLeaveException() {
        super("혼자 남은 워크스페이스는 나갈 수 없습니다. 워크스페이스를 삭제해주세요.");
    }
}