package com.chris64233.cc.documentquorum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "controlled_document", uniqueConstraints = @UniqueConstraint(columnNames = "doc_code"))
public class ControlledDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_code", nullable = false, updatable = false)
    private String docCode;

    @Column(nullable = false)
    private String title;

    protected ControlledDocument() {
    }

    public ControlledDocument(String docCode, String title) {
        this.docCode = docCode;
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public String getDocCode() {
        return docCode;
    }

    public String getTitle() {
        return title;
    }
}
