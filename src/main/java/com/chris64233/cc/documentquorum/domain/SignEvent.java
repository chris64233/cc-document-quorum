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
@Table(name = "sign_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sign_event_no", columnNames = {"event_no"}),
        @UniqueConstraint(name = "uk_sign_event_version_signer", columnNames = {"version_id", "signer_id"})
})
public class SignEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, length = 128)
    private String eventNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signer_id", nullable = false)
    private Signer signer;

    @Column(name = "role_name", nullable = false, length = 64)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Decision decision;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    protected SignEvent() {
    }

    public SignEvent(String eventNo, DocumentVersion version, Signer signer, String role,
                     Decision decision, String payloadHash) {
        this.eventNo = eventNo;
        this.version = version;
        this.signer = signer;
        this.role = role;
        this.decision = decision;
        this.payloadHash = payloadHash;
    }

    public Long getId() {
        return id;
    }

    public String getEventNo() {
        return eventNo;
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

    public Decision getDecision() {
        return decision;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
