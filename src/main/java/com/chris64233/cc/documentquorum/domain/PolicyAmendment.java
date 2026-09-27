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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 一次策略修订。创建时冻结所属文件版本与基准策略版本号，
 * 并携带一份完整的替换策略（PENDING 策略版本）。
 * amendmentNo 由调用方给定，在文件版本内唯一，作为幂等键。
 */
@Entity
@Table(name = "policy_amendment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "amendment_no"}))
public class PolicyAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private DocumentVersion version;

    @Column(name = "amendment_no", nullable = false, updatable = false)
    private int amendmentNo;

    @Column(name = "base_policy_no", nullable = false, updatable = false)
    private int basePolicyNo;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false, unique = true, updatable = false)
    private PolicyVersion policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmendmentStatus status = AmendmentStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "effective_at")
    private Instant effectiveAt;

    protected PolicyAmendment() {
    }

    public PolicyAmendment(DocumentVersion version, int amendmentNo, int basePolicyNo,
                           PolicyVersion policyVersion) {
        this.version = version;
        this.amendmentNo = amendmentNo;
        this.basePolicyNo = basePolicyNo;
        this.policyVersion = policyVersion;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public int getAmendmentNo() {
        return amendmentNo;
    }

    public int getBasePolicyNo() {
        return basePolicyNo;
    }

    public PolicyVersion getPolicyVersion() {
        return policyVersion;
    }

    public AmendmentStatus getStatus() {
        return status;
    }

    public void markEffective() {
        this.status = AmendmentStatus.EFFECTIVE;
        this.effectiveAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
