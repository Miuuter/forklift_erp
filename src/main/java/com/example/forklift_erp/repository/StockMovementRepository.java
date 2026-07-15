package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    Optional<StockMovement> findByMovementNo(String movementNo);
    Optional<StockMovement> findByIdempotencyKey(String idempotencyKey);

    List<StockMovement> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    List<StockMovement> findByIdIn(Collection<Long> ids);
}
