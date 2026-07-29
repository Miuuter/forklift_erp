package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockMovement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    Optional<StockMovement> findByMovementNo(String movementNo);
    Optional<StockMovement> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from StockMovement m where m.idempotencyKey = :idempotencyKey")
    Optional<StockMovement> findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    List<StockMovement> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    List<StockMovement> findByIdIn(Collection<Long> ids);
}
