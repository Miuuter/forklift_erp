package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.RentalRecordCreateDTO;
import com.example.forklift_erp.dto.RentalRecordUpdateDTO;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.RentalBill;
import com.example.forklift_erp.entity.RentalRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.StockLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RentalRecordServiceImplTests {

    private RentalRecordRepository rentalRecordRepository;
    private RentalBillRepository rentalBillRepository;
    private MachineInventoryRepository machineRepository;
    private CollaborationService collaborationService;
    private OperationAuditService operationAuditService;
    private StockLedgerService stockLedgerService;
    private RentalRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        rentalRecordRepository = mock(RentalRecordRepository.class);
        rentalBillRepository = mock(RentalBillRepository.class);
        machineRepository = mock(MachineInventoryRepository.class);
        collaborationService = mock(CollaborationService.class);
        operationAuditService = mock(OperationAuditService.class);
        stockLedgerService = mock(StockLedgerService.class);
        service = new RentalRecordServiceImpl();
        ReflectionTestUtils.setField(service, "rentalRecordRepository", rentalRecordRepository);
        ReflectionTestUtils.setField(service, "rentalBillRepository", rentalBillRepository);
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);
        ReflectionTestUtils.setField(service, "collaborationService", collaborationService);
        ReflectionTestUtils.setField(service, "operationAuditService", operationAuditService);
        ReflectionTestUtils.setField(service, "stockLedgerService", stockLedgerService);
    }

    @Test
    void deleteRejectsActiveRental() {
        RentalRecord record = rental(12L, 3L, RentalRecord.STATUS_ACTIVE);
        when(rentalRecordRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.delete(12L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("进行中的租赁记录不能删除，请先办理归还")
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ResultCode.CONFLICT.getCode());

        verify(collaborationService).validateWrite(record, 3L);
        verify(rentalRecordRepository, never()).delete(any(RentalRecord.class));
        verifyNoInteractions(operationAuditService);
    }

    @Test
    void deleteRemovesReturnedRental() {
        RentalRecord record = rental(13L, 4L, RentalRecord.STATUS_RETURNED);
        when(rentalRecordRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(record));

        service.delete(13L, 4L);

        verify(collaborationService).validateWrite(record, 4L);
        verify(rentalRecordRepository).delete(record);
        verify(operationAuditService).record(
                "租赁管理", "DELETE", "RENTAL_RECORD", 13L,
                "RT-013", "CPD-013", "删除车辆租赁记录",
                "legacy-operator", "returned", "RENTAL_RECORD", 13L
        );
    }

    @Test
    void deleteRejectsReturnedRentalWithPostedBills() {
        RentalRecord record = rental(17L, 8L, RentalRecord.STATUS_RETURNED);
        RentalBill bill = new RentalBill();
        bill.setId(91L);
        when(rentalRecordRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(record));
        when(rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(17L)).thenReturn(List.of(bill));

        assertThatThrownBy(() -> service.delete(17L, 8L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("已生成租赁账单的记录不能删除，以免破坏应收和收款历史");

        verify(rentalRecordRepository, never()).delete(any(RentalRecord.class));
        verifyNoInteractions(operationAuditService);
    }

    @Test
    void updateRejectsReactivatingRentalForLockedMachine() {
        RentalRecord record = rental(14L, 5L, RentalRecord.STATUS_RETURNED);
        record.setMachineId(50L);
        MachineInventory machine = machine(50L, true);
        when(rentalRecordRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(record));
        when(machineRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(machine));

        RentalRecordUpdateDTO request = updateRequest(5L);
        assertThatThrownBy(() -> service.update(14L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ResultCode.VEHICLE_NOT_FOUND.getCode());

        verify(rentalRecordRepository, never()).saveAndFlush(any(RentalRecord.class));
        verifyNoInteractions(operationAuditService);
    }

    @Test
    void updateRejectsReactivatingRentalWhenAnotherRentalIsActive() {
        RentalRecord record = rental(15L, 6L, RentalRecord.STATUS_RETURNED);
        record.setMachineId(51L);
        MachineInventory machine = machine(51L, false);
        when(rentalRecordRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(record));
        when(machineRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(machine));
        when(rentalRecordRepository.existsByMachineIdAndStatus(51L, RentalRecord.STATUS_ACTIVE)).thenReturn(true);

        RentalRecordUpdateDTO request = updateRequest(6L);
        assertThatThrownBy(() -> service.update(15L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ResultCode.CONFLICT.getCode());

        verify(rentalRecordRepository, never()).saveAndFlush(any(RentalRecord.class));
        verifyNoInteractions(operationAuditService);
    }

    @Test
    void updateRejectsReactivatingRentalThatAlreadyHasPostedBills() {
        RentalRecord record = rental(16L, 7L, RentalRecord.STATUS_RETURNED);
        record.setMachineId(52L);
        record.setWarehouseId(1L);
        MachineInventory machine = machine(52L, false);
        RentalBill bill = new RentalBill();
        bill.setId(1L);
        when(rentalRecordRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(record));
        when(machineRepository.findByIdForUpdate(52L)).thenReturn(Optional.of(machine));
        when(rentalRecordRepository.existsByMachineIdAndStatus(52L, RentalRecord.STATUS_ACTIVE)).thenReturn(false);
        when(rentalBillRepository.findByRentalIdOrderByBillPeriodAsc(16L)).thenReturn(List.of(bill));

        assertThatThrownBy(() -> service.update(16L, updateRequest(7L)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ResultCode.CONFLICT.getCode());

        verify(rentalRecordRepository, never()).saveAndFlush(any(RentalRecord.class));
        verifyNoInteractions(operationAuditService);
    }

    @Test
    void createRejectsActiveModificationEvenWhenWarehouseHasAvailableQuantity() {
        MachineInventory machine = machine(53L, false);
        machine.setWarehouseId(8L);
        machine.setStockStatus(MachineStockStatus.PENDING_MODIFICATION.code());
        when(machineRepository.findByIdForUpdate(53L)).thenReturn(Optional.of(machine));
        when(stockLedgerService.resolveWarehouseId(8L)).thenReturn(8L);
        when(stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, 53L, 8L))
                .thenReturn(1);
        RentalRecordCreateDTO request = new RentalRecordCreateDTO();
        request.setMachineId(53L);
        request.setMachineVersion(0L);
        request.setWarehouseId(8L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessage("Vehicle status does not allow rental: PENDING_MODIFICATION");

        verify(rentalRecordRepository, never()).saveAndFlush(any(RentalRecord.class));
        verify(stockLedgerService, never()).freezeForRental(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()
        );
    }

    private RentalRecordUpdateDTO updateRequest(Long version) {
        RentalRecordUpdateDTO request = new RentalRecordUpdateDTO();
        request.setVersion(version);
        request.setStatus(RentalRecord.STATUS_ACTIVE);
        return request;
    }

    private MachineInventory machine(Long id, boolean locked) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setIsLocked(locked);
        machine.setModelOnly(false);
        machine.setInventoryCount(1);
        machine.setStockStatus(MachineStockStatus.IN_STOCK.code());
        return machine;
    }

    private RentalRecord rental(Long id, Long version, String status) {
        RentalRecord record = new RentalRecord();
        record.setId(id);
        record.setVersion(version);
        record.setStatus(status);
        record.setRentalNo("RT-0" + id);
        record.setVehicleNumber("CPD-0" + id);
        record.setOperator("legacy-operator");
        record.setRemark("returned");
        return record;
    }
}
