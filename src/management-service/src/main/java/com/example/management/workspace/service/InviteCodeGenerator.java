package com.example.management.workspace.service;

import com.example.management.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 초대 코드 생성. 대문자·숫자 12자리이고, 손으로 옮겨 적을 때 헷갈리는 0/O, 1/I는 뺐다.
 * 32가지 문자 12자리로 약 60비트라 무작위 대입으로 맞히기 어려워 실패 횟수 제한은 두지 않는다.
 * 중복은 slug와 같은 방식으로 확인 후 다시 뽑고, 최종 보호는 DB의 UNIQUE 제약이다.
 */
@Component
@RequiredArgsConstructor
public class InviteCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaceRepository;

    public String generateAvailable() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = generate();
            if (!workspaceRepository.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("초대 코드 생성에 실패했습니다. 다시 시도해주세요.");
    }

    /** 사용자가 입력한 코드를 저장 형식으로 맞춘다(앞뒤 공백 제거, 대문자) */
    public static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * 발급할 수 있는 형식(허용 문자 12자리)인지 확인한다.
     * invite_code 컬럼이 ascii라서 한글 등 비ASCII 문자를 그대로 비교하면 MySQL이 콜레이션 충돌(500)을 낸다.
     */
    public static boolean isWellFormed(String normalizedCode) {
        return normalizedCode.length() == CODE_LENGTH
                && normalizedCode.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }

    private String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}