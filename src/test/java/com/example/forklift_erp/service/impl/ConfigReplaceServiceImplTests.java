package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.ConfigReplaceRequestDTO;
import com.example.forklift_erp.dto.PartReplaceRequestDTO;
import com.example.forklift_erp.dto.VehiclePartInstallRequestDTO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineConfig;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigReplaceServiceImplTests {

    private MachineInventoryRepository machineRepository;
    private MachineConfigRepository machineConfigRepository;
    private PartInventoryRepository partRepository;
    private ConfigItemRepository configItemRepository;
    private ConfigValueRepository configValueRepository;
    private ConfigReplaceServiceImpl service;

    @BeforeEach
    void setUp() {
        machineRepository = mock(MachineInventoryRepository.class);
        machineConfigRepository = mock(MachineConfigRepository.class);
        partRepository = mock(PartInventoryRepository.class);
        configItemRepository = mock(ConfigItemRepository.class);
        configValueRepository = mock(ConfigValueRepository.class);
        service = new ConfigReplaceServiceImpl();
        ReflectionTestUtils.setField(service, "machineRepository", machineRepository);
        ReflectionTestUtils.setField(service, "machineConfigRepository", machineConfigRepository);
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "configItemRepository", configItemRepository);
        ReflectionTestUtils.setField(service, "configValueRepository", configValueRepository);
        ReflectionTestUtils.setField(service, "collaborationService", mock(CollaborationService.class));
        ReflectionTestUtils.setField(service, "visibilityPolicy", new ResourceVisibilityPolicy());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void configReplaceRejectsLockedMachine() {
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, true)));
        ConfigReplaceRequestDTO request = new ConfigReplaceRequestDTO();
        request.setMachineId(1L);

        assertForbidden(() -> service.performReplace(request), "Vehicle is locked and cannot be modified");
    }

    @Test
    void partReplaceRejectsLockedPart() {
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(machineConfigRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(config(2L, 1L)));
        when(partRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(part(3L, true)));
        PartReplaceRequestDTO request = new PartReplaceRequestDTO();
        request.setMachineId(1L);
        request.setMachineConfigId(2L);
        request.setNewPartId(3L);

        assertForbidden(() -> service.performPartReplace(request), "Part is locked and cannot be used for replacement");
    }

    @Test
    void partInstallRejectsLockedPart() {
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configItemRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(configItem(2L)));
        when(partRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(part(3L, true)));
        VehiclePartInstallRequestDTO request = new VehiclePartInstallRequestDTO();
        request.setMachineId(1L);
        request.setConfigItemId(2L);
        request.setNewPartId(3L);

        assertForbidden(() -> service.performPartInstall(request), "Part is locked and cannot be installed");
    }

    @Test
    void configReplaceRejectsValueFromAnotherConfigItem() {
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configItemRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(configItem(2L)));
        ConfigValue value = new ConfigValue();
        value.setId(3L);
        value.setConfigItemId(99L);
        when(configValueRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(value));
        ConfigReplaceRequestDTO request = new ConfigReplaceRequestDTO();
        request.setMachineId(1L);
        request.setConfigItemId(2L);
        request.setNewConfigValueId(3L);

        assertThatThrownBy(() -> service.performReplace(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()))
                .hasMessage("Config value does not belong to selected config item");

        verify(machineConfigRepository, never())
                .findByMachineIdAndConfigItemIdForUpdate(1L, 2L);
    }

    @Test
    void partInstallRejectsExistingConfigBeforeLockingOrConsumingPart() {
        when(machineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(machine(1L, false)));
        when(configItemRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(configItem(2L)));
        when(machineConfigRepository.findByMachineIdAndConfigItemIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(config(5L, 1L)));
        VehiclePartInstallRequestDTO request = new VehiclePartInstallRequestDTO();
        request.setMachineId(1L);
        request.setConfigItemId(2L);
        request.setNewPartId(3L);

        assertThatThrownBy(() -> service.performPartInstall(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessageContaining("use replacement instead");

        verify(partRepository, never()).findByIdForUpdate(3L);
    }

    private MachineInventory machine(Long id, boolean locked) {
        MachineInventory machine = new MachineInventory();
        machine.setId(id);
        machine.setIsLocked(locked);
        return machine;
    }

    private MachineConfig config(Long id, Long machineId) {
        MachineConfig config = new MachineConfig();
        config.setId(id);
        config.setMachineId(machineId);
        config.setSelectedValue("Old part");
        return config;
    }

    private PartInventory part(Long id, boolean locked) {
        PartInventory part = new PartInventory();
        part.setId(id);
        part.setIsLocked(locked);
        return part;
    }

    private ConfigItem configItem(Long id) {
        ConfigItem item = new ConfigItem();
        item.setId(id);
        return item;
    }

    private void assertForbidden(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()))
                .hasMessage(message);
    }
}
