package com.chris64233.cc.documentquorum.domain;

public enum PolicyVersionStatus {
    /** 由修订创建、尚未生效的策略版本 */
    PENDING,
    /** 当前用于计算签署进度的策略版本 */
    ACTIVE,
    /** 被更新的策略版本取代 */
    SUPERSEDED
}
