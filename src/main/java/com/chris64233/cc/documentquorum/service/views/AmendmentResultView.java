package com.chris64233.cc.documentquorum.service.views;

public record AmendmentResultView(String eventId,
                                  int amendmentNo,
                                  String status,
                                  boolean replayed,
                                  boolean effectiveNow,
                                  Integer effectivePolicyNo,
                                  Integer carriedOverCount,
                                  Integer voidedCount) {
}
