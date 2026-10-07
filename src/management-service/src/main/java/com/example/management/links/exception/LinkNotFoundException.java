package com.example.management.links.exception;

/** 존재하지 않거나 soft-delete된 링크에 접근할 때 사용한다. */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException() {
        super("링크를 찾을 수 없습니다.");
    }
}
