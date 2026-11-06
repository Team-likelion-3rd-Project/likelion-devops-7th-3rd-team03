package redirect_service.clicklog.event;

/** 유효한 링크의 리다이렉트가 결정된 직후 발행하는 원본 클릭 이벤트다. */
public record RedirectSucceededEvent(Long linkId, ClickRequestSnapshot request) {
}
