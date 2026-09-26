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

@Entity
@Table(name = "policy_requirement",
        uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "role"}))
public class PolicyRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private DocumentVersion version;

    @Column(nullable = false, updatable = false)
    private String role;

    @Column(name = "required_approvals", nullable = false, updatable = false)
    private int requiredApprovals;

    @Column(name = "veto_power", nullable = false, updatable = false)
    private boolean vetoPower;

    protected PolicyRequirement() {
    }

    public PolicyRequirement(DocumentVersion version, String role, int requiredApprovals, boolean vetoPower) {
        this.version = version;
        this.role = role;
        this.requiredApprovals = requiredApprovals;
        this.vetoPower = vetoPower;
    }

    public Long getId() {
        return id;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    public String getRole() {
        return role;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public boolean isVetoPower() {
        return vetoPower;
    }
}
