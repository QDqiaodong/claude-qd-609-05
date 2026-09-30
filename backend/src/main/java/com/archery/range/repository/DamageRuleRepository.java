package com.archery.range.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamageRule;

import jakarta.persistence.LockModeType;

public interface DamageRuleRepository extends JpaRepository<DamageRule, Long> {

    List<DamageRule> findAllByOrderByEquipTypeAscSeverityAscEffectiveFromDesc();

    List<DamageRule> findByStatusOrderByEquipTypeAscSeverityAscEffectiveFromDesc(String status);

    /**
     * 取检查日当天适用的现行规则：ACTIVE 且生效日期 <= 当天，取生效日期最新的一条。
     * 旧规则即使保留在库，也不会被新检查采用；但已固化进损伤明细的快照不受影响。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DamageRule r where r.equipType = :equipType and r.severity = :severity"
            + " and r.status = 'ACTIVE' and r.effectiveFrom <= :date"
            + " order by r.effectiveFrom desc")
    List<DamageRule> findApplicableForUpdate(@Param("equipType") String equipType,
            @Param("severity") String severity,
            @Param("date") LocalDate date);

    /** 基线指纹只读查询：全部现行规则（含未来生效），按 类型 / 严重程度 / 生效日期 排序 */
    @Query("select r from DamageRule r where r.status = 'ACTIVE'"
            + " order by r.equipType, r.severity, r.effectiveFrom desc")
    List<DamageRule> findAllActive();

    /** 基线指纹：检查日已生效的现行规则（每类型×严重程度取最新一条） */
    @Query("select r from DamageRule r where r.status = 'ACTIVE' and r.effectiveFrom <= :date"
            + " order by r.equipType, r.severity, r.effectiveFrom desc")
    List<DamageRule> findEffectiveOn(@Param("date") LocalDate date);

    boolean existsByEquipTypeAndSeverityAndEffectiveFrom(String equipType, String severity, LocalDate effectiveFrom);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DamageRule r where r.id = :id")
    Optional<DamageRule> findByIdForUpdate(@Param("id") Long id);
}
