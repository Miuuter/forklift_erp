package com.example.forklift_erp.config;

import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.Warehouse;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoDataInitializerDefaultTests {

    @Test
    void warehouseSeedFlushesRetiredDefaultBeforePromotingCanonicalWarehouse() {
        WarehouseRepository repository = mock(WarehouseRepository.class);
        Warehouse canonical = warehouse(1L, "DEFAULT", false);
        Warehouse previousDefault = warehouse(2L, "WH-SH", true);
        when(repository.findAllForUpdate()).thenReturn(List.of(canonical, previousDefault));

        AtomicBoolean retiredDefaultFlushed = new AtomicBoolean();
        when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<Warehouse> rows = invocation.getArgument(0);
            assertThat(rows).containsExactly(previousDefault);
            assertThat(previousDefault.getDefaultWarehouse()).isFalse();
            retiredDefaultFlushed.set(true);
            return List.of(previousDefault);
        });
        when(repository.saveAndFlush(any(Warehouse.class))).thenAnswer(invocation -> {
            Warehouse saved = invocation.getArgument(0);
            if ("DEFAULT".equals(saved.getWarehouseCode())) {
                assertThat(retiredDefaultFlushed).isTrue();
                assertThat(saved.getDefaultWarehouse()).isTrue();
            }
            return saved;
        });

        DemoDataInitializer initializer = new DemoDataInitializer();
        ReflectionTestUtils.setField(initializer, "warehouseRepository", repository);

        ReflectionTestUtils.invokeMethod(initializer, "ensureWarehouses");

        assertThat(canonical.getDefaultWarehouse()).isTrue();
        assertThat(previousDefault.getDefaultWarehouse()).isFalse();
    }

    @Test
    void configSeedFlushesRetiredDefaultBeforePromotingCanonicalValue() {
        ConfigItemRepository itemRepository = mock(ConfigItemRepository.class);
        ConfigValueRepository valueRepository = mock(ConfigValueRepository.class);
        AtomicLong nextItemId = new AtomicLong(1);
        Map<Long, ConfigItem> items = new LinkedHashMap<>();
        when(itemRepository.findByItemCode(anyString())).thenReturn(Optional.empty());
        when(itemRepository.saveAndFlush(any(ConfigItem.class))).thenAnswer(invocation -> {
            ConfigItem item = invocation.getArgument(0);
            item.setId(nextItemId.getAndIncrement());
            items.put(item.getId(), item);
            return item;
        });
        when(itemRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(items.get(invocation.<Long>getArgument(0))));

        ConfigValue staleDefault = new ConfigValue();
        staleDefault.setId(900L);
        staleDefault.setConfigItemId(1L);
        staleDefault.setValueCode("OLD_DEFAULT");
        staleDefault.setValueLabel("Old default");
        staleDefault.setIsDefault(true);
        when(valueRepository.findByConfigItemIdForUpdate(anyLong())).thenAnswer(invocation ->
                Long.valueOf(1L).equals(invocation.<Long>getArgument(0))
                        ? List.of(staleDefault)
                        : List.of());

        AtomicBoolean retiredDefaultFlushed = new AtomicBoolean();
        when(valueRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<ConfigValue> rows = invocation.getArgument(0);
            assertThat(rows).containsExactly(staleDefault);
            assertThat(staleDefault.getIsDefault()).isFalse();
            retiredDefaultFlushed.set(true);
            return List.of(staleDefault);
        });
        when(valueRepository.saveAndFlush(any(ConfigValue.class))).thenAnswer(invocation -> {
            ConfigValue saved = invocation.getArgument(0);
            if (Long.valueOf(1L).equals(saved.getConfigItemId())
                    && Boolean.TRUE.equals(saved.getIsDefault())) {
                assertThat(retiredDefaultFlushed).isTrue();
            }
            return saved;
        });

        DemoDataInitializer initializer = new DemoDataInitializer();
        ReflectionTestUtils.setField(initializer, "configItemRepository", itemRepository);
        ReflectionTestUtils.setField(initializer, "configValueRepository", valueRepository);

        ReflectionTestUtils.invokeMethod(initializer, "ensureConfigCatalog");

        assertThat(staleDefault.getIsDefault()).isFalse();
        assertThat(retiredDefaultFlushed).isTrue();
    }

    private Warehouse warehouse(Long id, String code, boolean isDefault) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(id);
        warehouse.setWarehouseCode(code);
        warehouse.setWarehouseName(code);
        warehouse.setWarehouseType("MAIN");
        warehouse.setDefaultWarehouse(isDefault);
        return warehouse;
    }
}
