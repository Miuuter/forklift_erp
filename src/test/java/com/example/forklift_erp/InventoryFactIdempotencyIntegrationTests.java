package com.example.forklift_erp;

import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockBalance;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.StockBalanceRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import com.example.forklift_erp.repository.StockLotCostAdjustmentRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "forklift.seed-demo-data.enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class InventoryFactIdempotencyIntegrationTests extends TestcontainersDatabaseSupport {
    private static final int CONCURRENCY = 8;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private PartInventoryRepository partRepository;

    @Autowired
    private StockBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private StockMovementLineRepository movementLineRepository;

    @Autowired
    private StockLotRepository lotRepository;

    @Autowired
    private StockLotConsumptionRepository consumptionRepository;

    @Autowired
    private StockLotCostAdjustmentRepository costAdjustmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> partIds = new ArrayList<>();
    private final List<Long> balanceIds = new ArrayList<>();
    private final List<Long> movementIds = new ArrayList<>();
    private final List<Long> lotIds = new ArrayList<>();

    @AfterEach
    void cleanFacts() {
        for (Long movementId : movementIds) {
            movementLineRepository.deleteAll(movementLineRepository.findByMovementIdOrderByIdAsc(movementId));
            movementRepository.findById(movementId).ifPresent(movementRepository::delete);
        }
        movementIds.clear();
        for (Long lotId : lotIds) {
            consumptionRepository.deleteAll(consumptionRepository.findBySourceTypeAndSourceIdOrderByIdAsc(
                    "IDEMPOTENCY_TEST", lotId));
            costAdjustmentRepository.deleteAll(
                    costAdjustmentRepository.findByStockLotIdOrderByIdAsc(lotId));
            lotRepository.findById(lotId).ifPresent(lotRepository::delete);
        }
        lotIds.clear();
        for (Long balanceId : balanceIds) {
            balanceRepository.findById(balanceId).ifPresent(balanceRepository::delete);
        }
        balanceIds.clear();
        for (Long partId : partIds) {
            partRepository.findById(partId).ifPresent(partRepository::delete);
        }
        partIds.clear();
        jdbcTemplate.update("DELETE FROM request_idempotency WHERE scope LIKE 'STOCK_%' AND request_id LIKE 'TECH:%'");
    }

    @Test
    @Timeout(30)
    void concurrentStockMovementsConvergeToOneMovementAndBalanceDelta() throws Exception {
        PartInventory part = createPart(5);
        StockBalance balance = createBalance(part.getId(), 5);
        String key = "movement-" + UUID.randomUUID();

        List<StockMovement> movements = concurrentlyWithRepeatableReadSnapshot(
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE idempotency_key = ?",
                        Integer.class,
                        key),
                () -> stockLedgerService.recordMovement(
                        "OUTBOUND", StockLedgerService.RESOURCE_PART, part.getId(), part.getPartCode(),
                        part.getPartName(), balance.getWarehouseId(), 5, 3, new BigDecimal("4.000000"),
                        "idempotency-test", "same movement", "IDEMPOTENCY_TEST", part.getId(),
                        null, LocalDate.of(2026, 7, 19), "TEST_OUTBOUND", BigDecimal.ZERO, key, null, null));

        assertThat(movements.stream().map(StockMovement::getId).distinct()).hasSize(1);
        Long movementId = movements.getFirst().getId();
        movementIds.add(movementId);
        assertThat(movementLineRepository.findByMovementIdOrderByIdAsc(movementId)).hasSize(1);
        assertThat(balanceRepository.findById(balance.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void concurrentFifoConsumptionsConvergeToOneConsumptionSet() throws Exception {
        PartInventory part = createPart(0);
        Long warehouseId = defaultWarehouseId();
        var lot = stockLotService.createReceiptLot(
                StockLedgerService.RESOURCE_PART, part.getId(), warehouseId, 5,
                new BigDecimal("4.000000"), BigDecimal.ZERO, "IDEMPOTENCY_TEST", part.getId(),
                null, LocalDate.of(2026, 7, 19), "receipt-" + UUID.randomUUID());
        lotIds.add(lot.getId());
        String key = "fifo-" + UUID.randomUUID();

        List<StockLotService.ConsumptionResult> results = concurrentlyWithRepeatableReadSnapshot(
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_lot_consumption WHERE idempotency_key LIKE ?",
                        Integer.class,
                        key + ":%"),
                () -> stockLotService.consumeFifo(
                        StockLedgerService.RESOURCE_PART, part.getId(), warehouseId, 2,
                        new BigDecimal("4.000000"), 5, "IDEMPOTENCY_TEST", lot.getId(), null,
                        LocalDate.of(2026, 7, 19), key));

        assertThat(results.stream().map(result -> result.consumptions().stream()
                .map(consumption -> consumption.getId()).toList()).distinct()).hasSize(1);
        assertThat(consumptionRepository.findBySourceTypeAndSourceIdOrderByIdAsc(
                "IDEMPOTENCY_TEST", lot.getId())).hasSize(1);
        assertThat(lotRepository.findById(lot.getId()).orElseThrow().getRemainingQuantity()).isEqualTo(3);
    }

    @Test
    @Timeout(30)
    void concurrentSerializedCostAdjustmentsApplyOnce() throws Exception {
        PartInventory part = createPart(1);
        Long warehouseId = defaultWarehouseId();
        var lot = stockLotService.createReceiptLot(
                StockLedgerService.RESOURCE_PART, part.getId(), warehouseId, 1,
                new BigDecimal("10.000000"), BigDecimal.ZERO, "IDEMPOTENCY_TEST", part.getId(),
                null, LocalDate.of(2026, 7, 19), "cost-receipt-" + UUID.randomUUID());
        lotIds.add(lot.getId());
        String key = "cost-" + UUID.randomUUID();

        concurrentlyWithRepeatableReadSnapshot(
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_lot_cost_adjustment WHERE idempotency_key = ?",
                        Integer.class,
                        key),
                () -> {
                    stockLotService.capitalizeSerializedAssetCost(
                            StockLedgerService.RESOURCE_PART, part.getId(), warehouseId,
                            new BigDecimal("2.500000"), "IDEMPOTENCY_TEST", part.getId(), null,
                            LocalDate.of(2026, 7, 19), key);
                    return Boolean.TRUE;
                });

        assertThat(costAdjustmentRepository.findByStockLotIdOrderByIdAsc(lot.getId())).hasSize(1);
        assertThat(lotRepository.findById(lot.getId()).orElseThrow().getUnitCost())
                .isEqualByComparingTo("12.500000");
    }

    private PartInventory createPart(int quantity) {
        PartInventory part = new PartInventory();
        part.setPartCode("IDEMPOTENCY-" + UUID.randomUUID());
        part.setPartName("Idempotency test part");
        part.setPartCategory("TEST");
        part.setWarehouseId(defaultWarehouseId());
        part.setQuantity(quantity);
        part.setReorderPoint(0);
        part.setUnit("pcs");
        PartInventory saved = partRepository.saveAndFlush(part);
        partIds.add(saved.getId());
        return saved;
    }

    private StockBalance createBalance(Long partId, int available) {
        StockBalance balance = new StockBalance();
        balance.setResourceType(StockLedgerService.RESOURCE_PART);
        balance.setResourceId(partId);
        balance.setWarehouseId(defaultWarehouseId());
        balance.setAvailableQuantity(available);
        balance.setReservedQuantity(0);
        balance.setLockedQuantity(0);
        StockBalance saved = balanceRepository.saveAndFlush(balance);
        balanceIds.add(saved.getId());
        return saved;
    }

    private <T> List<T> concurrentlyWithRepeatableReadSnapshot(
            Supplier<Integer> snapshotCount,
            Supplier<T> operation
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CyclicBarrier snapshotBarrier = new CyclicBarrier(CONCURRENCY);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            List<Callable<T>> tasks = java.util.stream.IntStream.range(0, CONCURRENCY)
                    .mapToObj(index -> (Callable<T>) () -> transaction.execute(status -> {
                        assertThat(snapshotCount.get())
                                .as("idempotency fact count before concurrent writes")
                                .isZero();
                        awaitSnapshotBarrier(snapshotBarrier);
                        return operation.get();
                    }))
                    .toList();
            List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
            return futures.stream().map(this::result).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitSnapshotBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while aligning stale-snapshot transactions", error);
        } catch (BrokenBarrierException | TimeoutException error) {
            throw new IllegalStateException("Could not align stale-snapshot transactions", error);
        }
    }

    private <T> T result(Future<T> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError("Concurrent inventory operation failed", error);
        }
    }
}
