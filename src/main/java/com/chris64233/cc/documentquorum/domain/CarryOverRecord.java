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

import java.time.Instant;

/**
 * 修订生效时对彼时已有决定逐条评估的沿用明细。
 * 记录一经写入不再变化，作为稳定审计依据（即使之后签署人角色再变）。
 */
@Entity
@Table(name = "carry_over_record")
public class CarryOverRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amendment_id", nullable = false, updatable = false)
    private PolicyAmendment amendment;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "signer_external_id", nullable = false, updatable = false)
    private String signerExternalId;

    @Column(nullable = false, updatable = false)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DecisionType decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CarryOverOutcome outcome;

    /** 失效原因，仅 VOIDED 时有值：ROLE_NOT_IN_NEW_POLICY / SIGNER_LOST_ROLE / REJECT_NOT_CARRIED / ALREADY_VOIDED */
    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CarryOverRecord() {
    }

    public CarryOverRecord(PolicyAmendment amendment, String eventId, String signerExternalId,
                           String role, DecisionType decision, CarryOverOutcome outcome, String reason) {
        this.amendment = amendment;
        this.eventId = eventId;
        this.signerExternalId = signerExternalId;
        this.role = role;
        this.decision = decision;
        this.outcome = outcome;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public PolicyAmendment getAmendment() {
        return amendment;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSignerExternalId() {
        return signerExternalId;
    }

    public String getRole() {
        return role;
    }

    public DecisionType getDecision() {
        return decision;
    }

    public CarryOverOutcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
