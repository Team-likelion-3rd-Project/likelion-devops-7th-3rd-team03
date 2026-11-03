package com.example.management.workspace.exception;

/** 개인 워크스페이스의 주인은 강등하거나 내보낼 수 없다(주인은 나갈 수 없다는 규칙과 맞춘다). */
public class PersonalWorkspaceOwnerException extends RuntimeException {
    public PersonalWorkspaceOwnerException() {
        super("개인 워크스페이스의 주인은 강등하거나 내보낼 수 없습니다.");
    }
}