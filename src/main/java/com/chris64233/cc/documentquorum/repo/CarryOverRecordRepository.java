package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.CarryOverRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarryOverRecordRepository extends JpaRepository<CarryOverRecord, Long> {

    List<CarryOverRecord> findByAmendmentIdOrderByIdAsc(Long amendmentId);
}
