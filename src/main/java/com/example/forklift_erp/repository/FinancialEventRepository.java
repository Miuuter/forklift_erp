package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.FinancialEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FinancialEventRepository extends JpaRepository<FinancialEvent, Long> {
    Optional<FinancialEvent> findByIdempotencyKey(String idempotencyKey);

    List<FinancialEvent> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    List<FinancialEvent> findBySourceTypeAndSourceIdAndEventTypeInOrderByIdAsc(
            String sourceType,
            Long sourceId,
            Collection<String> eventTypes
    );

    List<FinancialEvent> findByBusinessDateBetweenOrderByBusinessDateAscIdAsc(LocalDate start, LocalDate end);
}
