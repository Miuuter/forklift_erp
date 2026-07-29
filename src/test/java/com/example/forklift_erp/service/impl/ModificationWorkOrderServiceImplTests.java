package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.dto.ModificationWorkOrderActionDTO;
import com.example.forklift_erp.dto.ModificationWorkOrderCreateDTO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigReplaceLog;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.ConfigReplaceService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.ModificationAccountingService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModificationWorkOrderServiceImplTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsDuplicateMachineConfigLinesBeforeDatabaseWork() {
        ModificationWorkOrderCreateDTO.Line first = new ModificationWorkOrderCreateDTO.Line();
        first.setMachineConfigId(10L);
        ModificationWorkOrderCreateDTO.Line duplicate = new ModificationWorkOrderCreateDTO.Line();
        duplicate.setMachineConfigId(10L);
        ModificationWorkOrderCreateDTO request = new ModificationWorkOrderCreateDTO();
        request.setLines(List.of(first, duplicate));

        ModificationWorkOrderServiceImpl service = new ModificationWorkOrderServiceImpl();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Each machine configuration can appear only once in a modification work order");
    }

    @Test
    void createRejectsLockedMachine() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, true)));
        ModificationWorkOrderServiceImpl service = service(workOrderRepository, mock(ModificationWorkOrderLineRepository.class),
                machineRepository, mock(MachineConfigRepository.class), mock(PartInventoryRepository.class), mock(ConfigReplaceService.class));

        ModificationWorkOrderCreateDTO request = createRequest(1L, 10L, 2L);

        assertForbidden(() -> service.create(request), "Vehicle is locked and cannot create modification work order");
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsLockedPart() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository configRepository = mock(MachineConfigRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(config(10L, 1L)));
        when(partRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(part(2L, true)));
        ModificationWorkOrderServiceImpl service = service(workOrderRepository, mock(ModificationWorkOrderLineRepository.class),
                machineRepository, configRepository, partRepository, mock(ConfigReplaceService.class));

        ModificationWorkOrderCreateDTO request = createRequest(1L, 10L, 2L);

        assertForbidden(() -> service.create(request), "Part is locked and cannot be used in modification work order");
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void completeRejectsMachineLockedAfterOrderWasCreated() {
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        ModificationWorkOrderLineRepository lineRepository = mock(ModificationWorkOrderLineRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        ModificationWorkOrder order = order(20L, 1L);
        when(workOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(lineRepository.findByWorkOrderIdOrderByIdAsc(20L)).thenReturn(List.of(line(10L, 2L)));
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, true)));
        ModificationWorkOrderServiceImpl service = service(workOrderRepository, lineRepository, machineRepository,
                mock(MachineConfigRepository.class), mock(PartInventoryRepository.class), mock(ConfigReplaceService.class));

        assertForbidden(() -> service.complete(20L, action()), "Vehicle is locked and cannot complete modification work order");
        verify(machineRepository, never()).save(any());
    }

    @Test
    void completeRejectsPartLockedAfterOrderWasCreated() {
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        ModificationWorkOrderLineRepository lineRepository = mock(ModificationWorkOrderLineRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository configRepository = mock(MachineConfigRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        ConfigReplaceService replaceService = mock(ConfigReplaceService.class);
        ModificationWorkOrder order = order(20L, 1L);
        when(workOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(lineRepository.findByWorkOrderIdOrderByIdAsc(20L)).thenReturn(List.of(line(10L, 2L)));
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(config(10L, 1L)));
        when(partRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(part(2L, true)));
        ModificationWorkOrderServiceImpl service = service(workOrderRepository, lineRepository, machineRepository,
                configRepository, partRepository, replaceService);

        assertForbidden(() -> service.complete(20L, action()), "Part is locked and cannot be used in modification work order");
        verify(replaceService, never()).performPartReplace(any());
    }

    @Test
    void cancelRejectsMachineLockedAfterOrderWasCreated() {
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        ModificationWorkOrder order = order(20L, 1L);
        when(workOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, true)));
        ModificationWorkOrderServiceImpl service = service(workOrderRepository, mock(ModificationWorkOrderLineRepository.class),
                machineRepository, mock(MachineConfigRepository.class), mock(PartInventoryRepository.class), mock(ConfigReplaceService.class));

        assertForbidden(() -> service.cancel(20L, action()), "Vehicle is locked and cannot cancel modification work order");
        verify(workOrderRepository, never()).save(any());
        verify(machineRepository, never()).save(any());
    }

    @Test
    void createRejectsDiscountThatExceedsTheLineCharge() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository configRepository = mock(MachineConfigRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(config(10L, 1L)));
        when(partRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(part(2L, false)));
        ModificationWorkOrderServiceImpl service = service(
                workOrderRepository,
                mock(ModificationWorkOrderLineRepository.class),
                machineRepository,
                configRepository,
                partRepository,
                mock(ConfigReplaceService.class)
        );
        ModificationWorkOrderCreateDTO request = createRequest(1L, 10L, 2L);
        request.getLines().get(0).setChargeUnitPrice(new BigDecimal("100.00"));
        request.getLines().get(0).setDiscountAmount(new BigDecimal("100.01"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Modification discount cannot exceed the line charge");

        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsSecondActiveWorkOrderForTheSameVehicle() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(workOrderRepository.existsByMachineIdAndStatusIn(eq(1L), any())).thenReturn(true);
        ModificationWorkOrderServiceImpl service = service(
                workOrderRepository,
                mock(ModificationWorkOrderLineRepository.class),
                machineRepository,
                mock(MachineConfigRepository.class),
                mock(PartInventoryRepository.class),
                mock(ConfigReplaceService.class)
        );

        assertThatThrownBy(() -> service.create(createRequest(1L, 10L, 2L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("Vehicle already has an active modification work order");

        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void preSaleCompletionCapitalizesNetFifoCostAfterOldPartRecovery() {
        CompletionFixture fixture = completionFixture("PRE_SALE");

        fixture.service.complete(fixture.order.getId(), action());

        assertThat(fixture.line.getCostAmount()).isEqualByComparingTo("100.00");
        assertThat(fixture.machine.getLandedUnitCost()).isEqualByComparingTo("570.00");
        assertThat(fixture.machine.getStockStatus()).isEqualTo(MachineStockStatus.PENDING_OUTBOUND.code());
        assertThat(fixture.order.getFinancialPosted()).isTrue();
        verify(fixture.stockLotService).capitalizeSerializedAssetCost(
                StockLedgerService.RESOURCE_MACHINE,
                fixture.machine.getId(),
                fixture.order.getWarehouseId(),
                new BigDecimal("70.00"),
                "MODIFICATION_WORK_ORDER",
                fixture.order.getId(),
                fixture.line.getId(),
                fixture.order.getBusinessDate(),
                "MODIFICATION-CAPITALIZATION:" + fixture.order.getId() + ":" + fixture.line.getId()
        );
        verifyNoInteractions(fixture.financialEventService);
    }

    @Test
    void afterSaleCompletionPostsChargeCostAndRecoveredOldPartValueWithoutCapitalization() {
        CompletionFixture fixture = completionFixture("AFTER_SALE");

        fixture.service.complete(fixture.order.getId(), action());

        assertThat(fixture.machine.getLandedUnitCost()).isEqualByComparingTo("500.00");
        verify(fixture.stockLotService, never()).capitalizeSerializedAssetCost(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> amounts = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.financialEventService, times(4)).post(
                eventTypes.capture(),
                amounts.capture(),
                eq(fixture.order.getBusinessDate()),
                eq("MODIFICATION_WORK_ORDER"),
                eq(fixture.order.getId()),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        assertThat(eventTypes.getAllValues()).containsExactly(
                FinancialEventType.ACCOUNTS_RECEIVABLE,
                FinancialEventType.REVENUE,
                FinancialEventType.OPERATING_COST,
                FinancialEventType.INVENTORY_GAIN
        );
        assertThat(amounts.getAllValues()).containsExactly(
                new BigDecimal("150.00"),
                new BigDecimal("150.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00")
        );
    }

    private ModificationWorkOrderServiceImpl service(
            ModificationWorkOrderRepository workOrderRepository,
            ModificationWorkOrderLineRepository lineRepository,
            MachineInventoryRepository machineRepository,
            MachineConfigRepository configRepository,
            PartInventoryRepository partRepository,
            ConfigReplaceService replaceService
    ) {
        ModificationWorkOrderServiceImpl service = new ModificationWorkOrderServiceImpl();
        ReflectionTestUtils.setField(service, "workOrderRepository", workOrderRepository);
        ReflectionTestUtils.setField(service, "lineRepository", lineRepository);
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);
        ReflectionTestUtils.setField(service, "machineConfigRepository", configRepository);
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "configItemRepository", mock(ConfigItemRepository.class));
        ReflectionTestUtils.setField(service, "configValueRepository", mock(ConfigValueRepository.class));
        ReflectionTestUtils.setField(service, "configReplaceService", replaceService);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
        ReflectionTestUtils.setField(service, "modificationAccountingService", mock(ModificationAccountingService.class));
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        when(stockLedgerService.resolveWarehouseId(any())).thenReturn(1L);
        when(stockLedgerService.availableQuantity(any(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        return service;
    }

    private CompletionFixture completionFixture(String workOrderType) {
        ModificationWorkOrderRepository workOrderRepository = mock(ModificationWorkOrderRepository.class);
        ModificationWorkOrderLineRepository lineRepository = mock(ModificationWorkOrderLineRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository configRepository = mock(MachineConfigRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        ConfigItemRepository configItemRepository = mock(ConfigItemRepository.class);
        ConfigValueRepository configValueRepository = mock(ConfigValueRepository.class);
        ConfigReplaceService replaceService = mock(ConfigReplaceService.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        StockLotConsumptionRepository consumptionRepository = mock(StockLotConsumptionRepository.class);
        FinancialEventService financialEventService = mock(FinancialEventService.class);

        ModificationWorkOrder order = order(20L, 1L);
        order.setWorkOrderType(workOrderType);
        order.setWarehouseId(8L);
        order.setBusinessDate(LocalDate.of(2026, 7, 15));
        order.setCustomerName("Acme");
        order.setFinancialPosted(false);

        ModificationWorkOrderLine line = line(10L, 2L);
        line.setId(30L);
        line.setWorkOrderId(order.getId());
        line.setWarehouseId(8L);
        line.setOldValue("Old part");
        line.setOldPartAction(PartChangeAction.STOCK_IN.code());
        line.setOldPartDisposition("RETURN_TO_STOCK");
        line.setOldPartUnitCost(new BigDecimal("30.00"));
        line.setChargeAmount(new BigDecimal("150.00"));

        MachineInventory machine = machine(1L, false);
        machine.setWarehouseId(8L);
        machine.setLandedUnitCost(new BigDecimal("500.00"));
        machine.setStockStatus(MachineStockStatus.PENDING_MODIFICATION.code());

        MachineConfig config = config(10L, 1L);
        config.setSelectedValue("Old part");
        PartInventory part = part(2L, false);
        ConfigItem configItem = new ConfigItem();
        configItem.setId(30L);
        configItem.setItemName("Tire");
        configItem.setSubCategory("Tire");
        ConfigReplaceLog replaceLog = new ConfigReplaceLog();
        replaceLog.setId(55L);
        StockLotConsumption consumption = new StockLotConsumption();
        consumption.setId(70L);
        consumption.setTotalCost(new BigDecimal("100.00"));

        when(workOrderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(workOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lineRepository.findByWorkOrderIdOrderByIdAsc(order.getId())).thenReturn(List.of(line));
        when(lineRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(machineRepository.findByIdForUpdate(machine.getId())).thenReturn(Optional.of(machine));
        when(machineRepository.findById(machine.getId())).thenReturn(Optional.of(machine));
        when(machineRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByIdForUpdate(config.getId())).thenReturn(Optional.of(config));
        when(partRepository.findByIdForUpdate(part.getId())).thenReturn(Optional.of(part));
        when(configItemRepository.findById(configItem.getId())).thenReturn(Optional.of(configItem));
        when(replaceService.performPartReplace(any())).thenReturn(replaceLog);
        when(consumptionRepository.findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                "MODIFICATION_WORK_ORDER", order.getId(), line.getId())).thenReturn(List.of(consumption));

        ModificationWorkOrderServiceImpl service = new ModificationWorkOrderServiceImpl();
        ReflectionTestUtils.setField(service, "workOrderRepository", workOrderRepository);
        ReflectionTestUtils.setField(service, "lineRepository", lineRepository);
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);
        ReflectionTestUtils.setField(service, "machineConfigRepository", configRepository);
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "configItemRepository", configItemRepository);
        ReflectionTestUtils.setField(service, "configValueRepository", configValueRepository);
        ReflectionTestUtils.setField(service, "configReplaceService", replaceService);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "stockLotService", stockLotService);
        ReflectionTestUtils.setField(
                service,
                "modificationAccountingService",
                new ModificationAccountingService(
                        workOrderRepository,
                        consumptionRepository,
                        financialEventService
                )
        );

        return new CompletionFixture(
                service,
                order,
                line,
                machine,
                stockLotService,
                financialEventService
        );
    }

    private ModificationWorkOrderCreateDTO createRequest(Long machineId, Long configId, Long partId) {
        ModificationWorkOrderCreateDTO.Line line = new ModificationWorkOrderCreateDTO.Line();
        line.setMachineConfigId(configId);
        line.setNewPartId(partId);
        ModificationWorkOrderCreateDTO request = new ModificationWorkOrderCreateDTO();
        request.setMachineId(machineId);
        request.setLines(List.of(line));
        return request;
    }

    private ModificationWorkOrderActionDTO action() {
        ModificationWorkOrderActionDTO request = new ModificationWorkOrderActionDTO();
        request.setVersion(0L);
        return request;
    }

    private ModificationWorkOrder order(Long id, Long machineId) {
        ModificationWorkOrder order = new ModificationWorkOrder();
        order.setId(id);
        order.setVersion(0L);
        order.setMachineId(machineId);
        order.setWorkOrderNo("MO-TEST");
        order.setStatus(ModificationWorkOrderStatus.WAITING_PARTS.code());
        return order;
    }

    private ModificationWorkOrderLine line(Long configId, Long partId) {
        ModificationWorkOrderLine line = new ModificationWorkOrderLine();
        line.setMachineConfigId(configId);
        line.setNewPartId(partId);
        line.setQuantity(1);
        return line;
    }

    private MachineConfig config(Long id, Long machineId) {
        MachineConfig config = new MachineConfig();
        config.setId(id);
        config.setMachineId(machineId);
        config.setConfigItemId(30L);
        config.setItemName("Tire");
        return config;
    }

    private MachineInventory machine(Long id, boolean locked) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setIsLocked(locked);
        machine.setInventoryCount(1);
        machine.setWarehouseId(1L);
        machine.setStockStatus(MachineStockStatus.IN_STOCK.code());
        return machine;
    }

    private PartInventory part(Long id, boolean locked) {
        PartInventory part = new PartInventory();
        part.setId(id);
        part.setIsLocked(locked);
        part.setQuantity(1);
        part.setPartCategory("Tire");
        return part;
    }

    private void assertForbidden(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()))
                .hasMessage(message);
    }

    private record CompletionFixture(
            ModificationWorkOrderServiceImpl service,
            ModificationWorkOrder order,
            ModificationWorkOrderLine line,
            MachineInventory machine,
            StockLotService stockLotService,
            FinancialEventService financialEventService
    ) {
    }
}
