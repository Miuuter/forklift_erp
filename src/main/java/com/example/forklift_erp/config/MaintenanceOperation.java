package com.example.forklift_erp.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a service operation that must exclude all other service calls. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MaintenanceOperation {
}
