package com.example.forklift_erp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SupplierDTO {
    private Long version;

    @NotBlank(message = "供应商名称不能为空")
    private String supplierName;

    private String supplierType;
    private Boolean active;
    private String contactName;
    private String contactPhone;
    private String address;
    private String taxNumber;
    private String bankAccount;
    private String remarks;
}
