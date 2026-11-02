package com.example.management.workspace.exception;

import com.example.management.common.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 워크스페이스 도메인 예외를 공통 오류 계약으로 변환한다.
 * 권한 검사(WorkspaceAccessChecker)는 링크·통계 API에서도 쓰므로 basePackages를 제한하지 않는다.
 */
@RestControllerAdvice
public class WorkspaceExceptionHandler {

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkspaceAccessDenied(WorkspaceAccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED", e.getMessage());
    }

    @ExceptionHandler(PersonalWorkspaceUndeletableException.class)
    public ResponseEntity<ApiErrorResponse> handlePersonalWorkspaceUndeletable(PersonalWorkspaceUndeletableException e) {
        return error(HttpStatus.CONFLICT, "PERSONAL_WORKSPACE_UNDELETABLE", e.getMessage());
    }

    @ExceptionHandler(WorkspaceHasMembersException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkspaceHasMembers(WorkspaceHasMembersException e) {
        return error(HttpStatus.CONFLICT, "WORKSPACE_HAS_MEMBERS", e.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status.value(), type, message));
    }
}
