package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.config.MaintenanceOperation;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusinessDataResetService {
    /**
     * Leaf-to-root order for every table whose rows are business facts rather
     * than authentication, configuration, warehouse, supplier or template
     * master data. New business tables must be added here and to the reset
     * integration invariant before a release can pass.
     */
    private static final List<ResetTarget> RESET_TARGETS = List.of(
            target("requestIdempotency", "request_idempotency"),
            target("dataImportRows", "data_import_row"),
            target("resourceAttachments", "resource_attachment"),
            target("repairPartUsage", "repair_part_usage"),
            target("modificationWorkOrderLines", "modification_work_order_line"),
            target("paymentRecords", "payment_record", "reversal_of_payment_id"),
            target("rentalBills", "rental_bill"),
            target("stockLotCostAdjustments", "stock_lot_cost_adjustment"),
            target("stockMovementLines", "stock_movement_line"),
            target("stockLotConsumptions", "stock_lot_consumption", "reversal_of_consumption_id"),
            target("financialEvents", "financial_event", "reversal_of_event_id"),
            target("configReplaceLogs", "config_replace_log"),
            target("outboundOrders", "outbound_order"),
            target("purchaseOrders", "purchase_order"),
            target("stocktakingRecords", "stocktaking_record"),
            target("modificationWorkOrders", "modification_work_order"),
            target("rentalRecords", "rental_record"),
            target("stockLots", "stock_lot"),
            target("stockMovements", "stock_movement"),
            target("stockBalances", "stock_balance"),
            target("stockOperationLogs", "stock_operation_log"),
            target("machineConfigs", "machine_config"),
            target("repairRecords", "repair_record"),
            target("partInventories", "part_inventory"),
            target("machineInventories", "machine_inventory"),
            target("customers", "customer_profile"),
            target("dataImportJobs", "data_import_job"),
            target("migrationExceptions", "migration_exception"),
            target("operationAuditLogs", "operation_audit_log")
    );

    private final JdbcTemplate jdbcTemplate;

    public BusinessDataResetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    @MaintenanceOperation
    public Map<String, Long> resetBusinessData() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (ResetTarget target : RESET_TARGETS) {
            long count = rowCount(target.tableName());
            if (count > 0) {
                if (target.selfReferenceColumn() == null) {
                    jdbcTemplate.update("delete from `" + target.tableName() + "`");
                } else {
                    deleteSelfReferentialRows(target);
                }
            }
            summary.put(target.summaryKey(), count);
        }
        assertResetInvariant();
        return summary;
    }

    private void deleteSelfReferentialRows(ResetTarget target) {
        long previous = rowCount(target.tableName());
        while (previous > 0) {
            int deleted = jdbcTemplate.update(
                    "delete parent from `" + target.tableName() + "` parent "
                            + "left join `" + target.tableName() + "` child "
                            + "on child.`" + target.selfReferenceColumn() + "` = parent.id "
                            + "where child.id is null"
            );
            if (deleted <= 0) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Business reset found a cyclic self-reference in " + target.tableName());
            }
            long remaining = rowCount(target.tableName());
            if (remaining >= previous) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Business reset made no progress in " + target.tableName());
            }
            previous = remaining;
        }
    }

    private void assertResetInvariant() {
        for (ResetTarget target : RESET_TARGETS) {
            long remaining = rowCount(target.tableName());
            if (remaining != 0) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR,
                        "Business reset invariant failed for " + target.tableName()
                                + ": remaining rows=" + remaining);
            }
        }
    }

    private long rowCount(String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from `" + tableName + "`",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static ResetTarget target(String summaryKey, String tableName) {
        return new ResetTarget(summaryKey, tableName, null);
    }

    private static ResetTarget target(String summaryKey, String tableName, String selfReferenceColumn) {
        return new ResetTarget(summaryKey, tableName, selfReferenceColumn);
    }

    private record ResetTarget(String summaryKey, String tableName, String selfReferenceColumn) {
    }
}
