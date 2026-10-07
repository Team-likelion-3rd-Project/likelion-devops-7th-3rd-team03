package com.example.management.links.exception;

/** visible 링크 수가 사용자별 허용 개수에 도달했을 때 사용한다. */
public class LinkLimitExceededException extends RuntimeException {

    public LinkLimitExceededException() {
        super("생성 가능한 링크 개수를 초과했습니다.");
    }
}
