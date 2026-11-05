package com.example.management.workspace.exception;

/** 워크스페이스마다 관리자가 최소 1명이어야 한다. 마지막 관리자의 강등·나가기를 막는다. */
public class LastAdminException extends RuntimeException {
    public LastAdminException () {
        super("마지막 관리자는 강등하거나 나갈 수 없습니다. 다른 멤버를 관리자로 지정한 뒤 다시 시도해주세요.");
    }
}