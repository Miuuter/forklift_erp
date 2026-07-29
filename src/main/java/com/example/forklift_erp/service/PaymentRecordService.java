package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.ModificationWorkOrderStatus;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.PaymentRecordCreateDTO;
import com.example.forklift_erp.dto.PaymentRecordVO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalBillRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.service.impl.OutboundReceivablePolicy;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class PaymentRecordService {
    private final PaymentRecordRepository paymentRecordRepository;
    private final FinancialEventService financialEventService;
    private final OutboundOrderRepository outboundOrderRepository;
    private final OutboundReceivablePolicy outboundReceivablePolicy;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final RepairRecordRepository repairRecordRepository;
    private final RentalBillRepository rentalBillRepository;
    private final ModificationWorkOrderRepository modificationWorkOrderRepository;
    private final OperationAuditService operationAuditService;
    private final RequestIdempotencyGuard requestIdempotencyGuard;

    public PaymentRecordService(
            PaymentRecordRepository paymentRecordRepository,
            FinancialEventService financialEventService,
            OutboundOrderRepository outboundOrderRepository,
            OutboundReceivablePolicy outboundReceivablePolicy,
            PurchaseOrderRepository purchaseOrderRepository,
            RepairRecordRepository repairRecordRepository,
            RentalBillRepository rentalBillRepository,
            ModificationWorkOrderRepository modificationWorkOrderRepository,
            OperationAuditService operationAuditService,
            RequestIdempotencyGuard requestIdempotencyGuard
    ) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.financialEventService = financialEventService;
        this.outboundOrderRepository = outboundOrderRepository;
        this.outboundReceivablePolicy = outboundReceivablePolicy;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.repairRecordRepository = repairRecordRepository;
        this.rentalBillRepository = rentalBillRepository;
        this.modificationWorkOrderRepository = modificationWorkOrderRepository;
        this.operationAuditService = operationAuditService;
        this.requestIdempotencyGuard = requestIdempotencyGuard;
    }

    @Transactional(readOnly = true)
    public List<PaymentRecordVO> findBySource(String sourceType, Long sourceId) {
        String normalizedSourceType = normalizeSourceType(sourceType);
        validateSourceExists(normalizedSourceType, sourceId);
        return paymentRecordRepository.findBySourceTypeAndSourceIdOrderByPaymentDateAscIdAsc(
                        normalizedSourceType, sourceId)
                .stream()
                .map(PaymentRecordVO::fromEntity)
                .toList();
    }

    @Transactional
    public PaymentRecordVO create(PaymentRecordCreateDTO request) {
        String requestId = normalizeRequestId(request.getRequestId());
        String persistedRequestId = "PAYMENT-REQUEST:" + requestId;
        String direction = normalizeDirection(request.getDirection());
        String sourceType = normalizeSourceType(request.getSourceType());
        BigDecimal amount = requirePositiveAmount(request.getAmount());
        String accountName = trimToNull(request.getAccountName());
        String paymentMethod = trimToNull(request.getPaymentMethod());
        String remark = trimToNull(request.getRemark());
        validateDirectionForSource(direction, sourceType);
        if (!requestIdempotencyGuard.claim("PAYMENT_CREATE", requestId)) {
            return existingRequest(
                    persistedRequestId,
                    direction,
                    amount,
                    sourceType,
                    request.getSourceId(),
                    request.getPaymentDate(),
                    accountName,
                    paymentMethod,
                    remark
            );
        }
        lockPaymentSource(sourceType, request.getSourceId(), true);
        PaymentRecord saved = financialEventService.recordPayment(
                direction,
                amount,
                request.getPaymentDate(),
                accountName,
                paymentMethod,
                sourceType,
                request.getSourceId(),
                remark,
                persistedRequestId
        );
        syncOutboundReceipt(sourceType, request.getSourceId());
        operationAuditService.record(
                "Payment",
                "CREATE",
                "PAYMENT_RECORD",
                saved.getId(),
                saved.getPaymentNo(),
                sourceType + ":" + request.getSourceId(),
                direction + " " + saved.getAmount(),
                SecurityUtils.currentUsername(),
                saved.getRemark(),
                sourceType,
                request.getSourceId()
        );
        return PaymentRecordVO.fromEntity(saved);
    }

    @Transactional
    public PaymentRecordVO reverse(Long id, String requestId, String remark) {
        String normalizedRequestId = normalizeRequestId(requestId);
        String persistedRequestId = "PAYMENT-REVERSAL-REQUEST:" + normalizedRequestId;
        String effectiveRemark = trimToNull(remark) == null ? "Payment reversal" : trimToNull(remark);
        if (!requestIdempotencyGuard.claim("PAYMENT_REVERSE", normalizedRequestId)) {
            return existingReversalRequest(persistedRequestId, id, effectiveRemark);
        }
        PaymentRecord candidate = paymentRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Payment record not found"));
        if (candidate.getReversalOfPaymentId() != null
                || candidate.getAmount() == null
                || candidate.getAmount().signum() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "Only an original positive payment can be reversed");
        }
        // Outbound updates lock their source before synchronizing receipt rows.
        // Use the same order here so an update and reversal cannot deadlock.
        lockPaymentSource(candidate.getSourceType(), candidate.getSourceId(), false);
        PaymentRecord original = paymentRecordRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Payment record not found"));
        if (!Objects.equals(candidate.getSourceType(), original.getSourceType())
                || !Objects.equals(candidate.getSourceId(), original.getSourceId())
                || !Objects.equals(candidate.getFinancialEventId(), original.getFinancialEventId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Payment source changed while the reversal was being prepared");
        }
        if (original.getReversalOfPaymentId() != null
                || original.getAmount() == null
                || original.getAmount().signum() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "Only an original positive payment can be reversed");
        }
        if (paymentRecordRepository.findByReversalOfPaymentId(original.getId()).isPresent()) {
            throw new BusinessException(ResultCode.CONFLICT, "Payment record has already been reversed");
        }
        BigDecimal currentTotal = paymentRecordRepository.totalForSource(
                original.getSourceType(), original.getSourceId(), original.getDirection());
        if (currentTotal == null || currentTotal.subtract(original.getAmount()).signum() < 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Payment reversal would make the source total negative");
        }
        PaymentRecord reversal = financialEventService.recordPayment(
                original.getDirection(),
                original.getAmount().negate(),
                original.getPaymentDate(),
                original.getAccountName(),
                original.getPaymentMethod(),
                original.getSourceType(),
                original.getSourceId(),
                effectiveRemark,
                persistedRequestId,
                original.getId(),
                original.getFinancialEventId()
        );
        syncOutboundReceipt(original.getSourceType(), original.getSourceId());
        operationAuditService.record(
                "Payment",
                "REVERSE",
                "PAYMENT_RECORD",
                reversal.getId(),
                reversal.getPaymentNo(),
                original.getPaymentNo(),
                "Reverse payment " + original.getPaymentNo(),
                SecurityUtils.currentUsername(),
                reversal.getRemark(),
                original.getSourceType(),
                original.getSourceId()
        );
        return PaymentRecordVO.fromEntity(reversal);
    }

    private PaymentRecordVO existingRequest(
            String persistedRequestId,
            String direction,
            BigDecimal amount,
            String sourceType,
            Long sourceId,
            java.time.LocalDate requestedPaymentDate,
            String accountName,
            String paymentMethod,
            String remark
    ) {
        PaymentRecord existing = existingClaimedRequest(persistedRequestId);
        boolean samePayload = Objects.equals(direction, existing.getDirection())
                && amountsEqual(amount, existing.getAmount())
                && Objects.equals(sourceType, existing.getSourceType())
                && Objects.equals(sourceId, existing.getSourceId())
                && (requestedPaymentDate == null || Objects.equals(requestedPaymentDate, existing.getPaymentDate()))
                && Objects.equals(accountName, trimToNull(existing.getAccountName()))
                && Objects.equals(paymentMethod, trimToNull(existing.getPaymentMethod()))
                && Objects.equals(remark, trimToNull(existing.getRemark()));
        if (!samePayload) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Request ID was already used for a different payment payload");
        }
        return PaymentRecordVO.fromEntity(existing);
    }

    private PaymentRecordVO existingReversalRequest(
            String persistedRequestId,
            Long originalPaymentId,
            String effectiveRemark
    ) {
        PaymentRecord existing = existingClaimedRequest(persistedRequestId);
        if (!Objects.equals(originalPaymentId, existing.getReversalOfPaymentId())
                || !Objects.equals(effectiveRemark, trimToNull(existing.getRemark()))) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Request ID was already used for a different payment reversal");
        }
        return PaymentRecordVO.fromEntity(existing);
    }

    private PaymentRecord existingClaimedRequest(String persistedRequestId) {
        return paymentRecordRepository.findByRequestIdForUpdate(persistedRequestId)
                .orElseThrow(() -> new BusinessException(
                        ResultCode.CONFLICT,
                        "Request is already being processed; retry with the same requestId"
                ));
    }

    private void syncOutboundReceipt(String sourceType, Long sourceId) {
        if (!FinancialEventService.SOURCE_OUTBOUND_ORDER.equals(sourceType)) {
            return;
        }
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(sourceId).orElse(null);
        if (order == null) {
            return;
        }
        BigDecimal received = financialEventService.receiptTotal(sourceType, sourceId);
        List<PaymentRecord> records = paymentRecordRepository
                .findBySourceTypeAndSourceIdOrderByPaymentDateAscIdAsc(sourceType, sourceId);
        Set<Long> reversedPaymentIds = records.stream()
                .map(PaymentRecord::getReversalOfPaymentId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        order.setReceivedAmount(received);
        order.setLastPaymentDate(received.signum() > 0
                ? records.stream()
                .filter(record -> PaymentRecord.DIRECTION_RECEIPT.equals(record.getDirection()))
                .filter(record -> record.getAmount() != null && record.getAmount().signum() > 0)
                .filter(record -> record.getId() == null || !reversedPaymentIds.contains(record.getId()))
                .map(PaymentRecord::getPaymentDate)
                .filter(java.util.Objects::nonNull)
                .max(java.time.LocalDate::compareTo)
                .orElse(null)
                : null);
        // A payment-ledger sync must derive settlement from the ledger total.
        // Reusing the stale true flag would otherwise refill receivedAmount to
        // the full receivable immediately after a partial payment reversal.
        outboundReceivablePolicy.apply(order, null);
        outboundOrderRepository.save(order);
    }

    private String normalizeDirection(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Payment direction is required");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!PaymentRecord.DIRECTION_RECEIPT.equals(normalized) && !PaymentRecord.DIRECTION_PAYMENT.equals(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Payment direction must be RECEIPT or PAYMENT");
        }
        return normalized;
    }

    private String normalizeSourceType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Payment source type is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeRequestId(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Request ID is required");
        }
        if (normalized.length() > 120) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Request ID is too long");
        }
        return normalized;
    }

    private void validateSourceExists(String sourceType, Long sourceId) {
        if (sourceId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Payment source ID is required");
        }
        boolean exists = switch (sourceType) {
            case FinancialEventService.SOURCE_OUTBOUND_ORDER -> outboundOrderRepository.existsById(sourceId);
            case FinancialEventService.SOURCE_PURCHASE_ORDER -> purchaseOrderRepository.existsById(sourceId);
            case FinancialEventService.SOURCE_REPAIR -> repairRecordRepository.existsById(sourceId);
            case FinancialEventService.SOURCE_RENTAL_BILL -> rentalBillRepository.existsById(sourceId);
            case "MODIFICATION_WORK_ORDER" -> modificationWorkOrderRepository.existsById(sourceId);
            default -> throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Unsupported payment source type: " + sourceType);
        };
        if (!exists) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Payment source record not found");
        }
    }

    private void lockPaymentSource(String sourceType, Long sourceId, boolean requireReady) {
        if (sourceId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Payment source ID is required");
        }
        switch (sourceType) {
            case FinancialEventService.SOURCE_OUTBOUND_ORDER -> outboundOrderRepository.findByIdForUpdate(sourceId)
                    .orElseThrow(this::paymentSourceNotFound);
            case FinancialEventService.SOURCE_PURCHASE_ORDER -> {
                var order = purchaseOrderRepository.findByIdForUpdate(sourceId)
                        .orElseThrow(this::paymentSourceNotFound);
                if (requireReady && "CANCELED".equalsIgnoreCase(order.getStatus())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Canceled purchase orders cannot accept payments");
                }
            }
            case FinancialEventService.SOURCE_REPAIR -> {
                var repair = repairRecordRepository.findByIdForUpdate(sourceId)
                        .orElseThrow(this::paymentSourceNotFound);
                if (requireReady && !RepairStatus.COMPLETED.code().equals(repair.getStatus())) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Repair payments can only be recorded after the repair is completed");
                }
            }
            case FinancialEventService.SOURCE_RENTAL_BILL -> rentalBillRepository.findByIdForUpdate(sourceId)
                    .orElseThrow(this::paymentSourceNotFound);
            case "MODIFICATION_WORK_ORDER" -> {
                var order = modificationWorkOrderRepository.findByIdForUpdate(sourceId)
                        .orElseThrow(this::paymentSourceNotFound);
                if (requireReady && (!ModificationWorkOrderStatus.COMPLETED.code().equals(order.getStatus())
                        || !"AFTER_SALE".equalsIgnoreCase(order.getWorkOrderType()))) {
                    throw new BusinessException(ResultCode.CONFLICT,
                            "Modification receipts require a completed after-sale work order");
                }
            }
            default -> throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Unsupported payment source type: " + sourceType);
        }
    }

    private BusinessException paymentSourceNotFound() {
        return new BusinessException(ResultCode.NOT_FOUND, "Payment source record not found");
    }

    private BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Payment amount must be greater than zero");
        }
        int integerDigits = Math.max(0, amount.precision() - amount.scale());
        if (amount.scale() > 2 || integerDigits > 12) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Payment amount must fit DECIMAL(14,2)");
        }
        return amount;
    }

    private boolean amountsEqual(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private void validateDirectionForSource(String direction, String sourceType) {
        if (FinancialEventService.SOURCE_PURCHASE_ORDER.equals(sourceType)
                && !PaymentRecord.DIRECTION_PAYMENT.equals(direction)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Purchase orders only accept PAYMENT records");
        }
        if ((FinancialEventService.SOURCE_OUTBOUND_ORDER.equals(sourceType)
                || FinancialEventService.SOURCE_RENTAL_BILL.equals(sourceType)
                || "MODIFICATION_WORK_ORDER".equals(sourceType))
                && !PaymentRecord.DIRECTION_RECEIPT.equals(direction)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "Customer-facing documents only accept RECEIPT records");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
