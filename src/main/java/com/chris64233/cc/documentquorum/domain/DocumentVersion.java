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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DocumentVersion() {
    }

    public DocumentVersion(ControlledDocument document, int versionNo, String content) {
        this.document = document;
        this.versionNo = versionNo;
        this.content = content;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
