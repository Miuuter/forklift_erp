package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLotConsumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockLotConsumptionRepository extends JpaRepository<StockLotConsumption, Long> {
    Optional<StockLotConsumption> findByIdempotencyKey(String idempotencyKey);

    boolean existsByWarehouseId(Long warehouseId);

    List<StockLotConsumption> findByIdempotencyKeyStartingWithOrderByIdAsc(String idempotencyKeyPrefix);

    List<StockLotConsumption> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    List<StockLotConsumption> findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
            String sourceType,
            Long sourceId,
            Long sourceLineId
    );

    List<StockLotConsumption> findByReversalOfConsumptionIdIn(List<Long> reversalOfConsumptionIds);
}
