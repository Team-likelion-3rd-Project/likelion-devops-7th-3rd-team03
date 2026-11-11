package com.example.management.workspace.exception;

/** 이미 멤버인 워크스페이스에 초대 코드로 다시 합류할 때 사용한다. */
public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException() {
        super("이미 참여 중인 워크스페이스입니다.");
    }
}