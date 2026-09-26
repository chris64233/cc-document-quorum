package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByDocumentDocCodeAndVersionNo(String docCode, int versionNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DocumentVersion v where v.document.docCode = :docCode and v.versionNo = :versionNo")
    Optional<DocumentVersion> findByDocCodeAndVersionNoForUpdate(@Param("docCode") String docCode,
                                                                 @Param("versionNo") int versionNo);

    List<DocumentVersion> findByDocumentDocCodeOrderByVersionNoAsc(String docCode);

    @Query("select max(v.versionNo) from DocumentVersion v where v.document.id = :documentId")
    Optional<Integer> findMaxVersionNoByDocumentId(@Param("documentId") Long documentId);
}
