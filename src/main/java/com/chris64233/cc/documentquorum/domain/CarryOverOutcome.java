package com.chris64233.cc.documentquorum.domain;

public enum CarryOverOutcome {
    /** 决定在新策略下仍然有效，计入进度 */
    CARRIED_OVER,
    /** 决定在新策略下失效，保留审计但不计入进度 */
    VOIDED
}
