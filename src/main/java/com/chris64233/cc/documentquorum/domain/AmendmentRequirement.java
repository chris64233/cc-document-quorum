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

/**
 * 修订提议的新策略内容（按角色）。修订生效时整体拷贝为下一代
 * {@link PolicyRequirement}。
 */
@Entity
@Table(name = "amendment_requirement",
        uniqueConstraints = @UniqueConstraint(columnNames = {"amendment_id", "role"}))
public class AmendmentRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amendment_id", nullable = false, updatable = false)
    private PolicyAmendment amendment;

    @Column(nullable = false, updatable = false)
    private String role;

    @Column(name = "required_approvals", nullable = false, updatable = false)
    private int requiredApprovals;

    @Column(name = "veto_power", nullable = false, updatable = false)
    private boolean vetoPower;

    protected AmendmentRequirement() {
    }

    public AmendmentRequirement(PolicyAmendment amendment, String role,
                                int requiredApprovals, boolean vetoPower) {
        this.amendment = amendment;
        this.role = role;
        this.requiredApprovals = requiredApprovals;
        this.vetoPower = vetoPower;
    }

    public Long getId() {
        return id;
    }

    public PolicyAmendment getAmendment() {
        return amendment;
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
