package com.example.forklift_erp.config;

import com.example.forklift_erp.common.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.slf4j.MDC;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ResultResponseAdviceTests {

    @AfterEach
    void clearRequestContext() {
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
    }

    @Test
    void addsCorrelationIdToResultBodyWithoutChangingExistingFields() throws Exception {
        MDC.put(RequestCorrelationFilter.MDC_KEY, "repair-submit-7");
        ResultResponseAdvice advice = new ResultResponseAdvice();
        Result<Void> body = Result.error("版本冲突");
        Method method = ResultResponseAdviceTests.class.getDeclaredMethod("sample");
        MethodParameter parameter = new MethodParameter(method, -1);

        Object result = advice.beforeBodyWrite(
                body,
                parameter,
                org.springframework.http.MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class,
                null,
                null
        );

        assertThat(result).isSameAs(body);
        assertThat(body.getCode()).isEqualTo(500);
        assertThat(body.getMessage()).isEqualTo("版本冲突");
        assertThat(body.getRequestId()).isEqualTo("repair-submit-7");
    }

    private static Result<Void> sample() {
        return Result.success();
    }
}
