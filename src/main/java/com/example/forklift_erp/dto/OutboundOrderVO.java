package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.OutboundOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Data
public class OutboundOrderVO {
    private Long id;
    private Long version;
    private String orderNo;
    private String resourceType;
    private Long resourceId;
    private Long sourceWarehouseId;
    private String resourceCode;
    private String resourceName;
    private String specificationModel;
    private Integer quantity;
    private String unit;
    private Long customerId;
    private String customerName;
    private String customerAddress;
    private String contactName;
    private String contactPhone;
    private String taxOrIdNumber;
    private BigDecimal settlementPrice;
    private BigDecimal unitSalePrice;
    private BigDecimal lineAmount;
    private LocalDate salesDate;
    private BigDecimal salePrice;
    private BigDecimal receivableAmount;
    private BigDecimal receivedAmount;
    private BigDecimal outstandingAmount;
    private LocalDate paymentDueDate;
    private LocalDate lastPaymentDate;
    private Integer overdueDays;
    private Boolean paymentSettled;
    private String paymentRemark;
    private Boolean salesReported;
    private Boolean invoiceApplied;
    private LocalDate salesReportDate;
    private LocalDate invoiceApplicationDate;
    private String invoiceStatus;
    private LocalDate invoiceIssuedDate;
    private String invoiceOriginalName;
    private String invoiceContentType;
    private Long invoiceFileSize;
    private LocalDateTime invoiceUploadedAt;
    private Boolean invoiceFileAvailable;
    private String registrationStatus;
    private String contractType;
    private String contractOriginalName;
    private String contractContentType;
    private Long contractFileSize;
    private LocalDateTime contractUploadedAt;
    private Boolean contractFileAvailable;
    private String orderRemark;
    private String operator;
    private Long stockOperationLogId;
    private Boolean financialPosted;
    private Boolean isLocked;
    private Boolean resourceLockedByOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OutboundOrderVO fromEntity(OutboundOrder entity) {
        OutboundOrderVO vo = new OutboundOrderVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setOrderNo(entity.getOrderNo());
        vo.setResourceType(entity.getResourceType());
        vo.setResourceId(entity.getResourceId());
        vo.setSourceWarehouseId(entity.getSourceWarehouseId());
        vo.setResourceCode(entity.getResourceCode());
        vo.setResourceName(entity.getResourceName());
        vo.setSpecificationModel(entity.getSpecificationModel());
        vo.setQuantity(entity.getQuantity());
        vo.setUnit(entity.getUnit());
        vo.setCustomerId(entity.getCustomerId());
        vo.setCustomerName(entity.getCustomerName());
        vo.setCustomerAddress(entity.getCustomerAddress());
        vo.setContactName(entity.getContactName());
        vo.setContactPhone(entity.getContactPhone());
        vo.setTaxOrIdNumber(entity.getTaxOrIdNumber());
        vo.setSettlementPrice(entity.getSettlementPrice());
        vo.setUnitSalePrice(entity.getUnitSalePrice());
        vo.setLineAmount(entity.getLineAmount());
        vo.setSalesDate(entity.getSalesDate());
        vo.setSalePrice(entity.getSalePrice());
        vo.setReceivableAmount(defaultAmount(entity.getReceivableAmount(), entity.getSettlementPrice()));
        vo.setReceivedAmount(defaultAmount(entity.getReceivedAmount(), BigDecimal.ZERO));
        vo.setOutstandingAmount(outstandingAmount(vo.getReceivableAmount(), vo.getReceivedAmount()));
        vo.setPaymentDueDate(entity.getPaymentDueDate());
        vo.setLastPaymentDate(entity.getLastPaymentDate());
        vo.setOverdueDays(overdueDays(vo.getOutstandingAmount(), entity.getPaymentDueDate()));
        vo.setPaymentSettled(Boolean.TRUE.equals(entity.getPaymentSettled()));
        vo.setPaymentRemark(entity.getPaymentRemark());
        vo.setSalesReported(Boolean.TRUE.equals(entity.getSalesReported()));
        vo.setInvoiceApplied(Boolean.TRUE.equals(entity.getInvoiceApplied()));
        vo.setSalesReportDate(entity.getSalesReportDate());
        vo.setInvoiceApplicationDate(entity.getInvoiceApplicationDate());
        vo.setInvoiceStatus(entity.getInvoiceStatus());
        vo.setInvoiceIssuedDate(entity.getInvoiceIssuedDate());
        vo.setInvoiceOriginalName(entity.getInvoiceOriginalName());
        vo.setInvoiceContentType(entity.getInvoiceContentType());
        vo.setInvoiceFileSize(entity.getInvoiceFileSize());
        vo.setInvoiceUploadedAt(entity.getInvoiceUploadedAt());
        vo.setInvoiceFileAvailable(entity.getInvoiceStoredFileName() != null && !entity.getInvoiceStoredFileName().isBlank());
        vo.setRegistrationStatus(entity.getRegistrationStatus());
        vo.setContractType(entity.getContractType());
        vo.setContractOriginalName(entity.getContractOriginalName());
        vo.setContractContentType(entity.getContractContentType());
        vo.setContractFileSize(entity.getContractFileSize());
        vo.setContractUploadedAt(entity.getContractUploadedAt());
        vo.setContractFileAvailable(entity.getContractStoredFileName() != null && !entity.getContractStoredFileName().isBlank());
        vo.setOrderRemark(entity.getOrderRemark());
        vo.setOperator(entity.getOperator());
        vo.setStockOperationLogId(entity.getStockOperationLogId());
        vo.setFinancialPosted(Boolean.TRUE.equals(entity.getFinancialPosted()));
        vo.setIsLocked(Boolean.TRUE.equals(entity.getIsLocked()));
        vo.setResourceLockedByOrder(Boolean.TRUE.equals(entity.getResourceLockedByOrder()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private static BigDecimal defaultAmount(BigDecimal value, BigDecimal fallback) {
        BigDecimal amount = value == null ? fallback : value;
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal outstandingAmount(BigDecimal receivableAmount, BigDecimal receivedAmount) {
        BigDecimal amount = defaultAmount(receivableAmount, BigDecimal.ZERO)
                .subtract(defaultAmount(receivedAmount, BigDecimal.ZERO));
        return amount.signum() < 0 ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static Integer overdueDays(BigDecimal outstandingAmount, LocalDate paymentDueDate) {
        if (paymentDueDate == null || outstandingAmount == null || outstandingAmount.signum() <= 0) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(paymentDueDate, LocalDate.now());
        return days > 0 ? Math.toIntExact(days) : 0;
    }
}
