package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.dto.InboundRequestDTO;
import com.example.forklift_erp.dto.MachineConfigVO;
import com.example.forklift_erp.dto.MachineInventoryCreateDTO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.MachineConfigService;
import com.example.forklift_erp.service.OperationAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MachineInventoryServiceImplTests {

    @Test
    void inStockStatusDoesNotInventAvailableVehicle() {
        MachineInventory machine = new MachineInventory();
        machine.setModelOnly(false);
        machine.setStockStatus(MachineStockStatus.IN_STOCK.code());
        machine.setInventoryCount(0);

        MachineInventoryServiceImpl.normalizeAvailableStock(machine);

        assertThat(machine.getInventoryCount()).isZero();
    }

    @Test
    void modelOnlyRecordStaysOutOfAvailableStock() {
        MachineInventory machine = new MachineInventory();
        machine.setModelOnly(true);
        machine.setStockStatus(MachineStockStatus.IN_STOCK.code());
        machine.setInventoryCount(0);

        MachineInventoryServiceImpl.normalizeAvailableStock(machine);

        assertThat(machine.getInventoryCount()).isZero();
    }

    @Test
    void saveRejectsNegativeInventoryCountBeforePersistence() {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("CPD-NEGATIVE-001");
        machine.setName("Forklift");
        machine.setSpecificationModel("CPD30");
        machine.setInventoryCount(-1);

        MachineInventoryServiceImpl service = new MachineInventoryServiceImpl();

        assertThatThrownBy(() -> service.save(machine))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Inventory count cannot be negative");
    }

    @Test
    void saveRejectsMultipleUnitsForASerializedVehicleBeforePersistence() {
        MachineInventory machine = new MachineInventory();
        machine.setVehicleProductNumber("CPD-SERIAL-002");
        machine.setName("Forklift");
        machine.setSpecificationModel("CPD30");
        machine.setInventoryCount(2);

        MachineInventoryServiceImpl service = new MachineInventoryServiceImpl();

        assertThatThrownBy(() -> service.save(machine))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("A concrete vehicle is serialized and can only be created with zero or one unit of stock");
    }

    @Test
    void inboundRejectsDuplicateConfigItemsBeforeCreatingVehicle() {
        InboundRequestDTO.ConfigSelection first = new InboundRequestDTO.ConfigSelection();
        first.setConfigItemId(10L);
        first.setConfigValueId(100L);
        InboundRequestDTO.ConfigSelection duplicate = new InboundRequestDTO.ConfigSelection();
        duplicate.setConfigItemId(10L);
        duplicate.setConfigValueId(101L);
        InboundRequestDTO request = new InboundRequestDTO();
        request.setMachineInventory(new MachineInventoryCreateDTO());
        request.setConfigs(List.of(first, duplicate));

        MachineInventoryServiceImpl service = new MachineInventoryServiceImpl();

        assertThatThrownBy(() -> service.inbound(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Each inbound configuration item must be selected exactly once");
    }

    @Test
    void updateConfigsPreservesExistingIdentityInsteadOfDeleteAndReinsert() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository machineConfigRepository = mock(MachineConfigRepository.class);
        MachineConfigService machineConfigService = mock(MachineConfigService.class);
        ConfigItemRepository itemRepository = mock(ConfigItemRepository.class);
        ConfigValueRepository valueRepository = mock(ConfigValueRepository.class);
        ModificationWorkOrderLineRepository lineRepository = mock(ModificationWorkOrderLineRepository.class);
        CollaborationService collaborationService = mock(CollaborationService.class);

        MachineInventory machine = new MachineInventory();
        machine.setId(1L);
        machine.setVersion(7L);
        MachineConfig existing = new MachineConfig();
        existing.setId(50L);
        existing.setVersion(3L);
        existing.setMachineId(1L);
        existing.setConfigItemId(10L);
        existing.setConfigValueId(100L);
        existing.setInstalledDate(java.time.LocalDateTime.of(2025, 1, 1, 8, 0));
        ConfigItem item = new ConfigItem();
        item.setId(10L);
        item.setItemName("Tyre");
        ConfigValue replacement = new ConfigValue();
        replacement.setId(101L);
        replacement.setConfigItemId(10L);
        replacement.setValueLabel("Solid tyre");

        when(machineRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(machine));
        when(machineRepository.findByIdAndIsLockedFalseForUpdate(1L))
                .thenReturn(java.util.Optional.of(machine));
        when(machineConfigRepository.findByMachineIdForUpdate(1L)).thenReturn(List.of(existing));
        when(itemRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(item));
        when(valueRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(replacement));
        when(machineConfigService.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MachineInventoryServiceImpl service = spy(new MachineInventoryServiceImpl());
        ReflectionTestUtils.setField(service, "repository", machineRepository);
        ReflectionTestUtils.setField(service, "machineConfigUpdateCoordinator", new MachineConfigUpdateCoordinator(
                machineConfigRepository,
                itemRepository,
                valueRepository,
                lineRepository,
                mock(ConfigReplaceLogRepository.class),
                machineConfigService
        ));
        ReflectionTestUtils.setField(service, "collaborationService", collaborationService);
        ReflectionTestUtils.setField(service, "operationAuditService", mock(OperationAuditService.class));
        doReturn(machine).when(service).save(machine);

        MachineConfigVO requested = new MachineConfigVO();
        requested.setId(50L);
        requested.setConfigItemId(10L);
        requested.setConfigValueId(101L);
        requested.setConfigSource("WAREHOUSE");

        List<MachineConfigVO> result = service.updateConfigs(1L, 7L, List.of(requested));

        assertThat(result).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(50L);
            assertThat(saved.getConfigValueId()).isEqualTo(101L);
            assertThat(saved.getInstalledDate())
                    .isEqualTo(java.time.LocalDateTime.of(2025, 1, 1, 8, 0));
        });
        assertThat(existing.getSelectedValue()).isEqualTo("Solid tyre");
        verify(machineConfigService).saveAll(List.of(existing));
        verify(machineConfigRepository, never()).deleteAll(any());
    }

    @Test
    void updateConfigsRejectsRemovingConfigWithModificationHistory() {
        MachineInventoryRepository machineRepository = mock(MachineInventoryRepository.class);
        MachineConfigRepository machineConfigRepository = mock(MachineConfigRepository.class);
        ModificationWorkOrderLineRepository lineRepository = mock(ModificationWorkOrderLineRepository.class);
        MachineInventory machine = new MachineInventory();
        machine.setId(1L);
        machine.setVersion(7L);
        MachineConfig existing = new MachineConfig();
        existing.setId(50L);
        existing.setMachineId(1L);
        existing.setConfigItemId(10L);
        existing.setItemName("Tyre");
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(machine));
        when(machineRepository.findByIdAndIsLockedFalseForUpdate(1L))
                .thenReturn(java.util.Optional.of(machine));
        when(machineConfigRepository.findByMachineIdForUpdate(1L)).thenReturn(List.of(existing));
        when(lineRepository.existsByMachineConfigId(50L)).thenReturn(true);

        MachineInventoryServiceImpl service = new MachineInventoryServiceImpl();
        ReflectionTestUtils.setField(service, "repository", machineRepository);
        ReflectionTestUtils.setField(service, "machineConfigUpdateCoordinator", new MachineConfigUpdateCoordinator(
                machineConfigRepository,
                mock(ConfigItemRepository.class),
                mock(ConfigValueRepository.class),
                lineRepository,
                mock(ConfigReplaceLogRepository.class),
                mock(MachineConfigService.class)
        ));
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));

        assertThatThrownBy(() -> service.updateConfigs(1L, 7L, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessageContaining("modification history");

        verify(machineConfigRepository, never()).deleteAll(any());
    }
}
