package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.StockLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockOperationRecorderTests {

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("actual-user", "password")
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordPartPersistsLogLedgerMovementAndAudit() {
        StockOperationLogRepository logRepository = mock(StockOperationLogRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        when(logRepository.save(org.mockito.ArgumentMatchers.any(StockOperationLog.class))).thenAnswer(invocation -> {
            StockOperationLog log = invocation.getArgument(0);
            log.setId(17L);
            return log;
        });
        StockOperationRecorder recorder = new StockOperationRecorder(logRepository, stockLedgerService, auditService);
        PartInventory part = new PartInventory();
        part.setId(3L);
        part.setPartCode("P-001");
        part.setPartName("Filter");
        part.setWarehouseId(5L);

        StockOperationLog saved = recorder.recordPart(
                part,
                "OUTBOUND",
                2,
                9,
                7,
                new BigDecimal("12.50"),
                "spoofed-user",
                "repair use"
        );

        assertThat(saved.getId()).isEqualTo(17L);
        assertThat(saved.getResourceType()).isEqualTo(StockLedgerService.RESOURCE_PART);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getBeforeQuantity()).isEqualTo(9);
        assertThat(saved.getAfterQuantity()).isEqualTo(7);
        assertThat(saved.getOperator()).isEqualTo("actual-user");
        verify(stockLedgerService).recordMovement(
                eq("OUTBOUND"),
                eq(StockLedgerService.RESOURCE_PART),
                eq(3L),
                eq("P-001"),
                eq("Filter"),
                eq(5L),
                eq(9),
                eq(7),
                eq(new BigDecimal("12.50")),
                eq("actual-user"),
                eq("repair use"),
                eq("STOCK_LOG"),
                eq(17L),
                eq(null),
                eq(java.time.LocalDate.now()),
                eq("OTHER_OUTBOUND"),
                eq(BigDecimal.ZERO),
                eq(null),
                eq(null)
        );
        verify(auditService).record(
                eq("Part stock"),
                eq("OUTBOUND"),
                eq(StockLedgerService.RESOURCE_PART),
                eq(3L),
                eq("P-001"),
                eq("Filter"),
                eq("Part outbound 2"),
                eq("actual-user"),
                eq("repair use"),
                eq("STOCK"),
                eq(17L)
        );
    }

    @Test
    void recordUsesExplicitMovementSourceWhenProvided() {
        StockOperationLogRepository logRepository = mock(StockOperationLogRepository.class);
        StockLedgerService stockLedgerService = mock(StockLedgerService.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        when(logRepository.save(org.mockito.ArgumentMatchers.any(StockOperationLog.class))).thenAnswer(invocation -> {
            StockOperationLog log = invocation.getArgument(0);
            log.setId(18L);
            return log;
        });
        StockOperationRecorder recorder = new StockOperationRecorder(logRepository, stockLedgerService, auditService);

        recorder.record(new StockOperationRecorder.Command(
                "Stocktaking",
                StockLedgerService.RESOURCE_PART,
                3L,
                "P-001",
                "Filter",
                5L,
                "INBOUND",
                4,
                2,
                6,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "spoofed-user",
                "stocktaking",
                "STOCKTAKING",
                99L,
                "Stocktaking adjustment 2 -> 6"
        ));

        verify(stockLedgerService).recordMovement(
                eq("INBOUND"),
                eq(StockLedgerService.RESOURCE_PART),
                eq(3L),
                eq("P-001"),
                eq("Filter"),
                eq(5L),
                eq(2),
                eq(6),
                eq(BigDecimal.ZERO),
                eq("actual-user"),
                eq("stocktaking"),
                eq("STOCKTAKING"),
                eq(99L),
                eq(null),
                eq(java.time.LocalDate.now()),
                eq("OTHER_INBOUND"),
                eq(BigDecimal.ZERO),
                eq(null),
                eq(null)
        );
    }
}
