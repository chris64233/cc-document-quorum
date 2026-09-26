package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "signers")
public class Signer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String externalId;

    @Column(nullable = false, length = 256)
    private String displayName;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "signer_roles", joinColumns = @JoinColumn(name = "signer_id"))
    @Column(name = "role_name", nullable = false, length = 64)
    private Set<String> roles = new LinkedHashSet<>();

    protected Signer() {
    }

    public Signer(String externalId, String displayName, Set<String> roles) {
        this.externalId = externalId;
        this.displayName = displayName;
        this.roles = new LinkedHashSet<>(roles);
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
