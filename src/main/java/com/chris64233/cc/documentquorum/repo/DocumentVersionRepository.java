package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DocumentVersion v where v.id = :id")
    Optional<DocumentVersion> findByIdForUpdate(@Param("id") Long id);

    List<DocumentVersion> findByDocumentIdOrderByVersionNumberAsc(Long documentId);

    Optional<DocumentVersion> findByDocumentIdAndStatus(Long documentId, VersionStatus status);

    @Query("select coalesce(max(v.versionNumber), 0) from DocumentVersion v where v.document.id = :documentId")
    int findMaxVersionNumber(@Param("documentId") Long documentId);
}
