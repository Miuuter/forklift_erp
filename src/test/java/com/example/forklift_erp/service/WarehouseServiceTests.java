package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.StockTransferDTO;
import com.example.forklift_erp.dto.WarehouseDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WarehouseServiceTests {

    @Test
    void createDefaultLocksAllWarehousesAndFlushesRetiredDefaultBeforeSavingNewDefault() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        Warehouse previousDefault = warehouse(1L, 3L, "WH-OLD", true);
        Warehouse secondary = warehouse(2L, 1L, "WH-SECONDARY", false);
        when(warehouseRepository.findAllForUpdate()).thenReturn(List.of(previousDefault, secondary));
        when(stockBalanceRepository.findAll()).thenReturn(List.of());

        AtomicBoolean retiredDefaultFlushed = new AtomicBoolean();
        when(warehouseRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<Warehouse> retired = invocation.getArgument(0);
            assertThat(retired).containsExactly(previousDefault);
            assertThat(previousDefault.getDefaultWarehouse()).isFalse();
            retiredDefaultFlushed.set(true);
            return List.of(previousDefault);
        });
        when(warehouseRepository.saveAndFlush(any(Warehouse.class))).thenAnswer(invocation -> {
            assertThat(retiredDefaultFlushed).isTrue();
            Warehouse saved = invocation.getArgument(0);
            saved.setId(3L);
            saved.setVersion(0L);
            return saved;
        });

        WarehouseService service = warehouseService(warehouseRepository, stockBalanceRepository);
        WarehouseDTO request = warehouseRequest(null, "WH-NEW", true);

        service.create(request);

        var ordered = inOrder(warehouseRepository);
        ordered.verify(warehouseRepository).findAllForUpdate();
        ordered.verify(warehouseRepository).saveAllAndFlush(any());
        ordered.verify(warehouseRepository).saveAndFlush(argThat(warehouse ->
                "WH-NEW".equals(warehouse.getWarehouseCode())
                        && Boolean.TRUE.equals(warehouse.getDefaultWarehouse())));
        assertThat(previousDefault.getDefaultWarehouse()).isFalse();
    }

    @Test
    void updateDefaultLocksAllWarehousesAndFlushesRetiredDefaultBeforeSavingReplacement() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        Warehouse previousDefault = warehouse(1L, 3L, "WH-OLD", true);
        Warehouse replacement = warehouse(2L, 7L, "WH-NEW", false);
        when(warehouseRepository.findAllForUpdate()).thenReturn(List.of(previousDefault, replacement));
        when(stockBalanceRepository.findAll()).thenReturn(List.of());

        AtomicBoolean retiredDefaultFlushed = new AtomicBoolean();
        when(warehouseRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<Warehouse> retired = invocation.getArgument(0);
            assertThat(retired).containsExactly(previousDefault);
            assertThat(previousDefault.getDefaultWarehouse()).isFalse();
            retiredDefaultFlushed.set(true);
            return List.of(previousDefault);
        });
        when(warehouseRepository.saveAndFlush(any(Warehouse.class))).thenAnswer(invocation -> {
            assertThat(retiredDefaultFlushed).isTrue();
            return invocation.getArgument(0);
        });

        WarehouseService service = warehouseService(warehouseRepository, stockBalanceRepository);
        WarehouseDTO request = warehouseRequest(7L, "WH-NEW", true);

        service.update(2L, request);

        var ordered = inOrder(warehouseRepository);
        ordered.verify(warehouseRepository).findAllForUpdate();
        ordered.verify(warehouseRepository).saveAllAndFlush(any());
        ordered.verify(warehouseRepository).saveAndFlush(argThat(warehouse ->
                Long.valueOf(2L).equals(warehouse.getId())
                        && Boolean.TRUE.equals(warehouse.getDefaultWarehouse())));
        verify(warehouseRepository, never()).findByIdForUpdate(2L);
        assertThat(previousDefault.getDefaultWarehouse()).isFalse();
        assertThat(replacement.getDefaultWarehouse()).isTrue();
    }

    @Test
    void updateRejectsDirectlyUnsettingTheOnlyDefaultWarehouse() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        Warehouse onlyDefault = warehouse(1L, 5L, "WH-MAIN", true);
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(onlyDefault));

        WarehouseService service = warehouseService(warehouseRepository, stockBalanceRepository);
        WarehouseDTO request = warehouseRequest(5L, "WH-MAIN", false);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("Default warehouse cannot be unset; set another warehouse as default instead");

        verify(warehouseRepository, never()).findAllForUpdate();
        verify(warehouseRepository, never()).saveAllAndFlush(any());
        verify(warehouseRepository, never()).saveAndFlush(any(Warehouse.class));
    }

    @Test
    void deleteRejectsWarehouseWithZeroBalanceHistory() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(9L);
        warehouse.setVersion(4L);
        warehouse.setDefaultWarehouse(false);
        StockBalance balance = new StockBalance();
        balance.setWarehouseId(9L);
        balance.setAvailableQuantity(0);
        balance.setReservedQuantity(0);
        balance.setLockedQuantity(0);
        when(warehouseRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(warehouse));
        when(machineRepository.countByWarehouseId(9L)).thenReturn(0L);
        when(partRepository.countByWarehouseId(9L)).thenReturn(0L);
        when(stockBalanceRepository.findByWarehouseId(9L)).thenReturn(List.of(balance));

        WarehouseService service = new WarehouseService();
        ReflectionTestUtils.setField(service, "warehouseRepository", warehouseRepository);
        ReflectionTestUtils.setField(service, "stockBalanceRepository", stockBalanceRepository);
        ReflectionTestUtils.setField(service, "machineInventoryRepository", machineRepository);
        ReflectionTestUtils.setField(service, "partInventoryRepository", partRepository);
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));

        assertThatThrownBy(() -> service.delete(9L, 4L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Warehouse has inventory ledger history and cannot be deleted");

        verify(warehouseRepository, never()).delete(any(Warehouse.class));
    }

    @Test
    void deleteRejectsWarehouseReferencedByDraftPurchaseOrder() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(12L);
        warehouse.setVersion(2L);
        warehouse.setDefaultWarehouse(false);
        when(warehouseRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(warehouse));
        when(machineRepository.countByWarehouseId(12L)).thenReturn(0L);
        when(partRepository.countByWarehouseId(12L)).thenReturn(0L);
        when(stockBalanceRepository.findByWarehouseId(12L)).thenReturn(List.of());
        when(purchaseOrderRepository.existsByWarehouseId(12L)).thenReturn(true);

        WarehouseService service = new WarehouseService();
        ReflectionTestUtils.setField(service, "warehouseRepository", warehouseRepository);
        ReflectionTestUtils.setField(service, "stockBalanceRepository", stockBalanceRepository);
        ReflectionTestUtils.setField(service, "machineInventoryRepository", machineRepository);
        ReflectionTestUtils.setField(service, "partInventoryRepository", partRepository);
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));

        assertThatThrownBy(() -> service.delete(12L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Warehouse is referenced by purchase orders and cannot be deleted");

        verify(warehouseRepository, never()).delete(any(Warehouse.class));
    }

    @Test
    void transferRejectsVehicleInActiveModificationBeforeMovingBalanceOrFifo() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StockLotService stockLotService = mock(StockLotService.class);
        MachineInventory machine = new MachineInventory();
        machine.setId(64L);
        machine.setVersion(3L);
        machine.setModelOnly(false);
        machine.setIsLocked(false);
        machine.setStockStatus(MachineStockStatus.MODIFYING.code());
        when(machineRepository.findByIdForUpdate(64L)).thenReturn(Optional.of(machine));

        WarehouseService service = new WarehouseService();
        ReflectionTestUtils.setField(service, "warehouseRepository", mock(WarehouseRepository.class));
        ReflectionTestUtils.setField(service, "stockBalanceRepository", mock(StockBalanceRepository.class));
        ReflectionTestUtils.setField(service, "machineInventoryRepository", machineRepository);
        ReflectionTestUtils.setField(service, "partInventoryRepository", mock(PartInventoryRepository.class));
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        ReflectionTestUtils.setField(service, "stockLotService", stockLotService);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());

        StockTransferDTO request = new StockTransferDTO();
        request.setResourceType(StockLedgerService.RESOURCE_MACHINE);
        request.setResourceId(64L);
        request.setFromWarehouseId(1L);
        request.setToWarehouseId(2L);
        request.setQuantity(1);
        request.setVersion(3L);

        assertThatThrownBy(() -> service.transfer(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("Vehicle status does not allow warehouse transfer: MODIFYING");

        verifyNoInteractions(stockLedgerService, stockLotService);
    }

    private WarehouseService warehouseService(
            WarehouseRepository warehouseRepository,
            StockBalanceRepository stockBalanceRepository
    ) {
        WarehouseService service = new WarehouseService();
        ReflectionTestUtils.setField(service, "warehouseRepository", warehouseRepository);
        ReflectionTestUtils.setField(service, "stockBalanceRepository", stockBalanceRepository);
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        return service;
    }

    private Warehouse warehouse(Long id, Long version, String code, boolean defaultWarehouse) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(id);
        warehouse.setVersion(version);
        warehouse.setWarehouseCode(code);
        warehouse.setWarehouseName(code);
        warehouse.setWarehouseType("MAIN");
        warehouse.setDefaultWarehouse(defaultWarehouse);
        return warehouse;
    }

    private WarehouseDTO warehouseRequest(Long version, String code, boolean defaultWarehouse) {
        WarehouseDTO request = new WarehouseDTO();
        request.setVersion(version);
        request.setWarehouseCode(code);
        request.setWarehouseName(code);
        request.setWarehouseType("MAIN");
        request.setDefaultWarehouse(defaultWarehouse);
        return request;
    }
}
