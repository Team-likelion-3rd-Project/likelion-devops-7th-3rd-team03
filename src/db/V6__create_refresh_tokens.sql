-- =====================================================================
-- V6__create_refresh_tokens.sql
-- refresh_tokens 테이블 신규 추가
--
-- JWT 리프레시 토큰을 저장한다. user_id는 users.id(내부 식별자, BIGINT)를 가리킨다.
-- token_hash는 원본 토큰 값이 아니라 해시만 저장한다 — DB가 노출되어도 토큰 자체를
-- 복원할 수 없다.
--
-- 실행 계정: shortlink_migrator
-- 선행: V1__init_schema.sql (users 테이블)
-- =====================================================================

CREATE TABLE refresh_tokens (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '내부 식별자',
    user_id    BIGINT       NOT NULL COMMENT '토큰 소유자 사용자 ID (users.id, 내부 FK)',
    token_hash VARCHAR(255) NOT NULL COMMENT '리프레시 토큰 해시값 (원본 토큰은 저장하지 않음)',
    expires_at DATETIME     NOT NULL COMMENT '토큰 만료 일시',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '토큰 발급 일시',
    FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
) ENGINE=InnoDB COMMENT '리프레시 토큰 저장 테이블';

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
