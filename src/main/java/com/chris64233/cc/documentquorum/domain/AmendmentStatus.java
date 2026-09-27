package com.chris64233.cc.documentquorum.domain;

public enum AmendmentStatus {
    /** 等待管理角色投票达到修订门槛 */
    PENDING,
    /** 修订门槛达成，新策略版本已生效 */
    EFFECTIVE
}
