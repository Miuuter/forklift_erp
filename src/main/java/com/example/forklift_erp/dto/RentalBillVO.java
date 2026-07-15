package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.RentalBill;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RentalBillVO {
    private Long id;
    private Long rentalId;
    private LocalDate billPeriod;
    private LocalDate businessDate;
    private BigDecimal amount;
    private BigDecimal receivedAmount;
    private BigDecimal outstandingAmount;
    private String status;

    public static RentalBillVO fromEntity(RentalBill entity, BigDecimal receivedAmount) {
        RentalBillVO vo = new RentalBillVO();
        BigDecimal billed = entity.getAmount() == null ? BigDecimal.ZERO : entity.getAmount();
        BigDecimal received = receivedAmount == null ? BigDecimal.ZERO : receivedAmount;
        vo.setId(entity.getId());
        vo.setRentalId(entity.getRentalId());
        vo.setBillPeriod(entity.getBillPeriod());
        vo.setBusinessDate(entity.getBusinessDate());
        vo.setAmount(billed);
        vo.setReceivedAmount(received);
        vo.setOutstandingAmount(billed.subtract(received).max(BigDecimal.ZERO));
        vo.setStatus(entity.getStatus());
        return vo;
    }
}
