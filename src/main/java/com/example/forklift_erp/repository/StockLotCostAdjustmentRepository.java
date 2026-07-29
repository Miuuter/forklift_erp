package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLotCostAdjustment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockLotCostAdjustmentRepository extends JpaRepository<StockLotCostAdjustment, Long> {
    Optional<StockLotCostAdjustment> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockLotCostAdjustment a where a.idempotencyKey = :idempotencyKey")
    Optional<StockLotCostAdjustment> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);

    List<StockLotCostAdjustment> findByStockLotIdOrderByIdAsc(Long stockLotId);
}
