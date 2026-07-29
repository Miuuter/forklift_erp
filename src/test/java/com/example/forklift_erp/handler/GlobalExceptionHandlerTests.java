package com.example.forklift_erp.handler;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.common.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    @Test
    void genericDatabaseConstraintFailureUsesConflictCodeInResponseBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        Result<Void> result = handler.handleDataIntegrityViolationException(
                new DataIntegrityViolationException("foreign key constraint fails"));

        assertThat(result.getCode()).isEqualTo(ResultCode.CONFLICT.getCode());
        assertThat(result.getMessage()).contains("existing references");
    }
}
