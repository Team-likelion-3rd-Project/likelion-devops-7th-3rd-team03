package com.example.management.batch;

/**
 * UPSERT 배치 처리 중 청크가 연속으로 N회 실패했을 때 던지는 예외.
 * row 몇 개의 문제가 아니라 DB 자체 장애(커넥션 풀 고갈, DB 다운 등)로 판단될 때
 * 배치 전체를 조기 중단시키기 위해 사용한다.
 * CronJob 레벨(AthenaBatchRunner 등)에서 이 예외를 잡아 재실행/알림 여부를 판단한다.
 */
public class BatchCircuitBreakerException extends RuntimeException {

    public BatchCircuitBreakerException(String message) {
        super(message);
    }

    public BatchCircuitBreakerException(String message, Throwable cause) {
        super(message, cause);
    }
}