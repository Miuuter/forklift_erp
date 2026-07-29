package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.ConfigValue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * 配置项可选值数据访问层
 */
@Repository
public interface ConfigValueRepository extends JpaRepository<ConfigValue, Long> {

    @Query("select c.configItemId from ConfigValue c where c.id = :id")
    Optional<Long> findConfigItemIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConfigValue c where c.id = :id")
    Optional<ConfigValue> findByIdForUpdate(@Param("id") Long id);

    /**
     * 根据配置项ID查询该配置项的所有可选值
     * 按排序号升序排列
     */
    List<ConfigValue> findByConfigItemIdOrderBySortOrderAsc(Long configItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConfigValue c where c.configItemId = :configItemId order by c.sortOrder asc, c.id asc")
    List<ConfigValue> findByConfigItemIdForUpdate(@Param("configItemId") Long configItemId);

    List<ConfigValue> findByConfigItemIdInOrderByConfigItemIdAscSortOrderAsc(List<Long> configItemIds);

    /**
     * 根据配置项ID查询默认值
     */
    ConfigValue findByConfigItemIdAndIsDefaultTrue(Long configItemId);
    /**
     * 根据配置项ID删除该配置项的所有可选值
     */
    void deleteByConfigItemId(Long configItemId);
}
