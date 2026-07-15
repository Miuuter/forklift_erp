package com.example.forklift_erp.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "outbound_order")
public class OutboundOrder implements CollaborativeResource {

    public static final String RESOURCE_MACHINE = "MACHINE";
    public static final String RESOURCE_PART = "PART";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "order_no", nullable = false, unique = true, length = 80)
    private String orderNo;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "source_warehouse_id")
    private Long sourceWarehouseId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 120)
    private String resourceName;

    @Column(name = "specification_model", length = 120)
    private String specificationModel;

    @Column(name = "quantity")
    private Integer quantity = 1;

    @Column(length = 20)
    private String unit;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "customer_address", length = 255)
    private String customerAddress;

    @Column(name = "contact_name", length = 80)
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "tax_or_id_number", length = 100)
    private String taxOrIdNumber;

    @Column(name = "settlement_price", precision = 12, scale = 2)
    private BigDecimal settlementPrice;

    @Column(name = "unit_sale_price", precision = 12, scale = 2)
    private BigDecimal unitSalePrice;

    @Column(name = "line_amount", precision = 14, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "sales_date")
    private LocalDate salesDate;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "receivable_amount", precision = 12, scale = 2)
    private BigDecimal receivableAmount;

    @Column(name = "received_amount", precision = 12, scale = 2)
    private BigDecimal receivedAmount = BigDecimal.ZERO;

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    @Column(name = "last_payment_date")
    private LocalDate lastPaymentDate;

    @Column(name = "payment_settled")
    private Boolean paymentSettled = false;

    @Column(name = "payment_remark", length = 500)
    private String paymentRemark;

    @Column(name = "sales_reported")
    private Boolean salesReported = false;

    @Column(name = "invoice_applied")
    private Boolean invoiceApplied = false;

    @Column(name = "sales_report_date")
    private LocalDate salesReportDate;

    @Column(name = "invoice_application_date")
    private LocalDate invoiceApplicationDate;

    @Column(name = "invoice_status", length = 120)
    private String invoiceStatus;

    @Column(name = "invoice_issued_date")
    private LocalDate invoiceIssuedDate;

    @Column(name = "invoice_stored_file_name", length = 255)
    private String invoiceStoredFileName;

    @Column(name = "invoice_original_name", length = 255)
    private String invoiceOriginalName;

    @Column(name = "invoice_content_type", length = 120)
    private String invoiceContentType;

    @Column(name = "invoice_file_size")
    private Long invoiceFileSize;

    @Column(name = "invoice_uploaded_at")
    private LocalDateTime invoiceUploadedAt;

    @Column(name = "registration_status", length = 120)
    private String registrationStatus;

    @Column(name = "contract_type", length = 80)
    private String contractType;

    @Column(name = "contract_stored_file_name", length = 255)
    private String contractStoredFileName;

    @Column(name = "contract_original_name", length = 255)
    private String contractOriginalName;

    @Column(name = "contract_content_type", length = 120)
    private String contractContentType;

    @Column(name = "contract_file_size")
    private Long contractFileSize;

    @Column(name = "contract_uploaded_at")
    private LocalDateTime contractUploadedAt;

    @Column(name = "order_remark", length = 500)
    private String orderRemark;

    @Column(length = 50)
    private String operator;

    @Column(name = "stock_operation_log_id")
    private Long stockOperationLogId;

    @Column(name = "financial_posted")
    private Boolean financialPosted = false;

    @Column(name = "is_locked")
    private Boolean isLocked = false;

    @Column(name = "resource_locked_by_order")
    private Boolean resourceLockedByOrder = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_modified_by", length = 50)
    private String lastModifiedBy;

    @Column(name = "last_modified_role", length = 30)
    private String lastModifiedRole;

    @Column(name = "last_modified_priority")
    private Integer lastModifiedPriority = 0;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
