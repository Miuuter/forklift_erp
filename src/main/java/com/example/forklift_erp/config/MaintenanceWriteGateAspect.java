package com.example.forklift_erp.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** Applies the maintenance gate outside each service invocation and transaction. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaintenanceWriteGateAspect {
    private final MaintenanceWriteGate gate;

    public MaintenanceWriteGateAspect(MaintenanceWriteGate gate) {
        this.gate = gate;
    }

    @Around("within(com.example.forklift_erp.service..*)"
            + " && (@within(org.springframework.stereotype.Service)"
            + " || @within(org.springframework.stereotype.Component))"
            + " && execution(public * *(..))")
    public Object guardServiceCall(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method targetMethod = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
        if (targetMethod.isAnnotationPresent(MaintenanceOperation.class)) {
            return gate.withExclusiveMaintenance(joinPoint::proceed);
        }
        return gate.withBusinessCall(joinPoint::proceed);
    }
}
