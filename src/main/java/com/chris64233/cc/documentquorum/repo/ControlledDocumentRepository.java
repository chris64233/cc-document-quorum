package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ControlledDocumentRepository extends JpaRepository<ControlledDocument, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ControlledDocument d where d.id = :id")
    Optional<ControlledDocument> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCode(String code);
}
