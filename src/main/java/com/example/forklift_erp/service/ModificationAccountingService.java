package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.constant.PartChangeAction;
import com.example.forklift_erp.entity.ModificationWorkOrder;
import com.example.forklift_erp.entity.ModificationWorkOrderLine;
import com.example.forklift_erp.entity.StockLotConsumption;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.StockLotConsumptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Owns the FIFO-cost projection and financial postings produced by a
 * modification work order.
 */
@Service
public class ModificationAccountingService {
    private static final String SOURCE_TYPE = FinancialEventService.SOURCE_MODIFICATION_WORK_ORDER;

    private final ModificationWorkOrderRepository workOrderRepository;
    private final StockLotConsumptionRepository stockLotConsumptionRepository;
    private final FinancialEventService financialEventService;

    public ModificationAccountingService(
            ModificationWorkOrderRepository workOrderRepository,
            StockLotConsumptionRepository stockLotConsumptionRepository,
            FinancialEventService financialEventService
    ) {
        this.workOrderRepository = workOrderRepository;
        this.stockLotConsumptionRepository = stockLotConsumptionRepository;
        this.financialEventService = financialEventService;
    }

    @Transactional
    public void postFinancial(ModificationWorkOrder order, List<ModificationWorkOrderLine> lines) {
        if (Boolean.TRUE.equals(order.getFinancialPosted())) {
            return;
        }
        BigDecimal charge = lines.stream()
                .map(ModificationWorkOrderLine::getChargeAmount)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cost = lines.stream()
                .map(ModificationWorkOrderLine::getCostAmount)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recoveredValue = lines.stream()
                .map(this::returnedPartValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate date = order.getBusinessDate() == null ? LocalDate.now() : order.getBusinessDate();
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && charge.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.ACCOUNTS_RECEIVABLE,
                    charge,
                    date,
                    SOURCE_TYPE,
                    order.getId(),
                    null,
                    "CUSTOMER",
                    null,
                    order.getCustomerName(),
                    "After-sale modification receivable",
                    "MODIFICATION:" + order.getId() + ":AR"
            );
            financialEventService.post(
                    FinancialEventType.REVENUE,
                    charge,
                    date,
                    SOURCE_TYPE,
                    order.getId(),
                    null,
                    "CUSTOMER",
                    null,
                    order.getCustomerName(),
                    "After-sale modification revenue",
                    "MODIFICATION:" + order.getId() + ":REV"
            );
        }
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && cost.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.OPERATING_COST,
                    cost,
                    date,
                    SOURCE_TYPE,
                    order.getId(),
                    null,
                    null,
                    null,
                    null,
                    "Modification FIFO cost",
                    "MODIFICATION:" + order.getId() + ":COST"
            );
        }
        if ("AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()) && recoveredValue.signum() > 0) {
            financialEventService.post(
                    FinancialEventType.INVENTORY_GAIN,
                    recoveredValue,
                    date,
                    SOURCE_TYPE,
                    order.getId(),
                    null,
                    null,
                    null,
                    null,
                    "Recovered old-part inventory value",
                    "MODIFICATION:" + order.getId() + ":OLD-PART-RECOVERY"
            );
        }
        order.setFinancialPosted(true);
        workOrderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public BigDecimal fifoCostForLine(Long workOrderId, Long workOrderLineId) {
        return stockLotConsumptionRepository
                .findBySourceTypeAndSourceIdAndSourceLineIdOrderByIdAsc(
                        SOURCE_TYPE,
                        workOrderId,
                        workOrderLineId
                )
                .stream()
                .filter(consumption -> consumption.getReversalOfConsumptionId() == null)
                .map(StockLotConsumption::getTotalCost)
                .map(this::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal capitalizationAmount(ModificationWorkOrderLine line) {
        return amountOrZero(line.getCostAmount()).subtract(returnedPartValue(line));
    }

    private BigDecimal returnedPartValue(ModificationWorkOrderLine line) {
        if (!oldPartReturnsToInventory(line)) {
            return BigDecimal.ZERO;
        }
        int quantity = line.getQuantity() == null || line.getQuantity() < 1 ? 1 : line.getQuantity();
        BigDecimal unitCost = amountOrZero(line.getOldPartUnitCost());
        return unitCost.signum() < 0 ? BigDecimal.ZERO : unitCost.multiply(BigDecimal.valueOf(quantity));
    }

    private boolean oldPartReturnsToInventory(ModificationWorkOrderLine line) {
        String disposition = blankToNull(line.getOldPartDisposition());
        if (disposition != null) {
            disposition = disposition.toUpperCase(Locale.ROOT);
            if ("SCRAP".equals(disposition) || "DISCARD".equals(disposition) || "NONE".equals(disposition)) {
                return false;
            }
        }
        return PartChangeAction.STOCK_IN.code().equalsIgnoreCase(line.getOldPartAction());
    }

    private BigDecimal amountOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
