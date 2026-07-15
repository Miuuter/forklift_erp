package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.util.InventoryQuantities;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OutboundStockAccountingService {
    private static final String SOURCE_TYPE = "OUTBOUND_ORDER";

    private final StockOperationRecorder stockOperationRecorder;

    public OutboundStockAccountingService(StockOperationRecorder stockOperationRecorder) {
        this.stockOperationRecorder = stockOperationRecorder;
    }

    public StockOperationLog recordMachineOutbound(
            MachineInventory machine,
            OutboundOrder order,
            InventoryQuantities.QuantityChange stockChange,
            BigDecimal unitCost,
            String operator,
            String remark
    ) {
        return recordMachineOutbound(machine, order, stockChange, unitCost, machine.getWarehouseId(), operator, remark);
    }

    public StockOperationLog recordMachineOutbound(
            MachineInventory machine,
            OutboundOrder order,
            InventoryQuantities.QuantityChange stockChange,
            BigDecimal unitCost,
            Long warehouseId,
            String operator,
            String remark
    ) {
        return recordOutbound(
                OutboundOrder.RESOURCE_MACHINE,
                machine.getId(),
                machine.getVehicleProductNumber(),
                machine.getName(),
                warehouseId,
                stockChange,
                unitCost,
                order,
                operator,
                remark
        );
    }

    public StockOperationLog recordPartOutbound(
            PartInventory part,
            OutboundOrder order,
            InventoryQuantities.QuantityChange stockChange,
            String operator,
            String remark
    ) {
        return recordPartOutbound(part, order, stockChange, partUnitCost(part), part.getWarehouseId(), operator, remark);
    }

    public StockOperationLog recordPartOutbound(
            PartInventory part,
            OutboundOrder order,
            InventoryQuantities.QuantityChange stockChange,
            BigDecimal unitCost,
            Long warehouseId,
            String operator,
            String remark
    ) {
        return recordOutbound(
                OutboundOrder.RESOURCE_PART,
                part.getId(),
                part.getPartCode(),
                part.getPartName(),
                warehouseId,
                stockChange,
                unitCost,
                order,
                operator,
                remark
        );
    }

    public BigDecimal machineUnitCost(MachineInventory machine) {
        return MoneyValues.firstNonNegativeOrNull(
                machine.getLandedUnitCost(),
                machine.getPurchasePrice(),
                BigDecimal.ZERO
        );
    }

    BigDecimal partUnitCost(PartInventory part) {
        return MoneyValues.firstNonNegativeOrNull(
                part.getLandedUnitCost(),
                part.getPurchasePrice(),
                BigDecimal.ZERO
        );
    }

    BigDecimal unitRevenue(OutboundOrder order) {
        int quantity = order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
        BigDecimal fromLineAmount = MoneyValues.firstNonNegativeOrNull(order.getLineAmount(), order.getReceivableAmount());
        if (fromLineAmount != null) {
            return fromLineAmount.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP);
        }
        return MoneyValues.firstNonNegativeOrNull(
                order.getUnitSalePrice(),
                order.getSettlementPrice(),
                order.getSalePrice(),
                BigDecimal.ZERO
        );
    }

    private StockOperationLog recordOutbound(
            String resourceType,
            Long resourceId,
            String resourceCode,
            String resourceName,
            Long warehouseId,
            InventoryQuantities.QuantityChange stockChange,
            BigDecimal unitCost,
            OutboundOrder order,
            String operator,
            String remark
    ) {
        return stockOperationRecorder.record(new StockOperationRecorder.Command(
                resourceType.equals(OutboundOrder.RESOURCE_MACHINE) ? "Machine stock" : "Part stock",
                resourceType,
                resourceId,
                resourceCode,
                resourceName,
                warehouseId,
                "OUTBOUND",
                stockChange.quantity(),
                stockChange.beforeQuantity(),
                stockChange.afterQuantity(),
                unitCost,
                unitRevenue(order),
                operator,
                remark,
                SOURCE_TYPE,
                order.getId(),
                "Outbound quantity: " + stockChange.quantity()
        ));
    }
}
