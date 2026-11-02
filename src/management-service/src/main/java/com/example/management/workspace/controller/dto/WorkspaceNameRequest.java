package com.example.management.workspace.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 워크스페이스 생성·이름 변경 요청. 앞뒤 공백은 서비스에서 제거해 저장한다 */
public record WorkspaceNameRequest(
        @NotBlank @Size(max = 50) String name
) {
}
