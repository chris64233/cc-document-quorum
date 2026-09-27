package com.chris64233.cc.documentquorum.service.views;

public record AmendmentVoteResultView(String eventId,
                                      String amendmentNo,
                                      String amendmentStatus,
                                      String versionStatus,
                                      boolean replayed) {
}
