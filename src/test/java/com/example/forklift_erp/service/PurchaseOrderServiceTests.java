package com.example.forklift_erp.service;

import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderServiceTests {

    @Test
    void landedUnitCostKeepsSixDecimalsForNonDivisiblePurchaseTotal() {
        PurchaseOrder order = new PurchaseOrder();
        order.setQuantity(3);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setFreightAmount(BigDecimal.ZERO);

        BigDecimal landedUnitCost = ReflectionTestUtils.invokeMethod(
                new PurchaseOrderService(), "landedUnitCost", order);

        assertThat(landedUnitCost).isEqualByComparingTo("33.333333");
    }

    @Test
    void reversingPartReceiptLocksResourceBeforeRevalidatingAndReversingLot() {
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        CollaborationService collaborationService = mock(CollaborationService.class);
        OperationAuditService operationAuditService = mock(OperationAuditService.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);

        PurchaseOrder order = receivedPartOrder();
        PartInventory part = new PartInventory();
        part.setId(7L);
        part.setPartCode("P-7");
        part.setPartName("Part 7");
        part.setQuantity(3);
        part.setPurchasePrice(new BigDecimal("33.33"));
        part.setLandedUnitCost(new BigDecimal("33.333333"));
        StockMovement movement = new StockMovement();
        movement.setId(21L);

        when(purchaseOrderRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(order));
        when(purchaseOrderRepository
                .existsByResourceTypeAndResourceIdAndReceivedStockMovementIdGreaterThan("PART", 7L, 12L))
                .thenReturn(false);
        when(partInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(part));
        when(stockLedgerService.resolveWarehouseId(3L)).thenReturn(3L);
        when(stockLedgerService.availableQuantity("PART", 7L, 3L)).thenReturn(3);
        when(stockLedgerService.totalAvailableQuantity("PART", 7L)).thenReturn(0);
        when(stockLedgerService.recordMovement(
                anyString(), anyString(), anyLong(), nullable(String.class), nullable(String.class), anyLong(),
                anyInt(), anyInt(), any(BigDecimal.class), nullable(String.class), nullable(String.class),
                anyString(), anyLong(), nullable(Long.class), any(LocalDate.class), anyString(),
                any(BigDecimal.class), anyString(), anyLong(), nullable(Long.class)
        )).thenReturn(movement);
        when(purchaseOrderRepository.saveAndFlush(order)).thenReturn(order);

        PurchaseOrderService service = new PurchaseOrderService();
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(service, "collaborationService", collaborationService);
        ReflectionTestUtils.setField(service, "operationAuditService", operationAuditService);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "stockLotService", stockLotService);
        ReflectionTestUtils.setField(service, "financialEventService", financialEventService);
        ReflectionTestUtils.setField(service, "partInventoryRepository", partInventoryRepository);

        service.setReceived(5L, false, 2L);

        InOrder ordered = inOrder(partInventoryRepository, stockLotService);
        ordered.verify(partInventoryRepository).findByIdForUpdate(7L);
        ordered.verify(stockLotService).reverseReceiptLot(13L);
        verify(stockLedgerService).recordMovement(
                anyString(), anyString(), anyLong(), nullable(String.class), nullable(String.class), anyLong(),
                anyInt(), anyInt(), any(BigDecimal.class), nullable(String.class), nullable(String.class),
                anyString(), anyLong(), nullable(Long.class), any(LocalDate.class), anyString(),
                any(BigDecimal.class), anyString(), anyLong(), nullable(Long.class)
        );
        assertThat(order.getStockLotId()).isNull();
        assertThat(order.getReceivedStockMovementId()).isNull();
        assertThat(order.getStatus()).isEqualTo("ORDERED");
        assertThat(part.getPurchasePrice()).isEqualByComparingTo("20.00");
        assertThat(part.getLandedUnitCost()).isEqualByComparingTo("20.125000");
        assertThat(order.getResourceCostSnapshotCaptured()).isFalse();
        assertThat(order.getPreviousResourcePurchasePrice()).isNull();
        assertThat(order.getPreviousResourceLandedUnitCost()).isNull();
    }

    @Test
    void olderPartReceiptCannotOverwriteCostCacheWhileNewerReceiptIsActive() {
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        CollaborationService collaborationService = mock(CollaborationService.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
        PurchaseOrder order = receivedPartOrder();
        PartInventory part = new PartInventory();
        part.setId(7L);

        when(purchaseOrderRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(order));
        when(partInventoryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(part));
        when(stockLedgerService.resolveWarehouseId(3L)).thenReturn(3L);
        when(stockLedgerService.availableQuantity("PART", 7L, 3L)).thenReturn(3);
        when(purchaseOrderRepository
                .existsByResourceTypeAndResourceIdAndReceivedStockMovementIdGreaterThan("PART", 7L, 12L))
                .thenReturn(true);

        PurchaseOrderService service = new PurchaseOrderService();
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(service, "collaborationService", collaborationService);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "stockLotService", stockLotService);
        ReflectionTestUtils.setField(service, "partInventoryRepository", partInventoryRepository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setReceived(5L, false, 2L))
                .isInstanceOf(com.example.forklift_erp.exception.BusinessException.class)
                .hasMessage("A newer receipt for this resource must be reversed first");

        verify(stockLotService, never()).reverseReceiptLot(anyLong());
    }

    private static PurchaseOrder receivedPartOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(5L);
        order.setVersion(2L);
        order.setPurchaseNo("PO-5");
        order.setResourceType(PurchaseOrder.RESOURCE_PART);
        order.setResourceId(7L);
        order.setResourceCode("P-7");
        order.setResourceName("Part 7");
        order.setWarehouseId(3L);
        order.setQuantity(3);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setFreightAmount(BigDecimal.ZERO);
        order.setLandedUnitCost(new BigDecimal("33.333333"));
        order.setPreviousResourcePurchasePrice(new BigDecimal("20.00"));
        order.setPreviousResourceLandedUnitCost(new BigDecimal("20.125000"));
        order.setResourceCostSnapshotCaptured(true);
        order.setReceivedDate(LocalDate.of(2026, 7, 18));
        order.setReceivedStockMovementId(12L);
        order.setStockLotId(13L);
        order.setFinancialPosted(true);
        order.setStatus("RECEIVED");
        order.setStatusBeforeReceived("ORDERED");
        order.setOperator("tester");
        order.setRemark("reverse test");
        return order;
    }
}
