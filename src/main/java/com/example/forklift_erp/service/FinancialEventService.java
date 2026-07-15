package com.example.forklift_erp.service;

import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only subledger used by both accrual and cash-basis reports.
 */
@Service
public class FinancialEventService {
    public static final String SOURCE_OUTBOUND_ORDER = "OUTBOUND_ORDER";
    public static final String SOURCE_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String SOURCE_REPAIR = "REPAIR";
    public static final String SOURCE_RENTAL_BILL = "RENTAL_BILL";

    private final FinancialEventRepository financialEventRepository;
    private final PaymentRecordRepository paymentRecordRepository;

    public FinancialEventService(
            FinancialEventRepository financialEventRepository,
            PaymentRecordRepository paymentRecordRepository
    ) {
        this.financialEventRepository = financialEventRepository;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    @Transactional
    public FinancialEvent post(
            String eventType,
            BigDecimal amount,
            LocalDate businessDate,
            String sourceType,
            Long sourceId,
            Long sourceLineId,
            String counterpartyType,
            Long counterpartyId,
            String counterpartyName,
            String remark,
            String idempotencyKey
    ) {
        if (idempotencyKey != null) {
            FinancialEvent existing = financialEventRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        FinancialEvent event = new FinancialEvent();
        event.setEventNo(BusinessNumberGenerator.next("FE", 8));
        event.setEventType(eventType);
        event.setAmount(amount == null ? BigDecimal.ZERO : amount);
        event.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        event.setSourceType(sourceType);
        event.setSourceId(sourceId);
        event.setSourceLineId(sourceLineId);
        event.setCounterpartyType(counterpartyType);
        event.setCounterpartyId(counterpartyId);
        event.setCounterpartyName(counterpartyName);
        event.setRemark(remark);
        event.setIdempotencyKey(idempotencyKey);
        event.setCreatedBy(SecurityUtils.currentUsername());
        return financialEventRepository.save(event);
    }

    @Transactional
    public void postSales(OutboundOrder order, BigDecimal totalCost, String suffix) {
        LocalDate date = order.getSalesDate() == null ? LocalDate.now() : order.getSalesDate();
        String base = "SALE:" + order.getId() + ":" + suffix;
        BigDecimal revenue = salesAmount(order);
        post(FinancialEventType.ACCOUNTS_RECEIVABLE, revenue, date, SOURCE_OUTBOUND_ORDER, order.getId(), null,
                "CUSTOMER", order.getCustomerId(), order.getCustomerName(), "Sales receivable", base + ":AR");
        post(FinancialEventType.REVENUE, revenue, date, SOURCE_OUTBOUND_ORDER, order.getId(), null,
                "CUSTOMER", order.getCustomerId(), order.getCustomerName(), "Sales revenue", base + ":REV");
        post(FinancialEventType.COST_OF_GOODS_SOLD, MoneyValues.zeroIfNullOrNegative(totalCost), date,
                SOURCE_OUTBOUND_ORDER, order.getId(), null, null, null, null, "Sales FIFO cost", base + ":COGS");
        order.setFinancialPosted(true);
    }

    @Transactional
    public void replaceSalesPosting(OutboundOrder order, BigDecimal totalCost, boolean locked) {
        LocalDate date = order.getSalesDate() == null ? LocalDate.now() : order.getSalesDate();
        String revisionSuffix = "REVISION:" + order.getVersion();
        String revisionBase = "SALE:" + order.getId() + ":" + revisionSuffix;
        // A retry of the same optimistic-lock version must not reverse the
        // already-posted revision a second time.  The events are append-only,
        // so the revision AR key is the transaction-level idempotency marker.
        if (financialEventRepository.findByIdempotencyKey(revisionBase + ":AR").isPresent()) {
            return;
        }
        reverseSourceEvents(
                SOURCE_OUTBOUND_ORDER,
                order.getId(),
                List.of(FinancialEventType.ACCOUNTS_RECEIVABLE, FinancialEventType.REVENUE, FinancialEventType.COST_OF_GOODS_SOLD),
                date,
                (locked ? "Locked sales correction" : "Sales amendment") + " for " + order.getOrderNo(),
                "SALE-REV:" + order.getId() + ":" + order.getVersion()
        );
        postSales(order, totalCost, revisionSuffix);
    }

    @Transactional
    public void postPurchase(PurchaseOrder order, BigDecimal payableAmount, String suffix) {
        LocalDate date = order.getReceivedDate() == null ? LocalDate.now() : order.getReceivedDate();
        post(FinancialEventType.ACCOUNTS_PAYABLE, MoneyValues.zeroIfNullOrNegative(payableAmount), date,
                SOURCE_PURCHASE_ORDER, order.getId(), null, "SUPPLIER", order.getSupplierId(), order.getSupplierName(),
                "Purchase payable", "PURCHASE:" + order.getId() + ":" + suffix + ":AP");
        order.setFinancialPosted(true);
    }

    @Transactional
    public void postRepair(RepairRecord repair, BigDecimal partsCost, String suffix) {
        LocalDate date = repair.getRepairDate() == null ? LocalDate.now() : repair.getRepairDate().toLocalDate();
        BigDecimal receivable = repairReceivable(repair);
        BigDecimal externalCost = MoneyValues.zeroIfNullOrNegative(repair.getRepairExpense());
        String base = "REPAIR:" + repair.getId() + ":" + suffix;
        post(FinancialEventType.ACCOUNTS_RECEIVABLE, receivable, date, SOURCE_REPAIR, repair.getId(), null,
                "CUSTOMER", repair.getCustomerId(), repair.getCustomerName(), "Repair receivable", base + ":AR");
        post(FinancialEventType.REVENUE, receivable, date, SOURCE_REPAIR, repair.getId(), null,
                "CUSTOMER", repair.getCustomerId(), repair.getCustomerName(), "Repair revenue", base + ":REV");
        post(FinancialEventType.COST_OF_GOODS_SOLD, MoneyValues.zeroIfNullOrNegative(partsCost), date,
                SOURCE_REPAIR, repair.getId(), null, null, null, null, "Repair parts FIFO cost", base + ":COGS");
        post(FinancialEventType.OPERATING_COST, externalCost, date, SOURCE_REPAIR, repair.getId(), null,
                "SUPPLIER", null, repair.getRepairPerson(), "Outsourced repair cost", base + ":OUTSOURCE");
        repair.setFinancialPosted(true);
    }

    @Transactional
    public FinancialEvent postRentalBill(
            Long rentalBillId,
            Long rentalId,
            Long customerId,
            String customerName,
            LocalDate businessDate,
            BigDecimal amount
    ) {
        String base = "RENTAL-BILL:" + rentalBillId;
        FinancialEvent ar = post(FinancialEventType.ACCOUNTS_RECEIVABLE, MoneyValues.zeroIfNullOrNegative(amount), businessDate,
                SOURCE_RENTAL_BILL, rentalBillId, rentalId, "CUSTOMER", customerId, customerName,
                "Rental bill receivable", base + ":AR");
        post(FinancialEventType.REVENUE, MoneyValues.zeroIfNullOrNegative(amount), businessDate,
                SOURCE_RENTAL_BILL, rentalBillId, rentalId, "CUSTOMER", customerId, customerName,
                "Rental bill revenue", base + ":REV");
        return ar;
    }

    @Transactional
    public PaymentRecord syncReceiptToTarget(
            String sourceType,
            Long sourceId,
            BigDecimal targetAmount,
            LocalDate paymentDate,
            String accountName,
            String paymentMethod,
            String remark,
            String idempotencyKey
    ) {
        BigDecimal current = receiptTotal(sourceType, sourceId);
        BigDecimal target = MoneyValues.zeroIfNullOrNegative(targetAmount);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return null;
        }
        return recordPayment(
                PaymentRecord.DIRECTION_RECEIPT,
                delta,
                paymentDate,
                accountName,
                paymentMethod,
                sourceType,
                sourceId,
                remark,
                idempotencyKey
        );
    }

    @Transactional
    public PaymentRecord recordPayment(
            String direction,
            BigDecimal amount,
            LocalDate paymentDate,
            String accountName,
            String paymentMethod,
            String sourceType,
            Long sourceId,
            String remark,
            String idempotencyKey
    ) {
        if (amount == null || amount.signum() == 0) {
            return null;
        }
        String effectiveIdempotencyKey = idempotencyKey == null
                ? "INTERNAL-PAYMENT:" + UUID.randomUUID()
                : idempotencyKey;
        if (idempotencyKey != null) {
            PaymentRecord existing = paymentRecordRepository.findByIdempotencyKey(effectiveIdempotencyKey).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        String eventType = PaymentRecord.DIRECTION_PAYMENT.equals(direction)
                ? FinancialEventType.CASH_PAYMENT
                : FinancialEventType.CASH_RECEIPT;
        FinancialEvent cashEvent = post(eventType, amount, paymentDate, sourceType, sourceId, null,
                null, null, null, remark, effectiveIdempotencyKey + ":EVENT");
        PaymentRecord record = new PaymentRecord();
        record.setPaymentNo(BusinessNumberGenerator.next("PAY", 8));
        record.setRequestId(effectiveIdempotencyKey);
        record.setDirection(direction);
        record.setAmount(amount);
        record.setPaymentDate(paymentDate == null ? LocalDate.now() : paymentDate);
        record.setAccountName(accountName);
        record.setPaymentMethod(paymentMethod);
        record.setSourceType(sourceType);
        record.setSourceId(sourceId);
        record.setFinancialEventId(cashEvent.getId());
        record.setRemark(remark);
        record.setIdempotencyKey(effectiveIdempotencyKey);
        record.setCreatedBy(SecurityUtils.currentUsername());
        return paymentRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public BigDecimal receiptTotal(String sourceType, Long sourceId) {
        BigDecimal amount = paymentRecordRepository.totalForSource(sourceType, sourceId, PaymentRecord.DIRECTION_RECEIPT);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    @Transactional
    public void reverseSourceEvents(
            String sourceType,
            Long sourceId,
            Collection<String> eventTypes,
            LocalDate businessDate,
            String remark,
            String idempotencyPrefix
    ) {
        List<FinancialEvent> existing = financialEventRepository
                .findBySourceTypeAndSourceIdAndEventTypeInOrderByIdAsc(sourceType, sourceId, eventTypes);
        Set<Long> reversed = new HashSet<>(existing.stream()
                .map(FinancialEvent::getReversalOfEventId)
                .filter(java.util.Objects::nonNull)
                .toList());
        for (FinancialEvent original : existing) {
            if (original.getReversalOfEventId() != null || reversed.contains(original.getId())) {
                continue;
            }
            FinancialEvent reversal = post(
                    original.getEventType(),
                    original.getAmount().negate(),
                    businessDate == null ? LocalDate.now() : businessDate,
                    sourceType,
                    sourceId,
                    original.getSourceLineId(),
                    original.getCounterpartyType(),
                    original.getCounterpartyId(),
                    original.getCounterpartyName(),
                    remark,
                    idempotencyPrefix + ":" + original.getId()
            );
            reversal.setReversalOfEventId(original.getId());
            financialEventRepository.save(reversal);
        }
    }

    private BigDecimal salesAmount(OutboundOrder order) {
        return MoneyValues.firstNonNegativeOrZero(order.getLineAmount(), order.getReceivableAmount(),
                order.getUnitSalePrice(), order.getSettlementPrice(), order.getSalePrice());
    }

    private BigDecimal repairReceivable(RepairRecord repair) {
        return MoneyValues.firstNonNegativeOrZero(
                repair.getReceivableAmount(),
                MoneyValues.zeroIfNullOrNegative(repair.getRepairFee())
                        .add(MoneyValues.zeroIfNullOrNegative(repair.getPartsFee()))
                        .add(MoneyValues.zeroIfNullOrNegative(repair.getPassThroughAmount()))
        );
    }
}
