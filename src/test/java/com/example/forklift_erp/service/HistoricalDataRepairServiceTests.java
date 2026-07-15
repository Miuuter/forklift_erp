package com.example.forklift_erp.service;

import com.example.forklift_erp.dto.HistoricalRepairReportVO;
import com.example.forklift_erp.dto.HistoricalRepairRequestDTO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDataRepairServiceTests {

    @Test
    void dryRunIdentifiesThreePiecePartRevenueBugWithoutWriting() {
        Fixture fixture = fixture();

        HistoricalRepairReportVO report = fixture.service.dryRun(LocalDate.of(2026, 7, 1));

        assertThat(report.isDryRun()).isTrue();
        assertThat(report.getFixedCount()).isEqualTo(1);
        assertThat(fixture.order.getUnitSalePrice()).isEqualByComparingTo("300.00");
        assertThat(fixture.line.getUnitRevenue()).isEqualByComparingTo("300.00");
        verify(fixture.outboundOrderRepository, never()).saveAndFlush(any());
        verify(fixture.financialEventService, never()).replaceSalesPosting(any(), any(), anyBoolean());
        verify(fixture.historicalRepairBackupService, never()).createPreRepairBackup();
    }

    @Test
    void executionRewritesOpenPartOrderAndMovementToOneUnitRevenue() {
        Fixture fixture = fixture();
        HistoricalRepairRequestDTO request = new HistoricalRepairRequestDTO();
        request.setBackupConfirmed(true);
        request.setFallbackBusinessDate(LocalDate.of(2026, 7, 1));
        when(fixture.outboundOrderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalRepairReportVO report = fixture.service.repair(request);

        assertThat(report.isDryRun()).isFalse();
        assertThat(report.getFixedCount()).isEqualTo(1);
        assertThat(fixture.order.getUnitSalePrice()).isEqualByComparingTo("100.00");
        assertThat(fixture.order.getSettlementPrice()).isEqualByComparingTo("100.00");
        assertThat(fixture.order.getLineAmount()).isEqualByComparingTo("300.00");
        assertThat(fixture.order.getReceivableAmount()).isEqualByComparingTo("300.00");
        assertThat(fixture.line.getUnitRevenue()).isEqualByComparingTo("100.00");
        assertThat(fixture.line.getLineAmount()).isEqualByComparingTo("300.00");
        assertThat(fixture.movement.getBusinessDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(report.getBackupFileName()).isEqualTo("pre-historical-repair-test.json");
        assertThat(report.getBackupSha256()).isEqualTo("test-sha256");
        verify(fixture.financialEventService).replaceSalesPosting(eq(fixture.order), eq(new BigDecimal("90.00")), eq(false));
        verify(fixture.stockMovementLineRepository).save(fixture.line);
        verify(fixture.stockMovementRepository).save(fixture.movement);
        verify(fixture.historicalRepairBackupService).createPreRepairBackup();
    }

    private Fixture fixture() {
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        StockMovementLineRepository stockMovementLineRepository = mock(StockMovementLineRepository.class);
        StockOperationLogRepository stockOperationLogRepository = mock(StockOperationLogRepository.class);
        FinancialEventRepository financialEventRepository = mock(FinancialEventRepository.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);
        RentalRecordRepository rentalRecordRepository = mock(RentalRecordRepository.class);
        MachineInventoryRepository machineInventoryRepository = mock(MachineInventoryRepository.class);
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        StockLotRepository stockLotRepository = mock(StockLotRepository.class);
        StockLotService stockLotService = mock(StockLotService.class);
        RepairRecordRepository repairRecordRepository = mock(RepairRecordRepository.class);
        RepairPartUsageRepository repairPartUsageRepository = mock(RepairPartUsageRepository.class);
        ModificationWorkOrderLineRepository modificationWorkOrderLineRepository = mock(ModificationWorkOrderLineRepository.class);
        MigrationExceptionService migrationExceptionService = mock(MigrationExceptionService.class);
        HistoricalRepairBackupService historicalRepairBackupService = mock(HistoricalRepairBackupService.class);
        OperationAuditService operationAuditService = mock(OperationAuditService.class);

        OutboundOrder order = new OutboundOrder();
        order.setId(21L);
        order.setOrderNo("OO-PART-021");
        order.setResourceType(OutboundOrder.RESOURCE_PART);
        order.setResourceId(7L);
        order.setSourceWarehouseId(1L);
        order.setQuantity(3);
        order.setSalesDate(LocalDate.of(2026, 6, 15));
        order.setUnitSalePrice(new BigDecimal("300.00"));
        order.setSettlementPrice(new BigDecimal("300.00"));
        order.setLineAmount(new BigDecimal("300.00"));
        order.setReceivableAmount(new BigDecimal("300.00"));
        order.setIsLocked(false);

        StockMovement movement = new StockMovement();
        movement.setId(51L);
        movement.setBusinessDate(LocalDate.of(2026, 7, 1));
        StockMovementLine line = new StockMovementLine();
        line.setId(61L);
        line.setMovementId(51L);
        line.setQuantityDelta(-3);
        line.setUnitRevenue(new BigDecimal("300.00"));
        line.setLineAmount(new BigDecimal("900.00"));
        line.setUnitCost(new BigDecimal("30.00"));
        line.setCostAmount(new BigDecimal("90.00"));

        Warehouse warehouse = new Warehouse();
        warehouse.setId(1L);
        warehouse.setWarehouseCode("WH-1");
        warehouse.setWarehouseName("Main");

        when(outboundOrderRepository.findAll()).thenReturn(List.of(order));
        when(stockMovementRepository.findBySourceTypeAndSourceId(FinancialEventService.SOURCE_OUTBOUND_ORDER, 21L))
                .thenReturn(List.of(movement));
        when(stockMovementLineRepository.findByMovementIdOrderByIdAsc(51L)).thenReturn(List.of(line));
        when(financialEventRepository.findBySourceTypeAndSourceIdOrderByIdAsc(FinancialEventService.SOURCE_OUTBOUND_ORDER, 21L))
                .thenReturn(List.of());
        when(rentalRecordRepository.findAll()).thenReturn(List.of());
        when(machineInventoryRepository.findAll()).thenReturn(List.of());
        when(partInventoryRepository.findAll()).thenReturn(List.of());
        when(purchaseOrderRepository.findAll()).thenReturn(List.of());
        when(supplierRepository.findAll()).thenReturn(List.of());
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(stockBalanceRepository.findAll()).thenReturn(List.of());
        when(stockLotRepository.findAll()).thenReturn(List.of());
        when(repairRecordRepository.findAll()).thenReturn(List.of());
        when(modificationWorkOrderLineRepository.findAll()).thenReturn(List.of());
        when(historicalRepairBackupService.createPreRepairBackup()).thenReturn(
                new HistoricalRepairBackupService.BackupReceipt(
                        "pre-historical-repair-test.json", 128L, "test-sha256", LocalDateTime.now()
                )
        );

        HistoricalReferenceRepairService historicalReferenceRepairService = new HistoricalReferenceRepairService(
                supplierRepository,
                warehouseRepository,
                machineInventoryRepository,
                partInventoryRepository,
                purchaseOrderRepository,
                outboundOrderRepository,
                stockBalanceRepository,
                stockMovementRepository,
                stockMovementLineRepository,
                migrationExceptionService
        );
        HistoricalDataRepairService service = new HistoricalDataRepairService(
                outboundOrderRepository,
                stockMovementRepository,
                stockMovementLineRepository,
                stockOperationLogRepository,
                financialEventRepository,
                financialEventService,
                rentalRecordRepository,
                machineInventoryRepository,
                partInventoryRepository,
                stockBalanceRepository,
                stockLotRepository,
                stockLotService,
                repairRecordRepository,
                repairPartUsageRepository,
                modificationWorkOrderLineRepository,
                migrationExceptionService,
                historicalRepairBackupService,
                historicalReferenceRepairService,
                operationAuditService
        );
        return new Fixture(service, order, movement, line, outboundOrderRepository, stockMovementRepository,
                stockMovementLineRepository, financialEventService, historicalRepairBackupService);
    }

    private record Fixture(
            HistoricalDataRepairService service,
            OutboundOrder order,
            StockMovement movement,
            StockMovementLine line,
            OutboundOrderRepository outboundOrderRepository,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            FinancialEventService financialEventService,
            HistoricalRepairBackupService historicalRepairBackupService
    ) {
    }
}
