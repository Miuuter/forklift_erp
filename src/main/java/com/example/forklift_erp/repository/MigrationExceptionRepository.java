package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.MigrationException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MigrationExceptionRepository extends JpaRepository<MigrationException, Long> {
    boolean existsByExceptionTypeAndSourceTypeAndSourceIdAndStatus(
            String exceptionType,
            String sourceType,
            Long sourceId,
            String status
    );

    List<MigrationException> findByStatusOrderByCreatedAtDescIdDesc(String status);

    long countByStatus(String status);
}
