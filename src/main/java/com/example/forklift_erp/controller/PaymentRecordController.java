package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.PaymentRecordCreateDTO;
import com.example.forklift_erp.dto.PaymentRecordVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.PaymentRecordService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@PreAuthorize(PermissionCodes.HAS_STOCK_ADJUST)
public class PaymentRecordController {
    private final PaymentRecordService paymentRecordService;

    public PaymentRecordController(PaymentRecordService paymentRecordService) {
        this.paymentRecordService = paymentRecordService;
    }

    @GetMapping
    public Result<List<PaymentRecordVO>> list(
            @RequestParam String sourceType,
            @RequestParam Long sourceId
    ) {
        return Result.success(paymentRecordService.findBySource(sourceType, sourceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<PaymentRecordVO> create(@Valid @RequestBody PaymentRecordCreateDTO request) {
        return Result.success("Payment recorded", paymentRecordService.create(request));
    }

    @PostMapping("/{id}/reverse")
    public Result<PaymentRecordVO> reverse(@PathVariable Long id, @RequestParam(required = false) String remark) {
        return Result.success("Payment reversed", paymentRecordService.reverse(id, remark));
    }
}
