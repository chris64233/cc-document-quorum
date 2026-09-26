package com.chris64233.cc.documentquorum.service;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION(HttpStatus.BAD_REQUEST),
    ELIGIBILITY(HttpStatus.FORBIDDEN),
    DUPLICATE_DECISION(HttpStatus.CONFLICT),
    EVENT_CONFLICT(HttpStatus.CONFLICT),
    TERMINAL_STATE(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
