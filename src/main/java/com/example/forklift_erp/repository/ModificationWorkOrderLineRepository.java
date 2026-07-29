package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModificationWorkOrderLineRepository extends JpaRepository<ModificationWorkOrderLine, Long> {
    boolean existsByNewPartId(Long newPartId);

    boolean existsByConfigItemId(Long configItemId);

    boolean existsByNewConfigValueId(Long newConfigValueId);

    boolean existsByMachineConfigId(Long machineConfigId);

    boolean existsByWarehouseIdOrOldPartWarehouseId(Long warehouseId, Long oldPartWarehouseId);

    List<ModificationWorkOrderLine> findByWorkOrderIdOrderByIdAsc(Long workOrderId);

    List<ModificationWorkOrderLine> findByWorkOrderIdIn(List<Long> workOrderIds);
}
