package com.example.management.workspace.exception;

/** 대상 사용자가 이 워크스페이스의 멤버가 아닐 때 사용한다. */
public class WorkspaceMemberNotFoundException extends RuntimeException {
    public WorkspaceMemberNotFoundException	() {
        super("워크스페이스 멤버를 찾을 수 없습니다.");
    }
}