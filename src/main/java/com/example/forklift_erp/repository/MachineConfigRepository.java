package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.MachineConfig;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * 车辆配置明细数据访问层
 */
@Repository
public interface MachineConfigRepository extends JpaRepository<MachineConfig, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MachineConfig m where m.id = :id")
    Optional<MachineConfig> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MachineConfig m where m.machineId = :machineId and m.configItemId = :configItemId")
    Optional<MachineConfig> findByMachineIdAndConfigItemIdForUpdate(
            @Param("machineId") Long machineId,
            @Param("configItemId") Long configItemId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MachineConfig m where m.machineId = :machineId order by m.id")
    List<MachineConfig> findByMachineIdForUpdate(@Param("machineId") Long machineId);

    /**
     * 根据车辆ID查询该车的所有配置
     * 自动生成SQL：SELECT * FROM machine_config WHERE machine_id = ?
     *
     * @param machineId 车辆ID
     * @return 该车的所有配置明细列表
     */
    List<MachineConfig> findByMachineId(Long machineId);

    /**
     * 根据车辆ID删除该车的所有配置
     * 自动生成SQL：DELETE FROM machine_config WHERE machine_id = ?
     * 需要一个事务注解，在 Service 层加 @Transactional
     *
     * @param machineId 车辆ID
     */
    void deleteByMachineId(Long machineId);

    /**
     * 根据配置项ID查询所有使用该配置项的车辆
     * 用于：当某个配置值被删除前，检查是否有车辆正在使用
     *
     * @param configItemId 配置项ID
     * @return 使用该配置项的所有配置记录
     */
    List<MachineConfig> findByConfigItemId(Long configItemId);

    /**
     * 根据配置值ID查询所有使用该配置值的车辆
     * 用于：当某个配置值被删除前，检查是否有车辆正在使用
     *
     * @param configValueId 配置值ID
     * @return 使用该配置值的所有配置记录
     */
    List<MachineConfig> findByConfigValueId(Long configValueId);
}
