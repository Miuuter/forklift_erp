package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockOperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockOperationLogRepository extends JpaRepository<StockOperationLog, Long> {

    List<StockOperationLog> findAllByOrderByCreatedAtDesc();

    List<StockOperationLog> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            LocalDateTime start,
            LocalDateTime end
    );

    List<StockOperationLog> findByResourceTypeAndResourceIdOrderByIdAsc(String resourceType, Long resourceId);

    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);
}
