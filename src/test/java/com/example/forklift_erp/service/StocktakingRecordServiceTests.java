package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.StocktakingRecordDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StocktakingRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.service.impl.StockOperationRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StocktakingRecordServiceTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsLockedMachine() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        StocktakingRecordRepository stocktakingRepository = mock(StocktakingRecordRepository.class);
        MachineInventory machine = machine(1L, true);
        when(machineRepository.findById(1L)).thenReturn(Optional.of(machine));
        StocktakingRecordService service = service(stocktakingRepository, machineRepository, mock(PartInventoryRepository.class));

        StocktakingRecordDTO request = request(StocktakingRecord.RESOURCE_MACHINE, 1L);

        assertForbidden(() -> service.create(request), "Vehicle is locked and cannot be stocktaken");
        verify(stocktakingRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsLockedPartTarget() {
        StocktakingRecordRepository stocktakingRepository = mock(StocktakingRecordRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        StocktakingRecord record = record(10L, StocktakingRecord.RESOURCE_MACHINE, 1L);
        when(stocktakingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(record));
        when(partRepository.findById(2L)).thenReturn(Optional.of(part(2L, true)));
        StocktakingRecordService service = service(stocktakingRepository, mock(MachineInventoryRepository.class), partRepository);

        StocktakingRecordDTO request = request(StocktakingRecord.RESOURCE_PART, 2L);

        assertForbidden(() -> service.update(10L, request), "Part is locked and cannot be stocktaken");
        verify(stocktakingRepository, never()).saveAndFlush(any());
    }

    @Test
    void completeRejectsMachineLockedAfterDraftWasCreated() {
        StocktakingRecordRepository stocktakingRepository = mock(StocktakingRecordRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        StocktakingRecord record = record(10L, StocktakingRecord.RESOURCE_MACHINE, 1L);
        when(stocktakingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(record));
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, true)));
        StocktakingRecordService service = service(stocktakingRepository, machineRepository, mock(PartInventoryRepository.class));

        assertForbidden(() -> service.complete(10L, 0L), "Vehicle is locked and cannot be stocktaken");
        verify(machineRepository, never()).saveAndFlush(any());
        verify(stocktakingRepository, never()).saveAndFlush(any());
    }

    @Test
    void completeRejectsPartLockedAfterDraftWasCreated() {
        StocktakingRecordRepository stocktakingRepository = mock(StocktakingRecordRepository.class);
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        StocktakingRecord record = record(10L, StocktakingRecord.RESOURCE_PART, 2L);
        when(stocktakingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(record));
        when(partRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(part(2L, true)));
        StocktakingRecordService service = service(stocktakingRepository, mock(MachineInventoryRepository.class), partRepository);

        assertForbidden(() -> service.complete(10L, 0L), "Part is locked and cannot be stocktaken");
        verify(partRepository, never()).saveAndFlush(any());
        verify(stocktakingRepository, never()).saveAndFlush(any());
    }

    @Test
    void zeroDifferenceStocktakeDoesNotCreateAnImplicitWarehouseReconciliation() {
        StocktakingRecordRepository stocktakingRepository = mock(StocktakingRecordRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        StocktakingRecord record = record(10L, StocktakingRecord.RESOURCE_MACHINE, 1L);
        record.setWarehouseId(7L);
        record.setBookBalanceVersion(0L);
        MachineInventory machine = machine(1L, false);
        machine.setWarehouseId(7L);
        when(stocktakingRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(record));
        when(stocktakingRepository.saveAndFlush(record)).thenReturn(record);
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine));
        StocktakingRecordService service = service(stocktakingRepository, machineRepository, mock(PartInventoryRepository.class));
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
        when(stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, 1L, 7L)).thenReturn(1);

        service.complete(10L, 0L);

        verify(stockLedgerService, never()).reconcileAvailableQuantity(
                StockLedgerService.RESOURCE_MACHINE, 1L, 7L, 1
        );
        verify(stockLedgerService, never()).recordMovement(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private StocktakingRecordService service(
            StocktakingRecordRepository stocktakingRepository,
            MachineInventoryRepository machineRepository,
            PartInventoryRepository partRepository
    ) {
        StocktakingRecordService service = new StocktakingRecordService();
        ReflectionTestUtils.setField(service, "stocktakingRepository", stocktakingRepository);
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        ReflectionTestUtils.setField(service, "stockOperationRecorder", mock(StockOperationRecorder.class));
        ReflectionTestUtils.setField(service, "stockLedgerService", mock(StockLedgerService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
        ReflectionTestUtils.setField(service, "stockBalanceRepository", mock(StockBalanceRepository.class));
        return service;
    }

    private StocktakingRecordDTO request(String resourceType, Long resourceId) {
        StocktakingRecordDTO request = new StocktakingRecordDTO();
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setActualQuantity(1);
        request.setVersion(0L);
        return request;
    }

    private StocktakingRecord record(Long id, String resourceType, Long resourceId) {
        StocktakingRecord record = new StocktakingRecord();
        record.setId(id);
        record.setVersion(0L);
        record.setStatus("DRAFT");
        record.setResourceType(resourceType);
        record.setResourceId(resourceId);
        record.setBookQuantity(1);
        record.setActualQuantity(1);
        return record;
    }

    private MachineInventory machine(Long id, boolean locked) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setIsLocked(locked);
        machine.setInventoryCount(1);
        return machine;
    }

    private PartInventory part(Long id, boolean locked) {
        PartInventory part = new PartInventory();
        part.setId(id);
        part.setIsLocked(locked);
        part.setQuantity(1);
        return part;
    }

    private void assertForbidden(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()))
                .hasMessage(message);
    }
}
