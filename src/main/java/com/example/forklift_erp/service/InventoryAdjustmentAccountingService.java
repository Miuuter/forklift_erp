package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.service.impl.StockOperationRecorder;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Posts one explicit inventory adjustment as a single transaction across the
 * warehouse balance, FIFO cost layer, stock/audit logs and financial subledger.
 */
@Service
public class InventoryAdjustmentAccountingService {
    private final StockLotService stockLotService;
    private final StockOperationRecorder stockOperationRecorder;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final FinancialEventService financialEventService;

    public InventoryAdjustmentAccountingService(
            StockLotService stockLotService,
            StockOperationRecorder stockOperationRecorder,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            FinancialEventService financialEventService
    ) {
        this.stockLotService = stockLotService;
        this.stockOperationRecorder = stockOperationRecorder;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.financialEventService = financialEventService;
    }

    @Transactional
    public AdjustmentResult post(Command command) {
        int delta = command.afterQuantity() - command.beforeQuantity();
        if (delta == 0) {
            return AdjustmentResult.empty();
        }
        if (command.idempotencyBase() == null || command.idempotencyBase().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Inventory adjustment idempotency key is required");
        }

        LocalDate businessDate = command.businessDate() == null ? LocalDate.now() : command.businessDate();
        int quantity = Math.abs(delta);
        BigDecimal unitCost;
        BigDecimal totalCost;
        Long stockLotId;

        if (delta > 0) {
            unitCost = MoneyValues.zeroIfNullOrNegative(command.fallbackUnitCost());
            totalCost = unitCost.multiply(BigDecimal.valueOf(quantity));
            StockLot lot = stockLotService.createReceiptLot(
                    command.resourceType(),
                    command.resourceId(),
                    command.warehouseId(),
                    quantity,
                    unitCost,
                    BigDecimal.ZERO,
                    command.sourceType(),
                    command.sourceId(),
                    null,
                    businessDate,
                    command.idempotencyBase() + ":LOT"
            );
            stockLotId = lot.getId();
        } else {
            StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                    command.resourceType(),
                    command.resourceId(),
                    command.warehouseId(),
                    quantity,
                    command.fallbackUnitCost(),
                    command.beforeQuantity(),
                    command.sourceType(),
                    command.sourceId(),
                    null,
                    businessDate,
                    command.idempotencyBase() + ":FIFO"
            );
            unitCost = fifo.unitCost();
            totalCost = fifo.totalCost();
            stockLotId = fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId();
        }

        String movementKey = command.idempotencyBase() + ":MOVEMENT";
        StockOperationLog stockLog = stockOperationRecorder.record(new StockOperationRecorder.Command(
                command.auditModule(),
                command.resourceType(),
                command.resourceId(),
                command.resourceCode(),
                command.resourceName(),
                command.warehouseId(),
                delta > 0 ? "INBOUND" : "OUTBOUND",
                quantity,
                command.beforeQuantity(),
                command.afterQuantity(),
                unitCost,
                BigDecimal.ZERO,
                command.operator(),
                command.remark(),
                command.sourceType(),
                command.sourceId(),
                command.auditSummary(),
                businessDate,
                command.businessType(),
                movementKey,
                stockLotId
        ));

        StockMovement movement = stockMovementRepository.findByIdempotencyKey(movementKey)
                .orElseThrow(() -> new BusinessException(ResultCode.CONFLICT,
                        "Inventory adjustment movement was not recorded"));
        List<StockMovementLine> movementLines =
                stockMovementLineRepository.findByMovementIdOrderByIdAsc(movement.getId());
        for (StockMovementLine line : movementLines) {
            line.setUnitCost(unitCost);
            line.setCostAmount(totalCost);
            line.setLineAmount(BigDecimal.ZERO);
            line.setStockLotId(stockLotId);
            stockMovementLineRepository.save(line);
        }

        if (command.financialImpact()) {
            financialEventService.post(
                    delta > 0 ? FinancialEventType.INVENTORY_GAIN : FinancialEventType.INVENTORY_LOSS,
                    totalCost,
                    businessDate,
                    command.sourceType(),
                    command.sourceId(),
                    null,
                    null,
                    null,
                    null,
                    command.remark(),
                    command.idempotencyBase() + ":FINANCIAL"
            );
        }
        return new AdjustmentResult(stockLog, movement, unitCost, totalCost, stockLotId);
    }

    public record Command(
            String auditModule,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            int beforeQuantity,
            int afterQuantity,
            BigDecimal fallbackUnitCost,
            String operator,
            String remark,
            String sourceType,
            Long sourceId,
            String auditSummary,
            LocalDate businessDate,
            String businessType,
            String idempotencyBase,
            boolean financialImpact
    ) {
    }

    public record AdjustmentResult(
            StockOperationLog stockLog,
            StockMovement movement,
            BigDecimal unitCost,
            BigDecimal totalCost,
            Long stockLotId
    ) {
        public static AdjustmentResult empty() {
            return new AdjustmentResult(null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }
}
