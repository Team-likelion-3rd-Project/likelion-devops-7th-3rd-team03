package com.example.management.workspace.exception;

/**
 * 워크스페이스가 없거나, 삭제됐거나, 요청한 사용자가 멤버가 아닐 때 사용한다.
 * 멤버가 아닌 사용자에게 다른 워크스페이스의 존재 여부를 드러내지 않으려고 세 경우를 구분하지 않는다.
 */
public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException() {
        super("워크스페이스를 찾을 수 없습니다.");
    }
}
