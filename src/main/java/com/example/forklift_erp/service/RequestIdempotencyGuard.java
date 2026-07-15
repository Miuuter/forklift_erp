package com.example.forklift_erp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

@Component
public class RequestIdempotencyGuard {

    @PersistenceContext
    private EntityManager entityManager;

    public boolean claim(String scope, String requestId) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT IGNORE INTO request_idempotency (scope, request_id)
                        VALUES (:scope, :requestId)
                        """)
                .setParameter("scope", scope)
                .setParameter("requestId", requestId)
                .executeUpdate();
        return inserted == 1;
    }
}
