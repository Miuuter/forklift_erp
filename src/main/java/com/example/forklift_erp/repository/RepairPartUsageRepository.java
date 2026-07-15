package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.RepairPartUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepairPartUsageRepository extends JpaRepository<RepairPartUsage, Long> {
    boolean existsByPartId(Long partId);

    boolean existsByWarehouseId(Long warehouseId);

    List<RepairPartUsage> findByRepairIdOrderByIdAsc(Long repairId);
}
