package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.PaymentRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    Optional<PaymentRecord> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentRecord> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentRecord payment where payment.id = :id")
    Optional<PaymentRecord> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentRecord payment where payment.requestId = :requestId")
    Optional<PaymentRecord> findByRequestIdForUpdate(@Param("requestId") String requestId);

    Optional<PaymentRecord> findByReversalOfPaymentId(Long reversalOfPaymentId);

    List<PaymentRecord> findBySourceTypeAndSourceIdOrderByPaymentDateAscIdAsc(String sourceType, Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select payment
            from PaymentRecord payment
            where payment.sourceType = :sourceType
              and payment.sourceId = :sourceId
              and payment.direction = :direction
            order by payment.paymentDate asc, payment.id asc
            """)
    List<PaymentRecord> findBySourceTypeAndSourceIdAndDirectionForUpdate(
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId,
            @Param("direction") String direction
    );

    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);

    @Query("""
            select coalesce(sum(p.amount), 0)
            from PaymentRecord p
            where p.sourceType = :sourceType
              and p.sourceId = :sourceId
              and p.direction = :direction
            """)
    BigDecimal totalForSource(
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId,
            @Param("direction") String direction
    );
}
