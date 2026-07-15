package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.PaymentRecord;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class PaymentRecordVO {
    private Long id;
    private String paymentNo;
    private String requestId;
    private String direction;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String accountName;
    private String paymentMethod;
    private String sourceType;
    private Long sourceId;
    private Long financialEventId;
    private String remark;
    private Long reversalOfPaymentId;
    private String createdBy;
    private LocalDateTime createdAt;

    public static PaymentRecordVO fromEntity(PaymentRecord entity) {
        PaymentRecordVO vo = new PaymentRecordVO();
        vo.setId(entity.getId());
        vo.setPaymentNo(entity.getPaymentNo());
        vo.setRequestId(entity.getRequestId());
        vo.setDirection(entity.getDirection());
        vo.setAmount(entity.getAmount());
        vo.setPaymentDate(entity.getPaymentDate());
        vo.setAccountName(entity.getAccountName());
        vo.setPaymentMethod(entity.getPaymentMethod());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setFinancialEventId(entity.getFinancialEventId());
        vo.setRemark(entity.getRemark());
        vo.setReversalOfPaymentId(entity.getReversalOfPaymentId());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
