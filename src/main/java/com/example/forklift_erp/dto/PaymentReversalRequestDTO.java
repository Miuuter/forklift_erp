package com.example.forklift_erp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PaymentReversalRequestDTO {
    @NotBlank(message = "Request ID is required")
    @Size(max = 120, message = "Request ID is too long")
    private String requestId;

    @Size(max = 500, message = "Remark is too long")
    private String remark;
}
