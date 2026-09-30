package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.DamageRule;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.DamageDtos;
import com.archery.range.repository.DamageRuleRepository;

/**
 * 损伤计价规则维护：按器材类型 × 严重程度维护带生效日期的费用 / 送修 / 报废规则。
 *
 * 调价不改旧规则：同一（类型，严重程度）需要调价时，用新的生效日期再发布一条 ACTIVE 规则；
 * 清算单在损伤明细里固化检查当时采用的规则快照，之后任何调价都不改变历史金额。
 */
@Service
public class DamageRuleService {

    private final DamageRuleRepository ruleRepository;

    public DamageRuleService(DamageRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public List<DamageDtos.RuleView> list(String status) {
        // 当天每个 类型×严重程度 实际生效的规则 id 集合（已生效的最新一条 ACTIVE）
        java.util.Set<Long> effectiveIds = new java.util.HashSet<>();
        String lastScope = null;
        for (DamageRule r : ruleRepository.findEffectiveOn(today)) {
            String scope = r.getEquipType() + "#" + r.getSeverity();
            if (!scope.equals(lastScope)) {
                effectiveIds.add(r.getId());
                lastScope = scope;
            }
        }
        List<DamageRule> rules = (status == null || status.isBlank())
                ? ruleRepository.findAllByOrderByEquipTypeAscSeverityAscEffectiveFromDesc()
                : ruleRepository.findByStatusOrderByEquipTypeAscSeverityAscEffectiveFromDesc(status);
        return rules.stream().map(rule -> toView(rule, effectiveIds.contains(rule.getId()))).toList();
    }

    @Transactional
    public DamageDtos.RuleView create(DamageDtos.RuleSaveReq req) {
        if (!RangeDict.isValidEquipType(req.equipType())) {
            throw new BizException("器材类型只能是 反曲弓 / 复合弓 / 传统弓 / 护具 / 箭支");
        }
        if (!RangeDict.isValidDamageSeverity(req.severity())) {
            throw new BizException("严重程度只能是 轻微 / 较重 / 严重报废");
        }
        LocalDate from = req.effectiveFrom();
        if (ruleRepository.existsByEquipTypeAndSeverityAndEffectiveFrom(
                req.equipType(), req.severity(), from)) {
            throw new BizException(RangeDict.equipTypeName(req.equipType()) + " · "
                    + RangeDict.damageSeverityName(req.severity()) + " 在 " + from
                    + " 已存在规则，同一天不能重复发布（调价请选择新的生效日期）");
        }
        if (Boolean.TRUE.equals(req.sendRepair()) && Boolean.TRUE.equals(req.scrap())) {
            throw new BizException("同一条规则不能既送修又报废封存");
        }
        DamageRule rule = new DamageRule();
        rule.setEquipType(req.equipType());
        rule.setSeverity(req.severity());
        rule.setEffectiveFrom(from);
        rule.setFee(req.fee().setScale(2, RoundingMode.HALF_UP));
        rule.setSendRepair(Boolean.TRUE.equals(req.sendRepair()));
        rule.setScrap(Boolean.TRUE.equals(req.scrap()));
        rule.setStatus(DamageRule.STATUS_ACTIVE);
        rule.setNote(req.note());
        rule.setCreatedBy(trimName(req.operator(), "值班经理"));
        rule.setCreatedAt(LocalDateTime.now());
        rule = ruleRepository.save(rule);
        return toView(rule, false);
    }

    /** 停用规则：已固化进历史清算单的快照不受影响，停用后不能再被新检查采用 */
    @Transactional
    public DamageDtos.RuleView retire(Long id, String operator) {
        DamageRule rule = ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("计价规则不存在：id=" + id));
        if (DamageRule.STATUS_RETIRED.equals(rule.getStatus())) {
            throw new BizException("该规则已停用，请勿重复操作");
        }
        rule.setStatus(DamageRule.STATUS_RETIRED);
        ruleRepository.save(rule);
        return toView(rule, false);
    }

    /**
     * 取基线日期当天适用于指定 类型 × 严重程度 的现行规则（已生效的最新一条）。
     * 清算单提交检查与重新核对都走这里，规则内容随即快照进损伤明细。
     */
    @Transactional
    public DamageRule requireApplicable(String equipType, String severity, LocalDate baselineDate) {
        List<DamageRule> rules = ruleRepository.findApplicableForUpdate(equipType, severity, baselineDate);
        return pickApplicable(rules, equipType, severity);
    }

    static DamageRule pickApplicable(List<DamageRule> rules, String equipType, String severity) {
        return rules.stream()
                .filter(r -> r.getEquipType().equals(equipType) && r.getSeverity().equals(severity))
                .findFirst()
                .orElseThrow(() -> new BizException(RangeDict.equipTypeName(equipType) + " · "
                        + RangeDict.damageSeverityName(severity) + " 缺少生效中的计价规则，"
                        + "请先由值班经理在「计价规则」中维护后再提交检查"));
    }

    /** 基线指纹：全部现行规则（含未来生效）的规范化摘要，任一发布 / 停用都会变化 */
    @Transactional(readOnly = true)
    public String currentRuleDigest() {
        return DamageRuleService.digestOf(ruleRepository.findAllActive());
    }

    static String digestOf(List<DamageRule> rules) {
        StringBuilder sb = new StringBuilder();
        rules.stream()
                .sorted((a, b) -> {
                    int c = a.getEquipType().compareTo(b.getEquipType());
                    if (c != 0) {
                        return c;
                    }
                    c = a.getSeverity().compareTo(b.getSeverity());
                    if (c != 0) {
                        return c;
                    }
                    return a.getEffectiveFrom().compareTo(b.getEffectiveFrom());
                })
                .forEach(r -> sb.append(r.getEquipType()).append('|').append(r.getSeverity()).append('|')
                        .append(r.getEffectiveFrom()).append('|')
                        .append(r.getFee().setScale(2, RoundingMode.HALF_UP).toPlainString()).append('|')
                        .append(Boolean.TRUE.equals(r.getSendRepair())).append('|')
                        .append(Boolean.TRUE.equals(r.getScrap())).append(';'));
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String trimName(String name, String who) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (trimmed.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return trimmed;
    }

    private static DamageDtos.RuleView toView(DamageRule rule, boolean effectiveNow) {
        return new DamageDtos.RuleView(
                rule.getId(),
                rule.getEquipType(),
                RangeDict.equipTypeName(rule.getEquipType()),
                rule.getSeverity(),
                RangeDict.damageSeverityName(rule.getSeverity()),
                rule.getEffectiveFrom(),
                rule.getFee().setScale(2, RoundingMode.HALF_UP),
                Boolean.TRUE.equals(rule.getSendRepair()),
                Boolean.TRUE.equals(rule.getScrap()),
                rule.getStatus(),
                DamageRule.STATUS_ACTIVE.equals(rule.getStatus()) ? "现行" : "已停用",
                rule.getNote(),
                rule.getCreatedBy(),
                rule.getCreatedAt(),
                effectiveNow);
    }
}
