package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartInventoryServiceImplTests {

    @Test
    void saveRejectsMissingSourceMachineBeforePartPersistence() {
        PartInventoryRepository partRepository = mock(PartInventoryRepository.class);
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        when(partRepository.findByPartCode("REMOVED-001")).thenReturn(Optional.empty());
        when(machineRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        PartInventoryServiceImpl service = new PartInventoryServiceImpl();
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);

        PartInventory part = new PartInventory();
        part.setPartCode("REMOVED-001");
        part.setPartName("Removed tyre");
        part.setSourceMachineId(999L);
        part.setQuantity(0);
        part.setReorderPoint(5);

        assertThatThrownBy(() -> service.save(part))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.VEHICLE_NOT_FOUND.getCode()))
                .hasMessage("Source machine not found");

        verify(partRepository, never()).save(any(PartInventory.class));
    }
}
