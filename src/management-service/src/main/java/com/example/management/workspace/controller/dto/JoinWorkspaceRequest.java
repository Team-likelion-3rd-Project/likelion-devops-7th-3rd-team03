package com.example.management.workspace.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 초대 코드로 합류 요청. 앞뒤 공백과 대소문자는 서비스에서 맞춘다 */
public record JoinWorkspaceRequest(
        @NotBlank @Size(max = 16) String inviteCode
) {
}