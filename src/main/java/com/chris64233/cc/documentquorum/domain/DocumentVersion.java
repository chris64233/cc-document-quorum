package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "document_versions", uniqueConstraints =
        @UniqueConstraint(name = "uk_version_document_number", columnNames = {"document_id", "version_number"}))
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private ControlledDocument document;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 8192)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionStatus status = VersionStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PolicyRoleRequirement> policyRequirements = new ArrayList<>();

    protected DocumentVersion() {
    }

    public DocumentVersion(ControlledDocument document, int versionNumber, String content) {
        this.document = document;
        this.versionNumber = versionNumber;
        this.content = content;
    }

    public void addPolicyRequirement(PolicyRoleRequirement requirement) {
        policyRequirements.add(requirement);
    }

    public Long getId() {
        return id;
    }

    public ControlledDocument getDocument() {
        return document;
    }

    public int getVersionNumber() {
        return versionNumber;
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

    public List<PolicyRoleRequirement> getPolicyRequirements() {
        return Collections.unmodifiableList(policyRequirements);
    }
}
