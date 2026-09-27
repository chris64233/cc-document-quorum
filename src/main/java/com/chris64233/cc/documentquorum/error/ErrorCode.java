package com.chris64233.cc.documentquorum.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    SIGNER_NOT_FOUND(HttpStatus.NOT_FOUND),
    AMENDMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    POLICY_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ELIGIBILITY(HttpStatus.FORBIDDEN),
    DUPLICATE_DECISION(HttpStatus.CONFLICT),
    EVENT_CONFLICT(HttpStatus.CONFLICT),
    AMENDMENT_CONFLICT(HttpStatus.CONFLICT),
    TERMINAL_STATE(HttpStatus.CONFLICT),
    DUPLICATE_DOCUMENT(HttpStatus.CONFLICT),
    DUPLICATE_SIGNER(HttpStatus.CONFLICT),
    VALIDATION(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
