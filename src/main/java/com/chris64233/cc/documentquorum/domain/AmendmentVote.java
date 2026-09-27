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
 * 管理角色对一次修订的投票。事件号全局唯一保证幂等；
 * 同一签署人对同一修订只能投一票。
 */
@Entity
@Table(name = "amendment_vote", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"amendment_id", "signer_id"}),
        @UniqueConstraint(columnNames = "event_id")
})
public class AmendmentVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amendment_id", nullable = false, updatable = false)
    private PolicyAmendment amendment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signer_id", nullable = false, updatable = false)
    private Signer signer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DecisionType decision;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AmendmentVote() {
    }

    public AmendmentVote(PolicyAmendment amendment, Signer signer, DecisionType decision, String eventId) {
        this.amendment = amendment;
        this.signer = signer;
        this.decision = decision;
        this.eventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public PolicyAmendment getAmendment() {
        return amendment;
    }

    public Signer getSigner() {
        return signer;
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
