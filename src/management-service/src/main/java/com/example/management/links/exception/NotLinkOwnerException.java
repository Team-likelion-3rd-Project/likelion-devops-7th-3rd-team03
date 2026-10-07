package com.example.management.links.exception;

/** 링크 소유자가 아닌 사용자의 수정·삭제 요청을 나타낸다. */
public class NotLinkOwnerException extends RuntimeException {

    private final String linkId;

    public NotLinkOwnerException(String linkId) {
        this(linkId, "본인이 생성한 링크만 수정할 수 있습니다.");
    }

    public NotLinkOwnerException(String linkId, String message) {
        super(message);
        this.linkId = linkId;
    }

    public String getLinkId() {
        return linkId;
    }
}
