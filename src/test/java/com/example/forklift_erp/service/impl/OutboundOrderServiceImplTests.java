package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.OutboundOrderUpdateDTO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceAttachmentService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboundOrderServiceImplTests {

    @Test
    void updateRecalculatesAmountFromUnitPriceAndPreservesOmittedFields() {
        Fixture fixture = fixture();
        OutboundOrder order = fixture.order;
        OutboundOrderUpdateDTO request = new OutboundOrderUpdateDTO();
        request.setVersion(4L);
        request.setUnitSalePrice(new BigDecimal("100.00"));

        fixture.service.update(order.getId(), request);

        assertThat(order.getUnitSalePrice()).isEqualByComparingTo("100.00");
        assertThat(order.getSettlementPrice()).isEqualByComparingTo("100.00");
        assertThat(order.getLineAmount()).isEqualByComparingTo("300.00");
        assertThat(order.getReceivableAmount()).isEqualByComparingTo("300.00");
        assertThat(order.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(order.getLastPaymentDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(order.getPaymentRemark()).isEqualTo("Partial receipt");
        assertThat(order.getInvoiceStatus()).isEqualTo("Pending");
        assertThat(order.getOrderRemark()).isEqualTo("Keep this note");
        verify(fixture.financialEventService).replaceSalesPosting(eq(order), eq(BigDecimal.ZERO), eq(false));
    }

    @Test
    void updateSettlementPriceCompatibilityFieldAlsoRecalculatesLineAmount() {
        Fixture fixture = fixture();
        OutboundOrderUpdateDTO request = new OutboundOrderUpdateDTO();
        request.setVersion(4L);
        request.setSettlementPrice(new BigDecimal("80.00"));

        fixture.service.update(fixture.order.getId(), request);

        assertThat(fixture.order.getUnitSalePrice()).isEqualByComparingTo("80.00");
        assertThat(fixture.order.getLineAmount()).isEqualByComparingTo("240.00");
        assertThat(fixture.order.getReceivableAmount()).isEqualByComparingTo("240.00");
    }

    @Test
    void createVehicleOutboundRejectsActiveModificationBeforeStockMutation() {
        Fixture fixture = fixture();
        MachineInventory machine = new MachineInventory();
        machine.setId(41L);
        machine.setModelOnly(false);
        machine.setIsLocked(false);
        machine.setStockStatus(MachineStockStatus.MODIFYING.code());
        when(fixture.machineInventoryRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(machine));
        VehicleOutboundOrderCreateDTO request = new VehicleOutboundOrderCreateDTO();
        request.setMachineId(41L);

        assertThatThrownBy(() -> fixture.service.createVehicleOutbound(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("Vehicle status does not allow sales outbound: MODIFYING");

        verifyNoInteractions(fixture.stockLedgerService);
        verify(fixture.outboundOrderRepository, org.mockito.Mockito.never()).save(any());
    }

    private Fixture fixture() {
        OutboundOrderRepository outboundOrderRepository = mock(OutboundOrderRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        MachineInventoryRepository machineInventoryRepository = mock(MachineInventoryRepository.class);
        PartInventoryRepository partInventoryRepository = mock(PartInventoryRepository.class);
        RentalRecordRepository rentalRecordRepository = mock(RentalRecordRepository.class);
        OperationAuditService operationAuditService = mock(OperationAuditService.class);
        CollaborationService collaborationService = mock(CollaborationService.class);
        OutboundOrderFileStorage fileStorage = mock(OutboundOrderFileStorage.class);
        OutboundUploadReadinessPolicy uploadReadinessPolicy = mock(OutboundUploadReadinessPolicy.class);
        OutboundResourceLockService resourceLockService = mock(OutboundResourceLockService.class);
        ResourceAttachmentService resourceAttachmentService = mock(ResourceAttachmentService.class);
        ResourceVisibilityPolicy visibilityPolicy = mock(ResourceVisibilityPolicy.class);
        OutboundReceivablePolicy receivablePolicy = mock(OutboundReceivablePolicy.class);
        OutboundStockAccountingService stockAccountingService = mock(OutboundStockAccountingService.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        StockMovementLineRepository stockMovementLineRepository = mock(StockMovementLineRepository.class);
        StockOperationLogRepository stockOperationLogRepository = mock(StockOperationLogRepository.class);

        OutboundOrder order = new OutboundOrder();
        order.setId(12L);
        order.setVersion(4L);
        order.setOrderNo("OO-PART-012");
        order.setQuantity(3);
        order.setSettlementPrice(new BigDecimal("70.00"));
        order.setUnitSalePrice(new BigDecimal("70.00"));
        order.setLineAmount(new BigDecimal("210.00"));
        order.setReceivableAmount(new BigDecimal("210.00"));
        order.setReceivedAmount(new BigDecimal("70.00"));
        order.setPaymentDueDate(LocalDate.of(2026, 7, 31));
        order.setLastPaymentDate(LocalDate.of(2026, 7, 2));
        order.setPaymentRemark("Partial receipt");
        order.setInvoiceStatus("Pending");
        order.setOrderRemark("Keep this note");
        order.setIsLocked(false);
        when(outboundOrderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(outboundOrderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockMovementRepository.findBySourceTypeAndSourceId("OUTBOUND_ORDER", order.getId()))
                .thenReturn(List.of());

        OutboundOrderServiceImpl service = new OutboundOrderServiceImpl(
                outboundOrderRepository,
                customerRepository,
                machineInventoryRepository,
                partInventoryRepository,
                rentalRecordRepository,
                operationAuditService,
                collaborationService,
                fileStorage,
                uploadReadinessPolicy,
                resourceLockService,
                resourceAttachmentService,
                visibilityPolicy,
                receivablePolicy,
                stockAccountingService,
                stockLedgerService,
                stockLotService,
                financialEventService,
                stockMovementRepository,
                stockMovementLineRepository,
                stockOperationLogRepository
        );
        return new Fixture(
                service,
                order,
                financialEventService,
                outboundOrderRepository,
                machineInventoryRepository,
                stockLedgerService
        );
    }

    private record Fixture(
            OutboundOrderServiceImpl service,
            OutboundOrder order,
            FinancialEventService financialEventService,
            OutboundOrderRepository outboundOrderRepository,
            MachineInventoryRepository machineInventoryRepository,
            StockLedgerService stockLedgerService
    ) {
    }
}
