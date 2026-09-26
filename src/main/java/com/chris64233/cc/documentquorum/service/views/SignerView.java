package com.chris64233.cc.documentquorum.service.views;

import java.util.Set;

public record SignerView(Long id, String externalId, String name, Set<String> roles) {
}
