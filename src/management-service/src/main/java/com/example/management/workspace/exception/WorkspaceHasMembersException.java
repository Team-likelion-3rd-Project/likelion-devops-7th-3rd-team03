package com.example.management.workspace.exception;

/** 다른 멤버가 남아 있는 워크스페이스는 (유일한 관리자라도) 삭제할 수 없다. */
public class WorkspaceHasMembersException extends RuntimeException {

    public WorkspaceHasMembersException() {
        super("다른 멤버가 남아 있는 워크스페이스는 삭제할 수 없습니다. 멤버를 모두 내보낸 뒤 삭제해주세요.");
    }
}
