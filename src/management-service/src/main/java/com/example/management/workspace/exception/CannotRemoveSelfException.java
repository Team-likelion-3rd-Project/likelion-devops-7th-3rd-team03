package com.example.management.workspace.exception;

/** 관리자가 자기 자신을 내보내는 요청. 나가기 API를 쓰게 한다. */
public class CannotRemoveSelfException extends RuntimeException {
    public CannotRemoveSelfException() {
        super("자기 자신은 내보낼 수 없습니다. 나가기를 이용해주세요.");
    }
}