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
@Table(name = "sign_decision", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"version_id", "signer_id"}),
        @UniqueConstraint(columnNames = "event_id")
})
public class SignDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private DocumentVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signer_id", nullable = false, updatable = false)
    private Signer signer;

    @Column(nullable = false, updatable = false)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DecisionType decision;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SignDecision() {
    }

    public SignDecision(DocumentVersion version, Signer signer, String role, DecisionType decision, String eventId) {
        this.version = version;
        this.signer = signer;
        this.role = role;
        this.decision = decision;
        this.eventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public Signer getSigner() {
        return signer;
    }

    public String getRole() {
        return role;
    }

    public DecisionType getDecision() {
        return decision;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
