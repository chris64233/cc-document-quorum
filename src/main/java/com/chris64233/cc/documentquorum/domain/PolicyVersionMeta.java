package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 一个文件版本的某一代签署策略的元信息。策略内容本身在
 * {@link PolicyRequirement} 中按 (version_id, policy_version_no, role) 存放。
 * 管理角色与修订门槛随策略版本冻结，修订生效时原样继承到下一代策略。
 */
@Entity
@Table(name = "policy_version_meta",
        uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "policy_version_no"}))
public class PolicyVersionMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private DocumentVersion version;

    @Column(name = "policy_version_no", nullable = false, updatable = false)
    private int policyVersionNo;

    @Column(name = "amendment_role", updatable = false)
    private String amendmentRole;

    @Column(name = "amendment_threshold", updatable = false)
    private Integer amendmentThreshold;

    @Column(name = "source_amendment_no", updatable = false)
    private String sourceAmendmentNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyVersionMeta() {
    }

    public PolicyVersionMeta(DocumentVersion version, int policyVersionNo,
                             String amendmentRole, Integer amendmentThreshold,
                             String sourceAmendmentNo) {
        this.version = version;
        this.policyVersionNo = policyVersionNo;
        this.amendmentRole = amendmentRole;
        this.amendmentThreshold = amendmentThreshold;
        this.sourceAmendmentNo = sourceAmendmentNo;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public int getPolicyVersionNo() {
        return policyVersionNo;
    }

    public String getAmendmentRole() {
        return amendmentRole;
    }

    public Integer getAmendmentThreshold() {
        return amendmentThreshold;
    }

    public String getSourceAmendmentNo() {
        return sourceAmendmentNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
