package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.DataImportRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataImportRowRepository extends JpaRepository<DataImportRow, Long> {
    Optional<DataImportRow> findByIdempotencyKey(String idempotencyKey);

    boolean existsByImportTypeAndImportModeAndBusinessKey(String importType, String importMode, String businessKey);
}
