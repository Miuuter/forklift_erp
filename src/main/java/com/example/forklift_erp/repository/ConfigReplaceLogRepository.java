package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.ConfigReplaceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 配置替换记录数据访问层
 */
@Repository
public interface ConfigReplaceLogRepository extends JpaRepository<ConfigReplaceLog, Long> {
    boolean existsByMachineId(Long machineId);

    boolean existsByNewPartId(Long newPartId);


    /**
     * 根据车辆ID查询该车的所有配置替换记录
     * 按创建时间倒序排列（最新的在前面）
     */
    List<ConfigReplaceLog> findByMachineIdOrderByCreatedAtDesc(Long machineId);
}
