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
 * 一次策略修订。创建时冻结基线策略版本号、管理角色与修订门槛；
 * 修订内容（新策略）在 {@link AmendmentRequirement} 中存放。
 * 修订号 (version_id, amendment_no) 唯一，保证创建幂等。
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
    private String amendmentNo;

    @Column(name = "base_policy_version_no", nullable = false, updatable = false)
    private int basePolicyVersionNo;

    @Column(name = "amendment_role", nullable = false, updatable = false)
    private String amendmentRole;

    @Column(name = "amendment_threshold", nullable = false, updatable = false)
    private int amendmentThreshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmendmentStatus status = AmendmentStatus.PENDING;

    @Column(name = "enacted_policy_version_no")
    private Integer enactedPolicyVersionNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyAmendment() {
    }

    public PolicyAmendment(DocumentVersion version, String amendmentNo, int basePolicyVersionNo,
                           String amendmentRole, int amendmentThreshold) {
        this.version = version;
        this.amendmentNo = amendmentNo;
        this.basePolicyVersionNo = basePolicyVersionNo;
        this.amendmentRole = amendmentRole;
        this.amendmentThreshold = amendmentThreshold;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public String getAmendmentNo() {
        return amendmentNo;
    }

    public int getBasePolicyVersionNo() {
        return basePolicyVersionNo;
    }

    public String getAmendmentRole() {
        return amendmentRole;
    }

    public int getAmendmentThreshold() {
        return amendmentThreshold;
    }

    public AmendmentStatus getStatus() {
        return status;
    }

    public void setStatus(AmendmentStatus status) {
        this.status = status;
    }

    public Integer getEnactedPolicyVersionNo() {
        return enactedPolicyVersionNo;
    }

    public void setEnactedPolicyVersionNo(Integer enactedPolicyVersionNo) {
        this.enactedPolicyVersionNo = enactedPolicyVersionNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
