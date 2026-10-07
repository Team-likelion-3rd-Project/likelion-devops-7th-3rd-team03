package com.example.management.links.controller.dto;

/**
 * DB 컬럼으로 저장하지 않는 응답 전용 상태.
 * is_visible=false 링크는 사용자 API에서 노출하지 않으므로 여기에 DELETED를 두지 않는다.
 */
public enum LinkStatus {
    ACTIVE,
    EXPIRED
}
