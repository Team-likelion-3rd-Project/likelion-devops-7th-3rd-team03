package com.example.management.workspace.exception;

/** 개인 워크스페이스는 삭제할 수 없다. */
public class PersonalWorkspaceUndeletableException extends RuntimeException {

    public PersonalWorkspaceUndeletableException() {
        super("개인 워크스페이스는 삭제할 수 없습니다.");
    }
}
