package com.example.forklift_erp.dto;

import com.example.forklift_erp.entity.Supplier;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SupplierVO {
    private Long id;
    private Long version;
    private String supplierName;
    private String supplierType;
    private Boolean active;
    private String contactName;
    private String contactPhone;
    private String address;
    private String taxNumber;
    private String bankAccount;
    private String remarks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SupplierVO fromEntity(Supplier entity) {
        SupplierVO vo = new SupplierVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setSupplierName(entity.getSupplierName());
        vo.setSupplierType(entity.getSupplierType());
        vo.setActive(Boolean.TRUE.equals(entity.getActive()));
        vo.setContactName(entity.getContactName());
        vo.setContactPhone(entity.getContactPhone());
        vo.setAddress(entity.getAddress());
        vo.setTaxNumber(entity.getTaxNumber());
        vo.setBankAccount(entity.getBankAccount());
        vo.setRemarks(entity.getRemarks());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
