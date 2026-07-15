package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLotCostAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockLotCostAdjustmentRepository extends JpaRepository<StockLotCostAdjustment, Long> {
    Optional<StockLotCostAdjustment> findByIdempotencyKey(String idempotencyKey);
}
