package com.example.forklift_erp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    /**
     * Claims an internal ledger key without truncating the authoritative key.
     * Stock keys may be longer than the public request-id column, so the
     * claim table stores a deterministic binary-safe digest while the domain
     * table retains and validates the original key.
     */
    public boolean claimTechnicalKey(String scope, String technicalKey) {
        if (technicalKey == null) {
            return true;
        }
        return claim(scope, "TECH:" + sha256(technicalKey));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for technical idempotency", exception);
        }
    }
}
