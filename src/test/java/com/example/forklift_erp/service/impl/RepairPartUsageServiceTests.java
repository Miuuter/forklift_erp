package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.RepairPartUsage;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.MigrationExceptionService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepairPartUsageServiceTests {

    @Test
    void reverseRejectsConsumptionFromWrongResourceIdentity() {
        Fixture fixture = fixture();
        RepairRecord repair = repair();
        RepairPartUsage usage = usage();
        StockLotConsumption wrong = consumption(usage);
        wrong.setResourceType(StockLedgerService.RESOURCE_MACHINE);
        when(fixture.usageRepository.findByRepairIdOrderByIdAsc(repair.getId()))
                .thenReturn(List.of(usage));
        when(fixture.consumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        "REPAIR", repair.getId(), usage.getId()))
                .thenReturn(List.of(wrong));

        assertThatThrownBy(() -> fixture.service.reverseAllInventory(repair))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessageContaining("no traceable stock history");

        verify(fixture.stockLotService, never()).restoreSourceLineConsumption(
                "REPAIR", repair.getId(), usage.getId(), repair.getRepairDate().toLocalDate(),
                "REPAIR:10:USAGE:30:RESTORE:REV:null:FIFO");
        assertThat(mockingDetails(fixture.stockLedgerService).getInvocations())
                .noneMatch(invocation -> "recordMovement".equals(invocation.getMethod().getName()));
    }

    @Test
    void emptyFifoRestoreNeverCreatesInboundMovement() {
        Fixture fixture = fixture();
        RepairRecord repair = repair();
        RepairPartUsage usage = usage();
        StockLotConsumption exact = consumption(usage);
        PartInventory part = new PartInventory();
        part.setId(usage.getPartId());
        part.setWarehouseId(usage.getWarehouseId());
        part.setPartCode("P-20");
        part.setPartName("Part 20");
        when(fixture.usageRepository.findByRepairIdOrderByIdAsc(repair.getId()))
                .thenReturn(List.of(usage));
        when(fixture.consumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        "REPAIR", repair.getId(), usage.getId()))
                .thenReturn(List.of(exact));
        when(fixture.partRepository.findByIdAndIsLockedFalseForUpdate(usage.getPartId()))
                .thenReturn(Optional.of(part));
        when(fixture.stockLedgerService.resolveWarehouseId(usage.getWarehouseId()))
                .thenReturn(usage.getWarehouseId());
        when(fixture.stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, usage.getPartId(), usage.getWarehouseId()))
                .thenReturn(4);
        when(fixture.stockLotService.restoreSourceLineConsumption(
                "REPAIR", repair.getId(), usage.getId(), repair.getRepairDate().toLocalDate(),
                "REPAIR:10:USAGE:30:RESTORE:REV:null:FIFO"))
                .thenReturn(StockLotService.ConsumptionResult.empty());

        assertThatThrownBy(() -> fixture.service.reverseAllInventory(repair))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessageContaining("stock was not restored");

        assertThat(mockingDetails(fixture.stockLedgerService).getInvocations())
                .noneMatch(invocation -> "recordMovement".equals(invocation.getMethod().getName()));
    }

    private Fixture fixture() {
        RepairRecordRepository repairRepository = mock(RepairRecordRepository.class);
        RepairPartUsageRepository usageRepository = mock(RepairPartUsageRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        StockMovementLineRepository movementLineRepository = mock(StockMovementLineRepository.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        MigrationExceptionService migrationExceptionService = mock(MigrationExceptionService.class);
        CollaborationService collaborationService = mock(CollaborationService.class);
        ResourceVisibilityPolicy visibilityPolicy = mock(ResourceVisibilityPolicy.class);
        RepairPartUsageService service = new RepairPartUsageService(
                repairRepository,
                usageRepository,
                partRepository,
                stockLedgerService,
                stockLotService,
                movementLineRepository,
                consumptionRepository,
                migrationExceptionService,
                collaborationService,
                visibilityPolicy
        );
        return new Fixture(service, usageRepository, partRepository, stockLedgerService,
                stockLotService, consumptionRepository);
    }

    private RepairRecord repair() {
        RepairRecord repair = new RepairRecord();
        repair.setId(10L);
        repair.setUsedPartIds("20");
        repair.setRepairDate(java.time.LocalDateTime.of(2026, 7, 18, 10, 0));
        return repair;
    }

    private RepairPartUsage usage() {
        RepairPartUsage usage = new RepairPartUsage();
        usage.setId(30L);
        usage.setRepairId(10L);
        usage.setPartId(20L);
        usage.setWarehouseId(40L);
        usage.setQuantity(1);
        usage.setStockLotConsumptionId(50L);
        return usage;
    }

    private StockLotConsumption consumption(RepairPartUsage usage) {
        StockLotConsumption consumption = new StockLotConsumption();
        consumption.setId(usage.getStockLotConsumptionId());
        consumption.setStockLotId(60L);
        consumption.setResourceType(StockLedgerService.RESOURCE_PART);
        consumption.setResourceId(usage.getPartId());
        consumption.setWarehouseId(usage.getWarehouseId());
        consumption.setSourceType("REPAIR");
        consumption.setSourceId(usage.getRepairId());
        consumption.setSourceLineId(usage.getId());
        consumption.setQuantity(usage.getQuantity());
        return consumption;
    }

    private record Fixture(
            RepairPartUsageService service,
            RepairPartUsageRepository usageRepository,
            PartInventoryRepository partRepository,
            StockLedgerService stockLedgerService,
            StockLotService stockLotService,
            StockLotConsumptionRepository consumptionRepository
    ) {
    }
}
