package com.archery.range.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamagePricingRule;

public interface DamagePricingRuleRepository extends JpaRepository<DamagePricingRule, Long> {

    List<DamagePricingRule> findAllByOrderByEquipTypeAscSeverityAscEffectiveDateDescIdDesc();

    /**
     * 登记损伤时取价：生效日期 <= 检查开始日的最新 ACTIVE 规则。
     * 一旦取价，费用 / 送修 / 报废快照固化到损伤项，后续规则变化不影响本单。
     */
    @Query("select r from DamagePricingRule r where r.equipType = :equipType and r.severity = :severity"
            + " and r.status = 'ACTIVE' and r.effectiveDate <= :date"
            + " order by r.effectiveDate desc, r.id desc")
    List<DamagePricingRule> findApplicable(@Param("equipType") String equipType,
            @Param("severity") String severity,
            @Param("date") LocalDate date);

    default Optional<DamagePricingRule> findApplicableOne(String equipType, String severity, LocalDate date) {
        List<DamagePricingRule> rules = findApplicable(equipType, severity, date);
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules.get(0));
    }
}
