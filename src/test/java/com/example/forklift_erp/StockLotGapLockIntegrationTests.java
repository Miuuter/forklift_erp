package com.example.forklift_erp;

import com.example.forklift_erp.dto.PartInventoryCreateDTO;
import com.example.forklift_erp.dto.PartInventoryVO;
import com.example.forklift_erp.dto.StockTransferDTO;
import com.example.forklift_erp.dto.WarehouseDTO;
import com.example.forklift_erp.dto.WarehouseVO;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.service.PartInventoryService;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.WarehouseService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockLotGapLockIntegrationTests extends TestcontainersDatabaseSupport {

    private static final int CONCURRENT_PARTS = 12;

    @Autowired
    private PartInventoryService partInventoryService;

    @Autowired
    private PartInventoryRepository partInventoryRepository;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private StockBalanceRepository stockBalanceRepository;

    @RepeatedTest(3)
    void concurrentTransfersForAdjacentPartsDoNotDeadlockOnFifoGapLocks() throws Exception {
        Long sourceWarehouseId = defaultWarehouseId();
        WarehouseDTO warehouseRequest = new WarehouseDTO();
        warehouseRequest.setWarehouseCode("GAP-" + UUID.randomUUID().toString().substring(0, 8));
        warehouseRequest.setWarehouseName("FIFO gap-lock target");
        warehouseRequest.setWarehouseType("TEST");
        warehouseRequest.setDefaultWarehouse(false);
        WarehouseVO targetWarehouse = warehouseService.create(warehouseRequest);

        List<PartInventoryVO> parts = new ArrayList<>();
        for (int index = 0; index < CONCURRENT_PARTS; index += 1) {
            PartInventoryCreateDTO request = new PartInventoryCreateDTO();
            request.setPartCode("GAP-PART-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
            request.setPartName("FIFO gap-lock part " + index);
            request.setPartCategory("INTEGRATION_TEST");
            request.setQuantity(2);
            request.setReorderPoint(0);
            request.setWarehouseId(sourceWarehouseId);
            request.setUnit("pcs");
            request.setPurchasePrice(BigDecimal.ONE);
            parts.add(partInventoryService.create(request));
        }

        CountDownLatch workersReady = new CountDownLatch(parts.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(parts.size());
        try {
            List<CompletableFuture<Void>> transfers = parts.stream()
                    .map(part -> CompletableFuture.runAsync(() -> {
                        workersReady.countDown();
                        await(start);
                        StockTransferDTO request = new StockTransferDTO();
                        request.setResourceType(StockLedgerService.RESOURCE_PART);
                        request.setResourceId(part.getId());
                        request.setFromWarehouseId(sourceWarehouseId);
                        request.setToWarehouseId(targetWarehouse.getId());
                        request.setQuantity(2);
                        request.setVersion(part.getVersion());
                        request.setOperator("gap-lock-test");
                        warehouseService.transfer(request);
                    }, executor))
                    .toList();

            assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (CompletableFuture<Void> transfer : transfers) {
                transfer.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        for (PartInventoryVO part : parts) {
            assertThat(partInventoryRepository.findById(part.getId()).orElseThrow().getWarehouseId())
                    .isEqualTo(targetWarehouse.getId());
            assertThat(stockBalanceRepository.findByResourceTypeAndResourceIdAndWarehouseId(
                    StockLedgerService.RESOURCE_PART,
                    part.getId(),
                    sourceWarehouseId
            ).orElseThrow().getAvailableQuantity()).isZero();
            assertThat(stockBalanceRepository.findByResourceTypeAndResourceIdAndWarehouseId(
                    StockLedgerService.RESOURCE_PART,
                    part.getId(),
                    targetWarehouse.getId()
            ).orElseThrow().getAvailableQuantity()).isEqualTo(2);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent transfer start timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent transfer was interrupted", exception);
        }
    }
}
