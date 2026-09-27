package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 文件版本下的一份策略版本。初始策略为 1 号且直接 ACTIVE；
 * 每次策略修订冻结一份新的 PENDING 策略版本，修订生效后转为 ACTIVE，
 * 原先的 ACTIVE 版本转为 SUPERSEDED。任一时刻同一文件版本至多一个 ACTIVE。
 */
@Entity
@Table(name = "policy_version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "policy_no"}))
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private DocumentVersion version;

    @Column(name = "policy_no", nullable = false, updatable = false)
    private int policyNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyVersionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyVersion() {
    }

    public PolicyVersion(DocumentVersion version, int policyNo, PolicyVersionStatus status) {
        this.version = version;
        this.policyNo = policyNo;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public int getPolicyNo() {
        return policyNo;
    }

    public PolicyVersionStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyVersionStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
