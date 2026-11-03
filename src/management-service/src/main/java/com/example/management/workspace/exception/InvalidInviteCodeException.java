package com.example.management.workspace.exception;

/** 초대 코드가 없거나, 재발급으로 무효가 됐거나, 삭제된 워크스페이스의 코드일 때 사용한다. */
public class InvalidInviteCodeException extends RuntimeException {
    public InvalidInviteCodeException() {
        super("유효하지 않은 초대 코드입니다.");
    }
}