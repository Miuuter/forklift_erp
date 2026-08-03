package com.example.forklift_erp.config;

import com.example.forklift_erp.common.Result;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds the server-side correlation id to normal controller responses.
 * The response header is still the canonical transport-level value; this field
 * makes copied API errors actionable when they are reported without headers.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ResultResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body instanceof Result<?> result && result.getRequestId() == null) {
            result.withRequestId(RequestCorrelationFilter.currentRequestId());
        }
        return body;
    }
}
