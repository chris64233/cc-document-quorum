package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "effective_version")
public class EffectiveVersion {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, unique = true)
    private DocumentVersion version;

    protected EffectiveVersion() {
    }

    public EffectiveVersion(Long documentId, DocumentVersion version) {
        this.documentId = documentId;
        this.version = version;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public void setVersion(DocumentVersion version) {
        this.version = version;
    }
}
