package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.StockLotConsumption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StockLotConsumptionRepository extends JpaRepository<StockLotConsumption, Long> {
    Optional<StockLotConsumption> findByIdempotencyKey(String idempotencyKey);

    boolean existsByWarehouseId(Long warehouseId);

    List<StockLotConsumption> findByIdempotencyKeyStartingWithOrderByIdAsc(String idempotencyKeyPrefix);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StockLotConsumption c "
            + "where c.idempotencyKey like concat(:prefix, '%') order by c.id asc")
    List<StockLotConsumption> findByIdempotencyKeyPrefixForUpdate(@Param("prefix") String prefix);

    List<StockLotConsumption> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, Long sourceId);

    List<StockLotConsumption> findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
            String sourceType,
            Long sourceId,
            Long sourceLineId
    );

    List<StockLotConsumption> findByReversalOfConsumptionIdIn(List<Long> reversalOfConsumptionIds);

}
