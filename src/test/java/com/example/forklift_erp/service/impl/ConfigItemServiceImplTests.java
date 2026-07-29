package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineConfigRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.VehicleConfigValueRepository;
import com.example.forklift_erp.service.CollaborationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigItemServiceImplTests {

    @Test
    void deleteByIdValidatesExpectedVersionInsideServiceTransaction() {
        Fixture fixture = new Fixture();
        ConfigItem item = item(10L, 4L);
        when(fixture.configItemRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(item));
        when(fixture.machineConfigRepository.findByConfigItemId(10L)).thenReturn(List.of());
        when(fixture.vehicleConfigValueRepository.existsByConfigItemId(10L)).thenReturn(false);

        fixture.service.deleteById(10L, 4L);

        verify(fixture.collaborationService).validateWrite(item, 4L);
        verify(fixture.configValueRepository).deleteByConfigItemId(10L);
        verify(fixture.configItemRepository).deleteById(10L);
    }

    @Test
    void deleteByIdRejectsPurchaseHistoryBeforeDeletingValues() {
        Fixture fixture = new Fixture();
        ConfigItem item = item(10L, 4L);
        when(fixture.configItemRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(item));
        when(fixture.machineConfigRepository.findByConfigItemId(10L)).thenReturn(List.of());
        when(fixture.vehicleConfigValueRepository.existsByConfigItemId(10L)).thenReturn(false);
        when(fixture.configValueRepository.findByConfigItemIdOrderBySortOrderAsc(10L))
                .thenReturn(List.of());
        when(fixture.purchaseOrderRepository.existsByConfigItemId(10L)).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.deleteById(10L, 4L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("purchase or modification history");

        verify(fixture.configValueRepository, never()).deleteByConfigItemId(10L);
        verify(fixture.configItemRepository, never()).deleteById(10L);
    }

    @Test
    void firstValueBecomesDefaultEvenWhenCallerDoesNotMarkIt() {
        Fixture fixture = new Fixture();
        ConfigValue requested = value(null, 10L, false, "Pneumatic");
        fixture.stubItemAndValues(10L, List.of());
        fixture.returnSavedArgument();

        ConfigValue saved = fixture.service.saveValue(requested);

        assertThat(saved).isSameAs(requested);
        assertThat(requested.getIsDefault()).isTrue();
        InOrder ordered = inOrder(fixture.configItemRepository, fixture.configValueRepository);
        ordered.verify(fixture.configItemRepository).findByIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).findByConfigItemIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).saveAndFlush(same(requested));
    }

    @Test
    void switchingDefaultFlushesOldDefaultBeforeSavingNewDefault() {
        Fixture fixture = new Fixture();
        ConfigValue previousDefault = value(11L, 10L, true, "Pneumatic");
        ConfigValue requested = value(null, 10L, true, "Solid");
        fixture.stubItemAndValues(10L, List.of(previousDefault));
        fixture.returnSavedArgument();

        fixture.service.saveValue(requested);

        assertThat(previousDefault.getIsDefault()).isFalse();
        assertThat(requested.getIsDefault()).isTrue();
        InOrder ordered = inOrder(fixture.configItemRepository, fixture.configValueRepository);
        ordered.verify(fixture.configItemRepository).findByIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).findByConfigItemIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).saveAndFlush(same(previousDefault));
        ordered.verify(fixture.configValueRepository).saveAndFlush(same(requested));
    }

    @Test
    void promotingExistingValueUpdatesLockedEntityAfterDemotingOldDefault() {
        Fixture fixture = new Fixture();
        ConfigValue previousDefault = value(11L, 10L, true, "Pneumatic");
        ConfigValue current = value(12L, 10L, false, "Solid");
        current.setVersion(3L);
        ConfigValue requested = value(12L, 10L, true, "Solid tyre");
        requested.setVersion(3L);
        requested.setValueCode("SOLID");
        requested.setSortOrder(8);
        requested.setRemark("updated");
        when(fixture.configValueRepository.findConfigItemIdById(12L)).thenReturn(Optional.of(10L));
        fixture.stubItemAndValues(10L, List.of(previousDefault, current));
        fixture.returnSavedArgument();

        ConfigValue saved = fixture.service.saveValue(requested);

        assertThat(saved).isSameAs(current);
        assertThat(previousDefault.getIsDefault()).isFalse();
        assertThat(current.getIsDefault()).isTrue();
        assertThat(current.getValueLabel()).isEqualTo("Solid tyre");
        assertThat(current.getValueCode()).isEqualTo("SOLID");
        assertThat(current.getSortOrder()).isEqualTo(8);
        assertThat(current.getRemark()).isEqualTo("updated");
        verify(fixture.collaborationService).validateWrite(current, 3L);
        verify(fixture.configValueRepository, never()).saveAndFlush(same(requested));
        InOrder ordered = inOrder(fixture.configItemRepository, fixture.configValueRepository);
        ordered.verify(fixture.configValueRepository).findConfigItemIdById(12L);
        ordered.verify(fixture.configItemRepository).findByIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).findByConfigItemIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).saveAndFlush(same(previousDefault));
        ordered.verify(fixture.configValueRepository).saveAndFlush(same(current));
    }

    @Test
    void updateCannotMoveValueToAnotherConfigItem() {
        Fixture fixture = new Fixture();
        ConfigValue requested = value(12L, 20L, false, "Solid");
        when(fixture.configValueRepository.findConfigItemIdById(12L)).thenReturn(Optional.of(10L));

        assertThatThrownBy(() -> fixture.service.saveValue(requested))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be moved");

        verify(fixture.configItemRepository, never()).findByIdForUpdate(any());
        verify(fixture.configValueRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateCannotClearCurrentDefaultWithoutReplacement() {
        Fixture fixture = new Fixture();
        ConfigValue current = value(11L, 10L, true, "Pneumatic");
        current.setVersion(2L);
        ConfigValue requested = value(11L, 10L, false, "Pneumatic");
        requested.setVersion(2L);
        when(fixture.configValueRepository.findConfigItemIdById(11L)).thenReturn(Optional.of(10L));
        fixture.stubItemAndValues(10L, List.of(current));

        assertThatThrownBy(() -> fixture.service.saveValue(requested))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be cleared");

        verify(fixture.collaborationService).validateWrite(current, 2L);
        verify(fixture.configValueRepository, never()).saveAndFlush(any());
    }

    @Test
    void deletingCurrentDefaultRequiresReplacementWhenOtherValuesRemain() {
        Fixture fixture = new Fixture();
        ConfigValue currentDefault = value(11L, 10L, true, "Pneumatic");
        ConfigValue other = value(12L, 10L, false, "Solid");
        when(fixture.configValueRepository.findConfigItemIdById(11L)).thenReturn(Optional.of(10L));
        fixture.stubItemAndValues(10L, List.of(currentDefault, other));
        when(fixture.machineConfigRepository.findByConfigValueId(11L)).thenReturn(List.of());
        when(fixture.vehicleConfigValueRepository.existsByConfigValueId(11L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.deleteValueById(11L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Assign another default");

        verify(fixture.collaborationService).validateWrite(currentDefault, 1L);
        verify(fixture.configValueRepository, never()).delete(any(ConfigValue.class));
    }

    @Test
    void deletingNonDefaultUsesParentThenSiblingLockOrder() {
        Fixture fixture = new Fixture();
        ConfigValue currentDefault = value(11L, 10L, true, "Pneumatic");
        ConfigValue deleted = value(12L, 10L, false, "Solid");
        deleted.setVersion(4L);
        when(fixture.configValueRepository.findConfigItemIdById(12L)).thenReturn(Optional.of(10L));
        fixture.stubItemAndValues(10L, List.of(currentDefault, deleted));
        when(fixture.machineConfigRepository.findByConfigValueId(12L)).thenReturn(List.of());
        when(fixture.vehicleConfigValueRepository.existsByConfigValueId(12L)).thenReturn(false);

        fixture.service.deleteValueById(12L, 4L);

        verify(fixture.collaborationService).validateWrite(deleted, 4L);
        verify(fixture.configValueRepository).delete(same(deleted));
        InOrder ordered = inOrder(fixture.configItemRepository, fixture.configValueRepository);
        ordered.verify(fixture.configValueRepository).findConfigItemIdById(12L);
        ordered.verify(fixture.configItemRepository).findByIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).findByConfigItemIdForUpdate(10L);
        ordered.verify(fixture.configValueRepository).delete(same(deleted));
    }

    @Test
    void deletingValueRejectsModificationHistory() {
        Fixture fixture = new Fixture();
        ConfigValue deleted = value(12L, 10L, false, "Solid");
        when(fixture.configValueRepository.findConfigItemIdById(12L)).thenReturn(Optional.of(10L));
        fixture.stubItemAndValues(10L, List.of(value(11L, 10L, true, "Pneumatic"), deleted));
        when(fixture.machineConfigRepository.findByConfigValueId(12L)).thenReturn(List.of());
        when(fixture.vehicleConfigValueRepository.existsByConfigValueId(12L)).thenReturn(false);
        when(fixture.modificationWorkOrderLineRepository.existsByNewConfigValueId(12L)).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.deleteValueById(12L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("purchase or modification history");

        verify(fixture.configValueRepository, never()).delete(any(ConfigValue.class));
    }

    private static ConfigItem item(Long id, Long version) {
        ConfigItem item = new ConfigItem();
        item.setId(id);
        item.setVersion(version);
        return item;
    }

    private static ConfigValue value(Long id, Long configItemId, boolean isDefault, String label) {
        ConfigValue value = new ConfigValue();
        value.setId(id);
        value.setVersion(1L);
        value.setConfigItemId(configItemId);
        value.setValueLabel(label);
        value.setIsDefault(isDefault);
        value.setSortOrder(0);
        return value;
    }

    private static final class Fixture {
        private final ConfigItemRepository configItemRepository = mock(ConfigItemRepository.class);
        private final ConfigValueRepository configValueRepository = mock(ConfigValueRepository.class);
        private final CollaborationService collaborationService = mock(CollaborationService.class);
        private final MachineConfigRepository machineConfigRepository = mock(MachineConfigRepository.class);
        private final VehicleConfigValueRepository vehicleConfigValueRepository = mock(VehicleConfigValueRepository.class);
        private final PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository =
                mock(ModificationWorkOrderLineRepository.class);
        private final ConfigItemServiceImpl service = new ConfigItemServiceImpl(
                configItemRepository,
                configValueRepository,
                collaborationService,
                machineConfigRepository,
                vehicleConfigValueRepository,
                purchaseOrderRepository,
                modificationWorkOrderLineRepository
        );

        private void stubItemAndValues(Long configItemId, List<ConfigValue> values) {
            when(configItemRepository.findByIdForUpdate(configItemId))
                    .thenReturn(Optional.of(item(configItemId, 1L)));
            when(configValueRepository.findByConfigItemIdForUpdate(configItemId)).thenReturn(values);
        }

        private void returnSavedArgument() {
            when(configValueRepository.saveAndFlush(any(ConfigValue.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }
    }
}
