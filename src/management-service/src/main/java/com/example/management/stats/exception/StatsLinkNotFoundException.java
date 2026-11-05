package com.example.management.stats.exception;

import java.util.List;

/** 통계 대상 linkIds 중 경로의 팀에 없는 링크가 있을 때. 하나라도 없으면 전체를 거절한다 */
public class StatsLinkNotFoundException extends RuntimeException {

    private final List<String> linkIds;

    public StatsLinkNotFoundException(List<String> linkIds) {
        super("링크를 찾을 수 없습니다.");
        this.linkIds = List.copyOf(linkIds);
    }

    public List<String> getLinkIds() {
        return linkIds;
    }
}