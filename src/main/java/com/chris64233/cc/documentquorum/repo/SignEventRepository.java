package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.Decision;
import com.chris64233.cc.documentquorum.domain.SignEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SignEventRepository extends JpaRepository<SignEvent, Long> {

    Optional<SignEvent> findByEventNo(String eventNo);

    boolean existsByVersionIdAndSignerId(Long versionId, Long signerId);

    List<SignEvent> findByVersionIdOrderByIdAsc(Long versionId);

    @Query("select e.role, count(e) from SignEvent e " +
            "where e.version.id = :versionId and e.decision = :decision group by e.role")
    List<Object[]> countByVersionAndDecisionGroupByRole(@Param("versionId") Long versionId,
                                                        @Param("decision") Decision decision);
}
