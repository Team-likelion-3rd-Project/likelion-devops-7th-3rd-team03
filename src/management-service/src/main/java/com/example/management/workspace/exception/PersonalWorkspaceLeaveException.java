package com.example.management.workspace.exception;

/** 개인 워크스페이스의 주인은 나갈 수 없다. */
public class PersonalWorkspaceLeaveException extends RuntimeException {
    public PersonalWorkspaceLeaveException() {
        super("개인 워크스페이스는 나갈 수 없습니다.");
    }
}