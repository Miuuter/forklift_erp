package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.FinancialEventType;
import com.example.forklift_erp.entity.FinancialEvent;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.RepairRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.FinancialEventRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            FinancialEventType.ACCOUNTS_RECEIVABLE,
            FinancialEventType.ACCOUNTS_PAYABLE,
            FinancialEventType.REVENUE,
            FinancialEventType.COST_OF_GOODS_SOLD,
            FinancialEventType.OPERATING_COST,
            FinancialEventType.INVENTORY_GAIN,
            FinancialEventType.INVENTORY_LOSS,
            FinancialEventType.CASH_RECEIPT,
            FinancialEventType.CASH_PAYMENT
    );

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
        return post(
                eventType,
                amount,
                businessDate,
                sourceType,
                sourceId,
                sourceLineId,
                counterpartyType,
                counterpartyId,
                counterpartyName,
                remark,
                idempotencyKey,
                null
        );
    }

    private FinancialEvent post(
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
            String idempotencyKey,
            Long reversalOfEventId
    ) {
        if (eventType == null || !SUPPORTED_EVENT_TYPES.contains(eventType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Unsupported financial event type: " + eventType);
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Financial event source type is required");
        }
        if (reversalOfEventId == null && amount != null && amount.signum() < 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A non-reversal financial event cannot have a negative amount");
        }
        if (reversalOfEventId != null && amount != null && amount.signum() > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A financial reversal must have a non-positive amount");
        }
        BigDecimal effectiveAmount = normalizeFinancialAmount(amount);
        if ((FinancialEventType.CASH_RECEIPT.equals(eventType)
                || FinancialEventType.CASH_PAYMENT.equals(eventType))
                && sourceLineId != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Cash financial events cannot be bound to a source detail line");
        }
        if (idempotencyKey != null) {
            FinancialEvent existing = financialEventRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                if (!Objects.equals(eventType, existing.getEventType())
                        || existing.getAmount() == null
                        || existing.getAmount().compareTo(effectiveAmount) != 0
                        || (businessDate != null && !Objects.equals(businessDate, existing.getBusinessDate()))
                        || !Objects.equals(sourceType, existing.getSourceType())
                        || !Objects.equals(sourceId, existing.getSourceId())
                        || !Objects.equals(sourceLineId, existing.getSourceLineId())
                        || !Objects.equals(counterpartyType, existing.getCounterpartyType())
                        || !Objects.equals(counterpartyId, existing.getCounterpartyId())
                        || !Objects.equals(counterpartyName, existing.getCounterpartyName())
                        || !Objects.equals(remark, existing.getRemark())
                        || !Objects.equals(reversalOfEventId, existing.getReversalOfEventId())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Financial event idempotency key is already bound to a different payload");
                }
                return existing;
            }
        }
        FinancialEvent event = new FinancialEvent();
        event.setEventNo(BusinessNumberGenerator.next("FE", 8));
        event.setEventType(eventType);
        event.setAmount(effectiveAmount);
        event.setBusinessDate(businessDate == null ? LocalDate.now() : businessDate);
        event.setSourceType(sourceType);
        event.setSourceId(sourceId);
        event.setSourceLineId(sourceLineId);
        event.setCounterpartyType(counterpartyType);
        event.setCounterpartyId(counterpartyId);
        event.setCounterpartyName(counterpartyName);
        event.setRemark(remark);
        event.setIdempotencyKey(idempotencyKey);
        event.setReversalOfEventId(reversalOfEventId);
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
        BigDecimal target = MoneyValues.zeroIfNullOrNegative(targetAmount);
        String effectiveIdempotencyKey = idempotencyKey == null
                ? "INTERNAL-RECEIPT:" + UUID.randomUUID()
                : idempotencyKey;
        List<PaymentRecord> ledger = paymentRecordRepository
                .findBySourceTypeAndSourceIdAndDirectionForUpdate(
                        sourceType, sourceId, PaymentRecord.DIRECTION_RECEIPT);
        BigDecimal current = ledger.stream()
                .map(PaymentRecord::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return null;
        }
        LocalDate effectivePaymentDate = paymentDate == null ? LocalDate.now() : paymentDate;
        if (delta.signum() > 0) {
            return recordPayment(
                    PaymentRecord.DIRECTION_RECEIPT,
                    delta,
                    effectivePaymentDate,
                    accountName,
                    paymentMethod,
                    sourceType,
                    sourceId,
                    remark,
                    effectiveIdempotencyKey
            );
        }

        BigDecimal reduction = delta.negate();
        Set<Long> reversedPaymentIds = ledger.stream()
                .map(PaymentRecord::getReversalOfPaymentId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<PaymentRecord> candidates = ledger.stream()
                .filter(payment -> payment.getAmount() != null && payment.getAmount().signum() > 0)
                .filter(payment -> payment.getId() != null && !reversedPaymentIds.contains(payment.getId()))
                .sorted(java.util.Comparator.comparing(PaymentRecord::getPaymentDate,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(PaymentRecord::getId,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .reversed())
                .toList();
        BigDecimal available = candidates.stream()
                .map(PaymentRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(reduction) < 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Receipt target cannot be reduced because no complete payment reversal covers the delta");
        }

        BigDecimal remaining = reduction;
        PaymentRecord last = null;
        for (PaymentRecord original : candidates) {
            if (remaining.signum() <= 0) {
                break;
            }
            last = recordPayment(
                    PaymentRecord.DIRECTION_RECEIPT,
                    original.getAmount().negate(),
                    effectivePaymentDate,
                    original.getAccountName(),
                    original.getPaymentMethod(),
                    sourceType,
                    sourceId,
                    remark == null ? "Receipt target correction" : remark,
                    effectiveIdempotencyKey + ":REVERSAL:" + original.getId(),
                    original.getId(),
                    original.getFinancialEventId()
            );
            remaining = remaining.subtract(original.getAmount());
        }
        if (remaining.signum() < 0) {
            last = recordPayment(
                    PaymentRecord.DIRECTION_RECEIPT,
                    remaining.negate(),
                    effectivePaymentDate,
                    accountName,
                    paymentMethod,
                    sourceType,
                    sourceId,
                    remark == null ? "Receipt target correction tail" : remark,
                    effectiveIdempotencyKey + ":TAIL"
            );
        }
        return last;
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
        return recordPayment(
                direction,
                amount,
                paymentDate,
                accountName,
                paymentMethod,
                sourceType,
                sourceId,
                remark,
                idempotencyKey,
                null,
                null
        );
    }

    /**
     * Records a cash fact and optionally binds both reversal identities before
     * the first insert. Both facts are persisted in the same transaction so a
     * retry cannot substitute an unrelated payment or financial event.
     */
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
            String idempotencyKey,
            Long reversalOfPaymentId,
            Long reversalOfFinancialEventId
    ) {
        if (amount == null || amount.signum() == 0) {
            return null;
        }
        if (!PaymentRecord.DIRECTION_RECEIPT.equals(direction)
                && !PaymentRecord.DIRECTION_PAYMENT.equals(direction)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Payment direction must be RECEIPT or PAYMENT");
        }
        if (amount.scale() > 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Payment amount must fit DECIMAL(14,2)");
        }
        int integerDigits = Math.max(0, amount.precision() - amount.scale());
        if (integerDigits > 12) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Payment amount must fit DECIMAL(14,2)");
        }
        BigDecimal effectiveAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() < 0
                ? reversalOfPaymentId == null || reversalOfFinancialEventId == null
                : reversalOfPaymentId != null || reversalOfFinancialEventId != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Negative payment facts must be complete reversals of one original payment");
        }
        LocalDate effectivePaymentDate = paymentDate == null ? LocalDate.now() : paymentDate;
        String effectiveIdempotencyKey = idempotencyKey == null
                ? "INTERNAL-PAYMENT:" + UUID.randomUUID()
                : idempotencyKey;
        if (idempotencyKey != null) {
            PaymentRecord existing = paymentRecordRepository.findByIdempotencyKey(effectiveIdempotencyKey).orElse(null);
            if (existing != null) {
                if (!Objects.equals(direction, existing.getDirection())
                        || existing.getAmount() == null
                        || existing.getAmount().compareTo(effectiveAmount) != 0
                        || (paymentDate != null && !Objects.equals(effectivePaymentDate, existing.getPaymentDate()))
                        || !Objects.equals(sourceType, existing.getSourceType())
                        || !Objects.equals(sourceId, existing.getSourceId())
                        || !Objects.equals(accountName, existing.getAccountName())
                        || !Objects.equals(paymentMethod, existing.getPaymentMethod())
                        || !Objects.equals(remark, existing.getRemark())
                        || !Objects.equals(reversalOfPaymentId, existing.getReversalOfPaymentId())
                        || !Objects.equals(
                        reversalOfFinancialEventId, existing.getReversalOfFinancialEventId())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Payment idempotency key is already bound to a different payload");
                }
                return existing;
            }
        }
        String eventType = PaymentRecord.DIRECTION_PAYMENT.equals(direction)
                ? FinancialEventType.CASH_PAYMENT
                : FinancialEventType.CASH_RECEIPT;
        FinancialEvent cashEvent = post(eventType, effectiveAmount, paymentDate, sourceType, sourceId, null,
                null, null, null, remark, effectiveIdempotencyKey + ":EVENT",
                reversalOfFinancialEventId);
        PaymentRecord record = new PaymentRecord();
        record.setPaymentNo(BusinessNumberGenerator.next("PAY", 8));
        record.setRequestId(effectiveIdempotencyKey);
        record.setDirection(direction);
        record.setAmount(effectiveAmount);
        record.setPaymentDate(effectivePaymentDate);
        record.setAccountName(accountName);
        record.setPaymentMethod(paymentMethod);
        record.setSourceType(sourceType);
        record.setSourceId(sourceId);
        record.setFinancialEventId(cashEvent.getId());
        record.setRemark(remark);
        record.setIdempotencyKey(effectiveIdempotencyKey);
        record.setReversalOfPaymentId(reversalOfPaymentId);
        record.setReversalOfFinancialEventId(reversalOfFinancialEventId);
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
                    original.getSourceType(),
                    original.getSourceId(),
                    original.getSourceLineId(),
                    original.getCounterpartyType(),
                    original.getCounterpartyId(),
                    original.getCounterpartyName(),
                    remark,
                    idempotencyPrefix + ":" + original.getId(),
                    original.getId()
            );
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

    private BigDecimal normalizeFinancialAmount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP);
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (integerDigits > 12) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Financial amount must fit DECIMAL(14,2)");
        }
        return normalized;
    }
}
