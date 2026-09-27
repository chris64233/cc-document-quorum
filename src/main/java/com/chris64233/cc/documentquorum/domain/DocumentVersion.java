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

@Entity
@Table(name = "document_version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "version_no"}))
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private ControlledDocument document;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false, updatable = false, length = 4000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionStatus status = VersionStatus.PENDING;

    /** 修订管理角色（创建版本时由原策略指定，固化不可变）；为 null 表示该版本不允许策略修订 */
    @Column(name = "amend_role", updatable = false)
    private String amendRole;

    /** 修订门槛：管理角色中需要多少名不同签署人同意才能让修订生效 */
    @Column(name = "amend_required_approvals", updatable = false)
    private Integer amendRequiredApprovals;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "effective_at")
    private Instant effectiveAt;

    protected DocumentVersion() {
    }

    public DocumentVersion(ControlledDocument document, int versionNo, String content) {
        this(document, versionNo, content, null, null);
    }

    public DocumentVersion(ControlledDocument document, int versionNo, String content,
                           String amendRole, Integer amendRequiredApprovals) {
        this.document = document;
        this.versionNo = versionNo;
        this.content = content;
        this.amendRole = amendRole;
        this.amendRequiredApprovals = amendRequiredApprovals;
    }

    public Long getId() {
        return id;
    }

    public ControlledDocument getDocument() {
        return document;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getContent() {
        return content;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public void setStatus(VersionStatus status) {
        this.status = status;
    }

    public String getAmendRole() {
        return amendRole;
    }

    public Integer getAmendRequiredApprovals() {
        return amendRequiredApprovals;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void markEffective(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
