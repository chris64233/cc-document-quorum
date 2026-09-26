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
import jakarta.persistence.UniqueConstraint;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "signer", uniqueConstraints = @UniqueConstraint(columnNames = "external_id"))
public class Signer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(nullable = false)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signer_role", joinColumns = @JoinColumn(name = "signer_id"))
    @Column(name = "role", nullable = false)
    private Set<String> roles = new LinkedHashSet<>();

    protected Signer() {
    }

    public Signer(String externalId, String name, Set<String> roles) {
        this.externalId = externalId;
        this.name = name;
        this.roles = new LinkedHashSet<>(roles);
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public Set<String> getRoles() {
        return roles;
    }
}
