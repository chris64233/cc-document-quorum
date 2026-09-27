package com.chris64233.cc.documentquorum.service.views;

public record DecisionResultView(String eventId,
                                 int versionNo,
                                 String versionStatus,
                                 int policyNo,
                                 boolean replayed) {
}
