package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.dto.RepairRecordCreateDTO;
import com.example.forklift_erp.dto.RepairRecordVO;
import com.example.forklift_erp.entity.RepairRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RepairRecordService {
    List<RepairRecord> findAll();

    PageResult<RepairRecordVO> findPage(
            String keyword,
            Integer page,
            Integer size,
            Long machineId,
            String repairPerson,
            String status,
            LocalDateTime startDate,
            LocalDateTime endDate
    );

    Optional<RepairRecord> findById(Long id);

    Optional<RepairRecord> findByIdForUpdate(Long id);

    RepairRecordVO toVO(RepairRecord record);

    RepairRecord save(RepairRecord record);

    void deleteById(Long id);

    List<RepairRecord> findByMachineId(Long machineId);

    List<RepairRecord> findByRepairPerson(String repairPerson);

    List<RepairRecord> findByStatus(String status);

    List<RepairRecord> findByDateRange(LocalDateTime start, LocalDateTime end);

    RepairRecordVO create(RepairRecordCreateDTO dto);

    RepairRecordVO update(Long id, RepairRecordCreateDTO dto);

    RepairRecordVO updateStatus(Long id, String status, Long version);

    void delete(Long id, Long version);
}
