package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class StockOperationRecorder {
    private static final String STOCK_LOG_SOURCE_TYPE = "STOCK_LOG";
    private static final String AUDIT_SOURCE_TYPE = "STOCK";

    private final StockOperationLogRepository stockOperationLogRepository;
    private final StockLedgerService stockLedgerService;
    private final OperationAuditService operationAuditService;

    public StockOperationRecorder(
            StockOperationLogRepository stockOperationLogRepository,
            StockLedgerService stockLedgerService,
            OperationAuditService operationAuditService
    ) {
        this.stockOperationLogRepository = stockOperationLogRepository;
        this.stockLedgerService = stockLedgerService;
        this.operationAuditService = operationAuditService;
    }

    @Transactional
    public StockOperationLog recordMachine(
            MachineInventory machine,
            String operationType,
            Integer quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            String operator,
            String remark
    ) {
        return record(new Command(
                "Machine stock",
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                machine.getWarehouseId(),
                operationType,
                quantity,
                beforeQuantity,
                afterQuantity,
                unitCost,
                BigDecimal.ZERO,
                operator,
                remark,
                STOCK_LOG_SOURCE_TYPE,
                null,
                ("INBOUND".equals(operationType) ? "Machine inbound " : "Machine outbound ") + quantity
        ));
    }

    @Transactional
    public StockOperationLog recordPart(
            PartInventory part,
            String operationType,
            Integer quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            String operator,
            String remark
    ) {
        return record(new Command(
                "Part stock",
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                part.getWarehouseId(),
                operationType,
                quantity,
                beforeQuantity,
                afterQuantity,
                unitCost,
                BigDecimal.ZERO,
                operator,
                remark,
                STOCK_LOG_SOURCE_TYPE,
                null,
                ("INBOUND".equals(operationType) ? "Part inbound " : "Part outbound ") + quantity
        ));
    }

    @Transactional
    public StockOperationLog record(Command command) {
        String operator = SecurityUtils.currentUsername();
        StockOperationLog stockLog = new StockOperationLog();
        stockLog.setResourceType(command.resourceType());
        stockLog.setOperationType(command.operationType());
        stockLog.setResourceId(command.resourceId());
        stockLog.setResourceCode(command.resourceCode());
        stockLog.setResourceName(command.resourceName());
        stockLog.setQuantity(command.quantity());
        stockLog.setBeforeQuantity(command.beforeQuantity());
        stockLog.setAfterQuantity(command.afterQuantity());
        stockLog.setUnitCost(command.unitCost());
        stockLog.setUnitRevenue(command.unitRevenue());
        stockLog.setOperator(operator);
        stockLog.setRemark(command.remark());
        StockOperationLog savedLog = stockOperationLogRepository.save(stockLog);
        String movementSourceType = command.movementSourceType() == null || command.movementSourceType().isBlank()
                ? STOCK_LOG_SOURCE_TYPE
                : command.movementSourceType();
        Long movementSourceId = command.movementSourceId() == null ? savedLog.getId() : command.movementSourceId();
        stockLedgerService.recordMovement(
                command.operationType(),
                command.resourceType(),
                command.resourceId(),
                command.resourceCode(),
                command.resourceName(),
                command.warehouseId(),
                command.beforeQuantity(),
                command.afterQuantity(),
                command.unitCost(),
                operator,
                command.remark(),
                movementSourceType,
                movementSourceId,
                null,
                command.businessDate() == null ? LocalDate.now() : command.businessDate(),
                command.businessType() == null || command.businessType().isBlank()
                        ? inferredBusinessType(command)
                        : command.businessType(),
                command.unitRevenue(),
                command.idempotencyKey(),
                command.stockLotId()
        );
        operationAuditService.record(command.auditModule(), command.operationType(), command.resourceType(), command.resourceId(),
                command.resourceCode(), command.resourceName(), command.auditSummary(), operator, command.remark(),
                AUDIT_SOURCE_TYPE, savedLog.getId());
        return savedLog;
    }

    private String inferredBusinessType(Command command) {
        if ("INITIAL".equals(command.operationType())) {
            return StockBusinessType.INITIAL_BALANCE;
        }
        if ("ADJUST".equals(command.operationType())
                || "STOCK_ADJUSTMENT".equals(command.movementSourceType())) {
            return StockBusinessType.STOCK_ADJUSTMENT;
        }
        if ("INBOUND".equals(command.operationType())) {
            return StockBusinessType.OTHER_INBOUND;
        }
        if ("OUTBOUND".equals(command.operationType())) {
            return StockBusinessType.OTHER_OUTBOUND;
        }
        return command.operationType();
    }

    public record Command(
            String auditModule,
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            String operationType,
            Integer quantity,
            Integer beforeQuantity,
            Integer afterQuantity,
            BigDecimal unitCost,
            BigDecimal unitRevenue,
            String operator,
            String remark,
            String movementSourceType,
            Long movementSourceId,
            String auditSummary,
            LocalDate businessDate,
            String businessType,
            String idempotencyKey,
            Long stockLotId
    ) {
        public Command(
                String auditModule,
                String resourceType,
                Long resourceId,
                String resourceCode,
                String resourceName,
                Long warehouseId,
                String operationType,
                Integer quantity,
                Integer beforeQuantity,
                Integer afterQuantity,
                BigDecimal unitCost,
                BigDecimal unitRevenue,
                String operator,
                String remark,
                String movementSourceType,
                Long movementSourceId,
                String auditSummary
        ) {
            this(
                    auditModule,
                    resourceType,
                    resourceId,
                    resourceCode,
                    resourceName,
                    warehouseId,
                    operationType,
                    quantity,
                    beforeQuantity,
                    afterQuantity,
                    unitCost,
                    unitRevenue,
                    operator,
                    remark,
                    movementSourceType,
                    movementSourceId,
                    auditSummary,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
