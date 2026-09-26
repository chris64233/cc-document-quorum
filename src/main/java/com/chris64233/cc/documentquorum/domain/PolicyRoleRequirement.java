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
@Table(name = "policy_role_requirements", uniqueConstraints =
        @UniqueConstraint(name = "uk_policy_version_role", columnNames = {"version_id", "role_name"}))
public class PolicyRoleRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion version;

    @Column(name = "role_name", nullable = false, length = 64)
    private String role;

    @Column(nullable = false)
    private int requiredApprovals;

    @Column(nullable = false)
    private boolean veto;

    protected PolicyRoleRequirement() {
    }

    public PolicyRoleRequirement(DocumentVersion version, String role, int requiredApprovals, boolean veto) {
        this.version = version;
        this.role = role;
        this.requiredApprovals = requiredApprovals;
        this.veto = veto;
    }

    public Long getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public boolean isVeto() {
        return veto;
    }
}
