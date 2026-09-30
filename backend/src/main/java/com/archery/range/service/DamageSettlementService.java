package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BaselineStaleException;
import com.archery.range.common.BizException;
import com.archery.range.domain.DamagePricingRule;
import com.archery.range.domain.DamageSettlement;
import com.archery.range.domain.DamageSettlementItem;
import com.archery.range.domain.DamageSettlementLog;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Member;
import com.archery.range.domain.MemberDebt;
import com.archery.range.domain.MemberDebtPayment;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.DamageDtos;
import com.archery.range.repository.DamagePricingRuleRepository;
import com.archery.range.repository.DamageSettlementItemRepository;
import com.archery.range.repository.DamageSettlementLogRepository;
import com.archery.range.repository.DamageSettlementRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.MemberDebtPaymentRepository;
import com.archery.range.repository.MemberDebtRepository;
import com.archery.range.repository.MemberRepository;

/**
 * 器材损伤清算台。
 *
 * 主流程：
 * 发起检查（器材 RENTED → SETTLING「归还处理中」，固化租借人 / 器材状态 / 会员余额基线）
 *   → 逐项登记损伤（登记瞬间固化计价规则快照：费用 / 送修 / 报废）
 *   → 结束检查（无损）或保持有损伤待结清
 *   → 结清前预览费用明细、余额变化与器材去向，并核对基线
 *   → 结清（单一事务：扣余额到 0、不足部分形成待缴并暂停租借、器材去向落库、轨迹留痕）
 *   → 补缴解限 / 经理减免 / 会员异议（异议期间金额冻结）。
 *
 * 关键不变量：
 * 1. SETTLING 期间器材不能再次租借、切换维修状态或清除原租借人；处于安全联锁（LOCKED）的器材必须先放行。
 * 2. 管理员原检查结果（部位 / 严重度 / 说明 / 责任会员 / 规则快照）永不被改写，减免与异议只改金额与处理列。
 * 3. 结清前任一基线（租借人 / 器材状态 / 会员余额）变化，清算单转 RECHECK，核对后才能结清。
 * 4. 结清事务要么全部成功，要么整体回到检查前状态；基线漂移时同事务把单据转为 RECHECK 并提交
 *    （BaselineStaleException 不触发回滚），不允许沿用旧结果。
 */
@Service
public class DamageSettlementService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter NO_STAMP_DEBT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DamageSettlementRepository settlementRepository;
    private final DamageSettlementItemRepository itemRepository;
    private final DamageSettlementLogRepository logRepository;
    private final DamagePricingRuleRepository ruleRepository;
    private final MemberDebtRepository debtRepository;
    private final MemberDebtPaymentRepository paymentRepository;
    private final EquipmentRepository equipmentRepository;
    private final MemberRepository memberRepository;

    public DamageSettlementService(DamageSettlementRepository settlementRepository,
            DamageSettlementItemRepository itemRepository,
            DamageSettlementLogRepository logRepository,
            DamagePricingRuleRepository ruleRepository,
            MemberDebtRepository debtRepository,
            MemberDebtPaymentRepository paymentRepository,
            EquipmentRepository equipmentRepository,
            MemberRepository memberRepository) {
        this.settlementRepository = settlementRepository;
        this.itemRepository = itemRepository;
        this.logRepository = logRepository;
        this.ruleRepository = ruleRepository;
        this.debtRepository = debtRepository;
        this.paymentRepository = paymentRepository;
        this.equipmentRepository = equipmentRepository;
        this.memberRepository = memberRepository;
    }

    // ================= 计价规则维护 =================

    @Transactional(readOnly = true)
    public List<DamageDtos.RuleView> listRules() {
        return ruleRepository.findAllByOrderByEquipTypeAscSeverityAscEffectiveDateDescIdDesc().stream()
                .map(DamageSettlementService::toRuleView).toList();
    }

    /** 新增一条带生效日期的计价规则（同类型 + 严重度 + 生效日期唯一）。 */
    @Transactional
    public DamageDtos.RuleView createRule(DamageDtos.RuleSaveReq req) {
        if (!RangeDict.isValidEquipType(req.equipType())) {
            throw new BizException("器材类型只能是 反曲弓 / 复合弓 / 传统弓 / 护具 / 箭支");
        }
        if (!RangeDict.isValidDamageSeverity(req.severity())) {
            throw new BizException("严重程度只能是 轻微 / 中等 / 严重");
        }
        if (req.repairFlag() && req.scrapFlag()) {
            throw new BizException("同一档规则不能既送修又报废封存");
        }
        BigDecimal fee = req.fee().setScale(2, RoundingMode.HALF_UP);
        String operator = requireName(req.operator(), "维护人");
        DamagePricingRule rule = new DamagePricingRule();
        rule.setEquipType(req.equipType());
        rule.setSeverity(req.severity());
        rule.setEffectiveDate(req.effectiveDate());
        rule.setFee(fee);
        rule.setRepairFlag(req.repairFlag());
        rule.setScrapFlag(req.scrapFlag());
        rule.setStatus(DamagePricingRule.STATUS_ACTIVE);
        rule.setNote(trim(req.note()));
        rule.setCreatedBy(operator);
        rule.setCreatedAt(LocalDateTime.now());
        return toRuleView(ruleRepository.save(rule));
    }

    // ================= 待检查租借 =================

    /** 待检查租借列表：租出（含归还处理中）的器材，标出安全锁定与在途清算。 */
    @Transactional(readOnly = true)
    public List<DamageDtos.RentedEquipView> listRented() {
        List<DamageSettlement> openSettlements = settlementRepository.findByStatusNotOrderByIdDesc("SETTLED");
        List<Equipment> equips = equipmentRepository.findAllByOrderByEquipCodeAsc().stream()
                .filter(e -> "RENTED".equals(e.getStatus())
                        || "SETTLING".equals(e.getStatus())
                        || "LOCKED".equals(e.getStatus()) && e.getRenterId() != null)
                .toList();
        List<DamageDtos.RentedEquipView> result = new ArrayList<>();
        for (Equipment e : equips) {
            Long openId = openSettlements.stream()
                    .filter(s -> s.getEquipmentId().equals(e.getId()))
                    .map(DamageSettlement::getId).findFirst().orElse(null);
            result.add(new DamageDtos.RentedEquipView(
                    e.getId(), e.getEquipCode(), e.getType(), RangeDict.equipTypeName(e.getType()),
                    e.getBrand(), e.getRenterId(), e.getRenterName(), e.getRentedAt(),
                    e.getStatus(), RangeDict.equipStatusName(e.getStatus()),
                    "LOCKED".equals(e.getStatus()), "SETTLING".equals(e.getStatus()), openId));
        }
        return result;
    }

    // ================= 发起检查 =================

    @Transactional
    public DamageDtos.SettlementView start(Long equipmentId, DamageDtos.StartReq req) {
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BizException("器材不存在：id=" + equipmentId));
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 处于安全锁定（停射事件未放行），"
                    + "必须先完成放行，不能发起损伤检查");
        }
        if ("SETTLING".equals(equipment.getStatus()) || settlementRepository.existsOpenByEquipment(equipmentId)) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 已在归还处理中，请在原清算单继续处理");
        }
        if (!"RENTED".equals(equipment.getStatus()) || equipment.getRenterId() == null) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 未处于租出状态，不能发起损伤检查");
        }
        Member member = memberRepository.findByIdForUpdate(equipment.getRenterId())
                .orElseThrow(() -> new BizException("租借会员不存在：id=" + equipment.getRenterId()));

        String inspector = requireName(req.inspector(), "器材管理员");
        LocalDateTime now = LocalDateTime.now();

        DamageSettlement settlement = new DamageSettlement();
        settlement.setSettleNo(nextSettleNo());
        settlement.setEquipmentId(equipment.getId());
        settlement.setEquipCode(equipment.getEquipCode());
        settlement.setEquipType(equipment.getType());
        settlement.setMemberId(member.getId());
        settlement.setMemberName(member.getName());
        settlement.setStatus(DamageSettlement.STATUS_INSPECTING);
        settlement.setBaselineEquipStatus(equipment.getStatus());
        settlement.setBaselineRenterId(equipment.getRenterId());
        settlement.setBaselineRenterName(equipment.getRenterName());
        settlement.setBaselineRentedAt(equipment.getRentedAt());
        settlement.setBaselineBalance(member.getBalance().setScale(2, RoundingMode.HALF_UP));
        settlement.setInspector(inspector);
        settlement.setCreatedAt(now);
        settlementRepository.save(settlement);

        // 检查开始：器材进入「归还处理中」，租借人字段原样保留（结清前不清除）
        equipment.setStatus("SETTLING");
        equipmentRepository.save(equipment);

        log(settlement.getId(), "START", inspector, "KEEPER",
                "发起损伤检查：器材 " + equipment.getEquipCode() + "（" + RangeDict.equipTypeName(equipment.getType())
                        + "）进入归还处理中；租借人 " + member.getName()
                        + "，检查时会员余额基线 " + settlement.getBaselineBalance() + " 元");
        return detail(settlement.getId());
    }

    // ================= 检查中：登记 / 移除损伤项 =================

    @Transactional(noRollbackFor = BaselineStaleException.class)
    public DamageDtos.SettlementView addDamage(Long settlementId, DamageDtos.DamageItemReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        requireInspecting(settlement);
        Equipment equipment = equipmentRepository.findByIdForUpdate(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("器材不存在：id=" + settlement.getEquipmentId()));
        if (!"SETTLING".equals(equipment.getStatus())) {
            // 安全联锁等外部流程改动了器材状态：同事务转待核对（不回滚）并提示
            markRecheck(settlement, List.of("器材状态已由「归还处理中」变为「"
                    + RangeDict.equipStatusName(equipment.getStatus()) + "」"));
            throw new BaselineStaleException("器材状态已变化，清算单已转为待重新核对，不能继续登记损伤",
                    List.of("器材状态变化"));
        }

        String part = requireText(req.part(), 64, "损伤部位");
        if (!RangeDict.isValidDamageSeverity(req.severity())) {
            throw new BizException("严重程度只能是 轻微 / 中等 / 严重");
        }
        String description = requireText(req.description(), 500, "现场说明");
        Member responsible = memberRepository.findById(req.responsibleMemberId())
                .orElseThrow(() -> new BizException("责任会员不存在：id=" + req.responsibleMemberId()));
        memberServiceRejectMerged(responsible);

        // 登记瞬间取价并固化快照：检查开始后调价不影响本单
        DamagePricingRule rule = ruleRepository
                .findApplicableOne(settlement.getEquipType(), req.severity(), settlement.getCreatedAt().toLocalDate())
                .orElseThrow(() -> new BizException("「" + RangeDict.equipTypeName(settlement.getEquipType())
                        + " · " + RangeDict.damageSeverityName(req.severity()) + "」缺少生效日期不晚于检查日期的计价规则，"
                        + "请先维护计价规则"));

        DamageSettlementItem item = new DamageSettlementItem();
        item.setSettlementId(settlementId);
        item.setItemSeq(itemRepository.countBySettlementId(settlementId) + 1);
        item.setPart(part);
        item.setSeverity(req.severity());
        item.setDescription(description);
        item.setResponsibleId(responsible.getId());
        item.setResponsibleName(responsible.getName());
        item.setRuleId(rule.getId());
        item.setSnapRuleCode(rule.getEquipType() + "-" + rule.getSeverity());
        item.setSnapEffectiveDate(rule.getEffectiveDate());
        item.setSnapFee(rule.getFee().setScale(2, RoundingMode.HALF_UP));
        item.setSnapRepairFlag(rule.isRepairFlag());
        item.setSnapScrapFlag(rule.isScrapFlag());
        item.setFinalFee(item.getSnapFee());
        item.setCreatedAt(LocalDateTime.now());
        itemRepository.save(item);

        log(settlementId, "ADD_DAMAGE", settlement.getInspector(), "KEEPER",
                "登记损伤 #" + item.getItemSeq() + "：部位「" + part + "」· "
                        + RangeDict.damageSeverityName(req.severity()) + "；责任会员 " + responsible.getName()
                        + "；适用规则 " + item.getSnapRuleCode() + "（" + rule.getEffectiveDate() + " 生效）"
                        + "，费用 " + item.getSnapFee() + " 元"
                        + (rule.isScrapFlag() ? "，规则判定报废封存" : rule.isRepairFlag() ? "，规则判定送修" : "")
                        + "；现场说明：" + description);
        return detail(settlementId);
    }

    @Transactional
    public DamageDtos.SettlementView removeDamage(Long settlementId, Long itemId, DamageDtos.OperatorReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        requireInspecting(settlement);
        DamageSettlementItem item = requireItem(settlementId, itemId);
        if (item.getDisputeStatus() != null) {
            throw new BizException("损伤 #" + item.getItemSeq() + " 已进入异议流程，不能移除");
        }
        if (item.isWaivedFlag()) {
            throw new BizException("损伤 #" + item.getItemSeq() + " 已有经理减免记录，不能移除");
        }
        String operator = requireName(req.operator(), "操作人");
        itemRepository.delete(item);
        log(settlementId, "REMOVE_DAMAGE", operator, "KEEPER",
                "移除损伤 #" + item.getItemSeq() + "：" + item.getPart() + "（原因：" + requireText(
                        req.note() == null ? "" : req.note(), 500, "移除原因") + "）");
        return detail(settlementId);
    }

    /** 结束检查：检查中 → 待结清（无损器材可零损伤结束，结清后回库）。 */
    @Transactional
    public DamageDtos.SettlementView finishInspection(Long settlementId, DamageDtos.OperatorReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        requireInspecting(settlement);
        String operator = requireName(req.operator(), "器材管理员");
        List<DamageSettlementItem> items = itemRepository.findBySettlementIdOrderByItemSeqAsc(settlementId);
        settlement.setStatus(DamageSettlement.STATUS_PENDING_SETTLE);
        settlementRepository.save(settlement);
        log(settlementId, "FINISH_INSPECT", operator, "KEEPER",
                items.isEmpty() ? "检查结束：未登记损伤，按无损归还结清后器材回库"
                        : "检查结束：共登记 " + items.size() + " 项损伤，进入待结清");
        return detail(settlementId);
    }

    /** 放弃清算：器材回到租出（仅限检查中，且未结束检查）。 */
    @Transactional(noRollbackFor = BaselineStaleException.class)
    public DamageDtos.SettlementView abandon(Long settlementId, DamageDtos.OperatorReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        requireInspecting(settlement);
        Equipment equipment = equipmentRepository.findByIdForUpdate(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("器材不存在：id=" + settlement.getEquipmentId()));
        if (!"SETTLING".equals(equipment.getStatus())) {
            throw new BizException("器材当前为「" + RangeDict.equipStatusName(equipment.getStatus())
                    + "」，不能放弃清算");
        }
        if (!equipment.getRenterId().equals(settlement.getBaselineRenterId())) {
            markRecheck(settlement, List.of("器材租借人已由 " + settlement.getBaselineRenterName() + " 变更"));
            throw new BaselineStaleException("租借人基线已变化，已转待重新核对，不能直接放弃",
                    List.of("租借人变化"));
        }
        equipment.setStatus("RENTED");
        equipmentRepository.save(equipment);
        // 清算单连同明细与轨迹一起物理删除，整体回到检查前状态（不留在途单据）
        itemRepository.deleteAll(itemRepository.findBySettlementIdOrderByItemSeqAsc(settlementId));
        logRepository.deleteAll(logRepository.findBySettlementIdOrderByIdAsc(settlementId));
        settlementRepository.delete(settlement);
        return null;
    }

    // ================= 经理减免 =================

    @Transactional
    public DamageDtos.SettlementView waive(Long settlementId, Long itemId, DamageDtos.WaiveReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        if (DamageSettlement.STATUS_INSPECTING.equals(settlement.getStatus())) {
            throw new BizException("检查尚未结束，不能减免；请先结束检查");
        }
        if (DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已结清，减免请走会员异议处理");
        }
        DamageSettlementItem item = requireItem(settlementId, itemId);
        if (DamageSettlementItem.DISPUTE_DISPUTING.equals(item.getDisputeStatus())) {
            throw new BizException("损伤 #" + item.getItemSeq() + " 异议处理中，金额冻结，不能减免");
        }
        BigDecimal after = req.finalFee().setScale(2, RoundingMode.HALF_UP);
        if (after.compareTo(item.getSnapFee()) >= 0) {
            throw new BizException("减免后金额必须小于管理员原检查金额 " + item.getSnapFee() + " 元");
        }
        if (DamageSettlementItem.DISPUTE_REDUCE.equals(item.getDisputeStatus())
                || DamageSettlementItem.DISPUTE_REVOKE.equals(item.getDisputeStatus())) {
            throw new BizException("损伤 #" + item.getItemSeq() + " 已经异议处理定费，不能再直接减免");
        }
        String manager = requireName(req.operator(), "值班经理");
        String reason = requireText(req.reason(), 500, "减免理由");
        BigDecimal before = item.getFinalFee();
        item.setFinalFee(after);
        item.setWaivedFlag(true);
        item.setWaivedBy(manager);
        item.setWaivedAt(LocalDateTime.now());
        item.setWaivedReason(reason);
        itemRepository.save(item);
        log(settlementId, "WAIVE", manager, "MANAGER",
                "减免损伤 #" + item.getItemSeq() + "（" + item.getPart() + "）：" + before + " 元 → " + after
                        + " 元；管理员原检查金额 " + item.getSnapFee() + " 元保留不变；理由：" + reason);
        return detail(settlementId);
    }

    // ================= 会员异议 / 经理处理 =================

    @Transactional
    public DamageDtos.SettlementView dispute(Long settlementId, Long itemId, DamageDtos.DisputeReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        if (DamageSettlement.STATUS_INSPECTING.equals(settlement.getStatus())) {
            throw new BizException("检查尚未结束，不能提出异议");
        }
        DamageSettlementItem item = requireItem(settlementId, itemId);
        if (DamageSettlementItem.DISPUTE_DISPUTING.equals(item.getDisputeStatus())) {
            throw new BizException("该损伤项已在异议处理中，金额已冻结");
        }
        if (DamageSettlementItem.DISPUTE_REVOKE.equals(item.getDisputeStatus())) {
            throw new BizException("该损伤项费用已撤销为 0，不能再提出异议");
        }
        String operator = requireName(req.operator(), "操作人");
        String reason = requireText(req.reason(), 500, "异议理由");
        item.setDisputeStatus(DamageSettlementItem.DISPUTE_DISPUTING);
        item.setDisputeReason(reason);
        item.setDisputedAt(LocalDateTime.now());
        itemRepository.save(item);
        log(settlementId, "DISPUTE", operator, "MEMBER",
                "对损伤 #" + item.getItemSeq() + "（" + item.getPart() + "）提出异议，金额 "
                        + item.getFinalFee() + " 元冻结；理由：" + reason);
        return detail(settlementId);
    }

    /**
     * 异议处理：维持 / 减免 / 撤销。只能动金额与异议列，不能改原检查结果。
     * 已结清的清算单：减免 / 撤销的差额从待缴费用核销，无待缴或剩余部分退回会员余额。
     */
    @Transactional
    public DamageDtos.SettlementView handleDispute(Long settlementId, Long itemId, DamageDtos.DisputeHandleReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        DamageSettlementItem item = requireItem(settlementId, itemId);
        if (!DamageSettlementItem.DISPUTE_DISPUTING.equals(item.getDisputeStatus())) {
            throw new BizException("该损伤项不在异议处理中");
        }
        String manager = requireName(req.operator(), "值班经理");
        String note = requireText(req.note(), 500, "处理说明");
        String decision = req.decision();
        BigDecimal before = item.getFinalFee();
        LocalDateTime now = LocalDateTime.now();

        switch (decision) {
            case "MAINTAIN" -> {
                item.setDisputeStatus(DamageSettlementItem.DISPUTE_MAINTAIN);
                // 维持：金额不变（finalFee 保持）
            }
            case "REDUCE" -> {
                BigDecimal after = req.finalFee() == null ? null : req.finalFee().setScale(2, RoundingMode.HALF_UP);
                if (after == null || after.compareTo(BigDecimal.ZERO) < 0 || after.compareTo(item.getSnapFee()) >= 0) {
                    throw new BizException("减免后金额需在 0（含）与原检查金额 " + item.getSnapFee() + " 元之间");
                }
                item.setFinalFee(after);
                item.setDisputeStatus(DamageSettlementItem.DISPUTE_REDUCE);
            }
            case "REVOKE" -> {
                item.setFinalFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                item.setDisputeStatus(DamageSettlementItem.DISPUTE_REVOKE);
            }
            default -> throw new BizException("异议处理结论只能是 维持 / 减免 / 撤销");
        }
        item.setDisputeHandledBy(manager);
        item.setDisputeHandledAt(now);
        item.setDisputeNote(note);
        itemRepository.save(item);

        BigDecimal delta = before.subtract(item.getFinalFee()).setScale(2, RoundingMode.HALF_UP);
        String action = switch (decision) {
            case "MAINTAIN" -> "DISPUTE_MAINTAIN";
            case "REDUCE" -> "DISPUTE_REDUCE";
            default -> "DISPUTE_REVOKE";
        };
        String decisionText = RangeDict.disputeStatusName(
                DamageSettlementItem.DISPUTE_REVOKE.equals(decision) ? DamageSettlementItem.DISPUTE_REVOKE
                        : DamageSettlementItem.DISPUTE_REDUCE.equals(decision) ? DamageSettlementItem.DISPUTE_REDUCE
                                : DamageSettlementItem.DISPUTE_MAINTAIN);

        if (delta.compareTo(BigDecimal.ZERO) > 0 && DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
            refundAfterSettle(settlement, item, delta, manager, "异议" + decisionText);
        }
        log(settlementId, action, manager, "MANAGER",
                "异议处理「" + decisionText + "」损伤 #" + item.getItemSeq() + "（" + item.getPart() + "）："
                        + before + " 元 → " + item.getFinalFee() + " 元；原检查 " + item.getSnapFee()
                        + " 元与检查结果不变；说明：" + note
                        + (delta.compareTo(BigDecimal.ZERO) > 0
                                && DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())
                                        ? "；差额 " + delta + " 元已核销 / 退回"
                                        : ""));
        return detail(settlementId);
    }

    // ================= 基线核对 / 重新核对 =================

    /**
     * 重新计算基线漂移：器材状态（须仍为归还处理中）、租借人（id/姓名/租出时间）、会员余额。
     * 已结清清算单不核对。
     */
    private List<String> baselineDrifts(DamageSettlement settlement, Equipment equipment, Member member) {
        List<String> drifts = new ArrayList<>();
        if (!"SETTLING".equals(equipment.getStatus())) {
            drifts.add("器材状态已由「归还处理中」变为「" + RangeDict.equipStatusName(equipment.getStatus()) + "」");
        }
        if (equipment.getRenterId() == null) {
            drifts.add("器材租借人已被清除（原租借人：" + settlement.getBaselineRenterName() + "）");
        } else if (!equipment.getRenterId().equals(settlement.getBaselineRenterId())) {
            drifts.add("器材租借人已由 " + settlement.getBaselineRenterName() + " 变更为 "
                    + equipment.getRenterName());
        } else if (!eq(equipment.getRenterName(), settlement.getBaselineRenterName())) {
            drifts.add("租借人姓名与检查开始时不一致");
        }
        if (!eqTime(equipment.getRentedAt(), settlement.getBaselineRentedAt())) {
            drifts.add("租出时间与检查开始时基线不一致");
        }
        if (member == null) {
            drifts.add("原租借会员档案不存在");
        } else {
            if (member.getBalance().compareTo(settlement.getBaselineBalance()) != 0) {
                drifts.add("会员余额已由 " + settlement.getBaselineBalance() + " 元变为 "
                        + member.getBalance() + " 元");
            }
            if (Member.STATUS_MERGED.equals(member.getStatus())) {
                drifts.add("原租借会员档案已合并封存");
            }
        }
        return drifts;
    }

    /** 待重新核对的清算单确认当前现场后重新绑定基线（规则快照不动），回到待结清。 */
    @Transactional
    public DamageDtos.SettlementView rebind(Long settlementId, DamageDtos.OperatorReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        if (!DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
            throw new BizException("清算单当前为「" + RangeDict.settleStatusName(settlement.getStatus())
                    + "」，无需重新核对");
        }
        String operator = requireName(req.operator(), "值班经理");
        Equipment equipment = equipmentRepository.findByIdForUpdate(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("器材已不存在，无法重新核对"));
        Member member = memberRepository.findByIdForUpdate(settlement.getMemberId())
                .orElseThrow(() -> new BizException("原租借会员不存在，无法重新核对"));

        List<String> remaining = new ArrayList<>();
        // 器材必须回到归还处理中且仍挂原租借人，才能沿用本单；否则需要现场另行处理
        if (!"SETTLING".equals(equipment.getStatus())) {
            remaining.add("器材当前为「" + RangeDict.equipStatusName(equipment.getStatus())
                    + "」，需先恢复为归还处理中");
        }
        if (equipment.getRenterId() == null || !equipment.getRenterId().equals(settlement.getMemberId())) {
            remaining.add("器材租借人与本单责任会员不一致，不能沿用本清算单");
        }
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            remaining.add("原租借会员档案已合并封存，不能沿用本清算单");
        }
        if (!remaining.isEmpty()) {
            throw new BizException("重新核对未通过：" + String.join("；", remaining));
        }

        BigDecimal oldBalance = settlement.getBaselineBalance();
        settlement.setBaselineEquipStatus(equipment.getStatus());
        settlement.setBaselineRenterId(equipment.getRenterId());
        settlement.setBaselineRenterName(equipment.getRenterName());
        settlement.setBaselineRentedAt(equipment.getRentedAt());
        settlement.setBaselineBalance(member.getBalance().setScale(2, RoundingMode.HALF_UP));
        settlement.setStatus(DamageSettlement.STATUS_PENDING_SETTLE);
        settlementRepository.save(settlement);
        log(settlementId, "REBIND", operator, "MANAGER",
                "重新核对完成，基线已更新（余额基线 " + oldBalance + " → " + settlement.getBaselineBalance()
                        + " 元），恢复待结清；已登记的规则快照与检查结果不变");
        return detail(settlementId);
    }

    // ================= 结清前预览 =================

    @Transactional(readOnly = true)
    public DamageDtos.SettlePreview preview(Long settlementId) {
        DamageSettlement settlement = requireSettlement(settlementId);
        Equipment equipment = equipmentRepository.findById(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("器材不存在：id=" + settlement.getEquipmentId()));
        Member member = memberRepository.findById(settlement.getMemberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + settlement.getMemberId()));
        List<DamageSettlementItem> items = itemRepository.findBySettlementIdOrderByItemSeqAsc(settlementId);

        List<String> drifts = DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())
                ? List.of()
                : baselineDrifts(settlement, equipment, member);
        boolean stale = !drifts.isEmpty();
        BigDecimal total = sumTotal(items);
        BigDecimal balance = member.getBalance().setScale(2, RoundingMode.HALF_UP);
        BigDecimal deducted = balance.min(total).setScale(2, RoundingMode.HALF_UP);
        BigDecimal debt = total.subtract(deducted).setScale(2, RoundingMode.HALF_UP);
        String disposal = disposalOf(items);
        boolean blockedAfter = debt.compareTo(BigDecimal.ZERO) > 0
                || debtRepository.existsUnpaidByMember(member.getId());

        List<DamageDtos.FeeLine> lines = items.stream().map(item -> new DamageDtos.FeeLine(
                item.getItemSeq(), item.getPart(), RangeDict.damageSeverityName(item.getSeverity()),
                item.getResponsibleName(), item.getSnapFee(), item.getFinalFee(),
                item.isSnapRepairFlag(), item.isSnapScrapFlag(), adjustmentText(item))).toList();

        return new DamageDtos.SettlePreview(
                settlement.getId(), settlement.getSettleNo(), settlement.getEquipCode(),
                RangeDict.equipTypeName(settlement.getEquipType()), settlement.getMemberName(),
                balance, settlement.getBaselineBalance(), total,
                balance.subtract(deducted).setScale(2, RoundingMode.HALF_UP), deducted, debt,
                disposal, RangeDict.damageDisposalName(disposal), blockedAfter, stale, drifts, lines);
    }

    // ================= 结清（单一事务） =================

    @Transactional(noRollbackFor = BaselineStaleException.class)
    public DamageDtos.SettlementView settle(Long settlementId, DamageDtos.SettleReq req) {
        DamageSettlement settlement = lockSettlement(settlementId);
        if (DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已结清，请勿重复操作");
        }
        if (DamageSettlement.STATUS_INSPECTING.equals(settlement.getStatus())) {
            throw new BizException("检查尚未结束，请先结束检查再结清");
        }
        if (DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
            throw new BizException("清算单处于待重新核对状态，不能沿用旧结果结清，请由值班经理完成重新核对后再结清");
        }
        String operator = requireName(req.operator(), "结清操作人");

        // 固定加锁顺序（会员 → 器材），与租借（先器材后会员的场景）及补缴（会员）避免死锁；
        // 先锁会员行串行化该会员的余额变动，保证基线核对看到的余额即扣款余额
        Member member = memberRepository.findByIdForUpdate(settlement.getMemberId())
                .orElseThrow(() -> new BizException("租借会员不存在：id=" + settlement.getMemberId()));
        Equipment equipment = equipmentRepository.findByIdForUpdate(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("器材不存在：id=" + settlement.getEquipmentId()));
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材处于安全锁定，必须先完成放行后才能结清");
        }

        List<DamageSettlementItem> items = itemRepository.findBySettlementIdOrderByItemSeqAsc(settlementId);
        if (items.stream().anyMatch(i -> DamageSettlementItem.DISPUTE_DISPUTING.equals(i.getDisputeStatus()))) {
            throw new BizException("存在异议处理中的损伤项，金额冻结期间不能结清");
        }

        // 基线核对：任一变化同事务转 RECHECK 并提交（不回滚），绝不沿用旧结果
        List<String> drifts = baselineDrifts(settlement, equipment, member);
        if (!drifts.isEmpty()) {
            markRecheck(settlement, drifts);
            throw new BaselineStaleException(
                    "结清前基线核对发现变化，清算单已转为待重新核对：" + String.join("；", drifts), drifts);
        }

        BigDecimal total = sumTotal(items);
        BigDecimal balanceBefore = member.getBalance().setScale(2, RoundingMode.HALF_UP);
        BigDecimal deducted = balanceBefore.min(total).setScale(2, RoundingMode.HALF_UP);
        BigDecimal debt = total.subtract(deducted).setScale(2, RoundingMode.HALF_UP);
        String disposal = disposalOf(items);

        // ---- 1. 余额扣减（最多扣到 0）：计入累计消费并按规则升级 ----
        member.setBalance(balanceBefore.subtract(deducted).setScale(2, RoundingMode.HALF_UP));
        if (deducted.compareTo(BigDecimal.ZERO) > 0) {
            member.setTotalSpend(member.getTotalSpend().add(deducted).setScale(2, RoundingMode.HALF_UP));
            String upgraded = RangeDict.levelByTotalSpend(member.getTotalSpend());
            if (RangeDict.memberLevelRank(upgraded) > RangeDict.memberLevelRank(member.getLevel())) {
                member.setLevel(upgraded);
            }
        }
        memberRepository.save(member);

        // ---- 2. 余额不足 → 待缴费用（会员租借资格由债务存在性派生暂停） ----
        if (debt.compareTo(BigDecimal.ZERO) > 0) {
            MemberDebt memberDebt = new MemberDebt();
            memberDebt.setDebtNo(nextDebtNo());
            memberDebt.setSettlementId(settlementId);
            memberDebt.setMemberId(member.getId());
            memberDebt.setOriginalAmount(debt);
            memberDebt.setPaidAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            memberDebt.setAmount(debt);
            memberDebt.setStatus(MemberDebt.STATUS_UNPAID);
            memberDebt.setCreatedAt(LocalDateTime.now());
            memberDebt.setSettleNo(settlement.getSettleNo());
            debtRepository.save(memberDebt);
        }

        // ---- 3. 器材去向：无损回库 / 可修复送修 / 报废永久封存，并清除租借人 ----
        equipment.setStatus(switch (disposal) {
            case DamageSettlement.DISPOSAL_SCRAP -> "SCRAPPED";
            case DamageSettlement.DISPOSAL_REPAIR -> "REPAIR";
            default -> "INSTOCK";
        });
        equipment.setRenterId(null);
        equipment.setRenterName(null);
        equipment.setRentedAt(null);
        equipmentRepository.save(equipment);

        // ---- 4. 清算单结果固化 ----
        LocalDateTime now = LocalDateTime.now();
        settlement.setStatus(DamageSettlement.STATUS_SETTLED);
        settlement.setFinalDisposal(disposal);
        settlement.setTotalFee(total);
        settlement.setBalanceDeducted(deducted);
        settlement.setDebtAmount(debt);
        settlement.setSettledBy(operator);
        settlement.setSettledAt(now);
        settlement.setSettleNote(trim(req.note()));
        settlementRepository.save(settlement);

        log(settlementId, "SETTLE", operator, "KEEPER",
                "结清：应收 " + total + " 元，余额扣减 " + deducted + " 元（余额 " + balanceBefore + " → "
                        + member.getBalance() + "），待缴 " + debt + " 元；器材去向："
                        + RangeDict.damageDisposalName(disposal)
                        + (debt.compareTo(BigDecimal.ZERO) > 0 ? "；已暂停该会员租借器材资格" : ""));
        return detail(settlementId);
    }

    // ================= 补缴欠款解限 =================

    @Transactional
    public DamageDtos.DebtView repay(Long debtId, DamageDtos.RepayReq req) {
        MemberDebt debt = debtRepository.findByIdForUpdate(debtId)
                .orElseThrow(() -> new BizException("待缴费用不存在：id=" + debtId));
        if (!MemberDebt.STATUS_UNPAID.equals(debt.getStatus())) {
            throw new BizException("该笔待缴费用状态为「" + RangeDict.debtStatusName(debt.getStatus())
                    + "」，无需补缴");
        }
        String operator = requireName(req.operator(), "收款人");
        BigDecimal amount = req.amount() == null
                ? debt.getAmount()
                : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("补缴金额必须大于 0");
        }
        if (amount.compareTo(debt.getAmount()) > 0) {
            throw new BizException("补缴金额 " + amount + " 元超过剩余待缴 " + debt.getAmount() + " 元");
        }

        Member member = memberRepository.findByIdForUpdate(debt.getMemberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + debt.getMemberId()));
        memberServiceRejectMerged(member);
        if (member.getBalance().compareTo(amount) < 0) {
            throw new BizException("会员余额 " + member.getBalance() + " 元，不足支付本次补缴 " + amount + " 元，"
                    + "请先充值或调整补缴金额");
        }

        BigDecimal balanceBefore = member.getBalance();
        member.setBalance(balanceBefore.subtract(amount).setScale(2, RoundingMode.HALF_UP));
        member.setTotalSpend(member.getTotalSpend().add(amount).setScale(2, RoundingMode.HALF_UP));
        memberRepository.save(member);

        debt.setPaidAmount(debt.getPaidAmount().add(amount).setScale(2, RoundingMode.HALF_UP));
        debt.setAmount(debt.getAmount().subtract(amount).setScale(2, RoundingMode.HALF_UP));
        boolean fullyPaid = debt.getAmount().compareTo(BigDecimal.ZERO) == 0;
        if (fullyPaid) {
            debt.setStatus(MemberDebt.STATUS_PAID);
            debt.setPaidAt(LocalDateTime.now());
        }
        debtRepository.save(debt);

        MemberDebtPayment payment = new MemberDebtPayment();
        payment.setDebtId(debt.getId());
        payment.setMemberId(member.getId());
        payment.setAmount(amount);
        payment.setKind(MemberDebtPayment.KIND_REPAY);
        payment.setOperator(operator);
        payment.setDetail("补缴清算单 " + debt.getSettleNo() + " 待缴费用（余额 " + balanceBefore + " → "
                + member.getBalance() + "）" + (fullyPaid ? "，已缴清" : "，剩余待缴 " + debt.getAmount() + " 元"));
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // 全部待缴清零才恢复租借资格
        if (fullyPaid && !debtRepository.existsUnpaidByMember(member.getId())) {
            log(debt.getSettlementId(), "REPAY", operator, "MANAGER",
                    "会员 " + member.getName() + " 已补足全部待缴费用，租借资格恢复；本次补缴 " + amount + " 元");
        }
        return debtView(debt.getId());
    }

    // ================= 查询 =================

    @Transactional(readOnly = true)
    public List<DamageDtos.SettlementView> listSettlements(String status, Long memberId, Long equipmentId) {
        List<DamageSettlement> source;
        if (status != null && !status.isBlank()) {
            source = settlementRepository.findByStatusOrderByIdDesc(status);
        } else if (memberId != null) {
            source = settlementRepository.findByMemberIdOrderByIdDesc(memberId);
        } else if (equipmentId != null) {
            source = settlementRepository.findByEquipmentIdOrderByIdDesc(equipmentId);
        } else {
            source = settlementRepository.findAllByOrderByIdDesc();
        }
        return source.stream().map(s -> toView(s, false)).toList();
    }

    @Transactional(readOnly = true)
    public DamageDtos.SettlementView detail(Long id) {
        return toView(requireSettlement(id), true);
    }

    @Transactional(readOnly = true)
    public List<DamageDtos.DebtView> listDebts(String status, Long memberId) {
        List<MemberDebt> source;
        if (status != null && !status.isBlank()) {
            source = debtRepository.findByStatusOrderByIdDesc(status);
        } else if (memberId != null) {
            source = debtRepository.findByMemberIdOrderByIdDesc(memberId);
        } else {
            source = debtRepository.findAllByOrderByIdDesc();
        }
        return source.stream().map(d -> toDebtView(d, true)).toList();
    }

    @Transactional(readOnly = true)
    public DamageDtos.DebtView debtView(Long id) {
        MemberDebt debt = debtRepository.findById(id)
                .orElseThrow(() -> new BizException("待缴费用不存在：id=" + id));
        return toDebtView(debt, true);
    }

    /** 会员是否被暂停租借（存在待缴费用）—— 租借入口调用 */
    @Transactional(readOnly = true)
    public boolean isRentalBlocked(Long memberId) {
        return debtRepository.existsUnpaidByMember(memberId);
    }

    /** 会员是否有未结清清算单 —— 档案合并入口调用，防止基线失效 */
    @Transactional(readOnly = true)
    public boolean hasOpenSettlement(Long memberId) {
        return settlementRepository.existsOpenByMember(memberId);
    }

    // ================= 内部工具 =================

    /**
     * 已结清后的异议减免 / 撤销：差额先核销待缴费用（REVOKE 轨迹留痕），
     * 核销后仍有剩余则退回会员余额。不改写已固化的结清金额列。
     */
    private void refundAfterSettle(DamageSettlement settlement, DamageSettlementItem item,
            BigDecimal delta, String manager, String reason) {
        Optional<MemberDebt> debtOpt = debtRepository.findBySettlementId(settlement.getId());
        BigDecimal remaining = delta;
        if (debtOpt.isPresent()) {
            MemberDebt debt = debtOpt.get();
            if (MemberDebt.STATUS_UNPAID.equals(debt.getStatus()) && debt.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal writeOff = debt.getAmount().min(remaining);
                debt.setAmount(debt.getAmount().subtract(writeOff).setScale(2, RoundingMode.HALF_UP));
                if (debt.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                    debt.setStatus(MemberDebt.STATUS_REVOKED);
                    debt.setPaidAt(LocalDateTime.now());
                }
                debtRepository.save(debt);
                savePayment(debt, writeOff, manager,
                        reason + "核销待缴：损伤 #" + item.getItemSeq() + " " + item.getPart());
                remaining = remaining.subtract(writeOff);
            }
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            Member member = memberRepository.findByIdForUpdate(settlement.getMemberId())
                    .orElseThrow(() -> new BizException("会员不存在：id=" + settlement.getMemberId()));
            member.setBalance(member.getBalance().add(remaining).setScale(2, RoundingMode.HALF_UP));
            memberRepository.save(member);
        }
    }

    private void savePayment(MemberDebt debt, BigDecimal amount, String operator, String detail) {
        MemberDebtPayment payment = new MemberDebtPayment();
        payment.setDebtId(debt.getId());
        payment.setMemberId(debt.getMemberId());
        payment.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        payment.setKind(MemberDebtPayment.KIND_REVOKE);
        payment.setOperator(operator);
        payment.setDetail(detail);
        payment.setCreatedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    private static BigDecimal sumTotal(List<DamageSettlementItem> items) {
        return items.stream().map(DamageSettlementItem::getFinalFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 器材最终去向：任一严重项规则判报废 → 报废封存；否则任一规则判送修 → 维修；
     * 全部无损（无损伤或金额均被减免但规则为轻微）→ 回库。
     * 依据管理员检查时固化的规则快照，经理减免 / 异议只改钱，不改变器材安全去向。
     */
    private static String disposalOf(List<DamageSettlementItem> items) {
        boolean repair = false;
        for (DamageSettlementItem item : items) {
            if (item.isSnapScrapFlag()) {
                return DamageSettlement.DISPOSAL_SCRAP;
            }
            if (item.isSnapRepairFlag()) {
                repair = true;
            }
        }
        return repair ? DamageSettlement.DISPOSAL_REPAIR : DamageSettlement.DISPOSAL_NONE;
    }

    private static String adjustmentText(DamageSettlementItem item) {
        if (DamageSettlementItem.DISPUTE_REVOKE.equals(item.getDisputeStatus())) {
            return "异议撤销：" + item.getSnapFee() + " → 0.00";
        }
        if (DamageSettlementItem.DISPUTE_REDUCE.equals(item.getDisputeStatus())) {
            return "异议减免：" + item.getSnapFee() + " → " + item.getFinalFee();
        }
        if (item.isWaivedFlag()) {
            return "经理减免：" + item.getSnapFee() + " → " + item.getFinalFee();
        }
        if (DamageSettlementItem.DISPUTE_MAINTAIN.equals(item.getDisputeStatus())) {
            return "异议维持原价";
        }
        return item.getFinalFee().compareTo(item.getSnapFee()) == 0 ? "—" : "已调整";
    }

    /** 同事务把清算单置为 RECHECK 并留痕（配合 BaselineStaleException 的 noRollbackFor 提交）。 */
    private void markRecheck(DamageSettlement settlement, List<String> drifts) {
        if (!DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())
                && !DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
            settlement.setStatus(DamageSettlement.STATUS_RECHECK);
            settlementRepository.save(settlement);
            log(settlement.getId(), "RECHECK", "清算系统", "SYSTEM",
                    "结清前基线核对发现变化，转为待重新核对：" + String.join("；", drifts));
        }
    }

    private void memberServiceRejectMerged(Member member) {
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException("会员卡 [" + member.getCardNo() + "] 已合并封存，不能作为责任会员 / 补缴人");
        }
    }

    private DamageSettlement requireSettlement(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> new BizException("清算单不存在：id=" + id));
    }

    private DamageSettlement lockSettlement(Long id) {
        return settlementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("清算单不存在：id=" + id));
    }

    private static void requireInspecting(DamageSettlement settlement) {
        if (!DamageSettlement.STATUS_INSPECTING.equals(settlement.getStatus())) {
            throw new BizException("清算单当前为「" + RangeDict.settleStatusName(settlement.getStatus())
                    + "」，检查登记已结束");
        }
    }

    private DamageSettlementItem requireItem(Long settlementId, Long itemId) {
        DamageSettlementItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("损伤项不存在：id=" + itemId));
        if (!item.getSettlementId().equals(settlementId)) {
            throw new BizException("损伤项不属于该清算单");
        }
        return item;
    }

    private static String requireText(String value, int max, String field) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new BizException("请填写" + field);
        }
        if (text.length() > max) {
            throw new BizException(field + "过长（最多 " + max + " 字）");
        }
        return text;
    }

    private static String requireName(String name, String who) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (trimmed.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return trimmed;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean eqTime(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        return a == null ? b == null : a.equals(b);
    }

    private void log(Long settlementId, String action, String operator, String role, String detail) {
        DamageSettlementLog entry = new DamageSettlementLog();
        entry.setSettlementId(settlementId);
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String nextSettleNo() {
        String no = "CL" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (settlementRepository.existsBySettleNo(no)) {
            no = "CL" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq++;
        }
        return no;
    }

    private String nextDebtNo() {
        String prefix = "DT" + LocalDateTime.now().format(NO_STAMP_DEBT);
        String no = prefix;
        int seq = 1;
        while (debtRepository.existsByDebtNo(no)) {
            no = prefix + seq;
            seq++;
        }
        return no;
    }

    // ---------------- 视图组装 ----------------

    private DamageDtos.SettlementView toView(DamageSettlement settlement, boolean withDetails) {
        List<DamageSettlementItem> items = withDetails
                ? itemRepository.findBySettlementIdOrderByItemSeqAsc(settlement.getId())
                : List.of();
        List<DamageSettlementLog> logs = withDetails
                ? logRepository.findBySettlementIdOrderByIdAsc(settlement.getId())
                : List.of();
        BigDecimal currentBalance = null;
        List<String> drifts = List.of();
        boolean stale = false;
        if (withDetails) {
            Member member = memberRepository.findById(settlement.getMemberId()).orElse(null);
            currentBalance = member == null ? null : member.getBalance();
            if (!DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
                Equipment equipment = equipmentRepository.findById(settlement.getEquipmentId()).orElse(null);
                if (equipment != null && member != null) {
                    drifts = baselineDrifts(settlement, equipment, member);
                    stale = !drifts.isEmpty();
                }
            }
        }
        return new DamageDtos.SettlementView(
                settlement.getId(), settlement.getSettleNo(), settlement.getEquipmentId(),
                settlement.getEquipCode(), settlement.getEquipType(),
                RangeDict.equipTypeName(settlement.getEquipType()),
                settlement.getMemberId(), settlement.getMemberName(),
                settlement.getStatus(), RangeDict.settleStatusName(settlement.getStatus()),
                settlement.getBaselineEquipStatus(),
                RangeDict.equipStatusName(settlement.getBaselineEquipStatus()),
                settlement.getBaselineRenterId(), settlement.getBaselineRenterName(),
                settlement.getBaselineRentedAt(), settlement.getBaselineBalance(),
                settlement.getFinalDisposal(),
                settlement.getFinalDisposal() == null ? null
                        : RangeDict.damageDisposalName(settlement.getFinalDisposal()),
                settlement.getTotalFee(), settlement.getBalanceDeducted(), settlement.getDebtAmount(),
                settlement.getInspector(), settlement.getCreatedAt(),
                settlement.getSettledBy(), settlement.getSettledAt(), settlement.getSettleNote(),
                items.stream().map(DamageSettlementService::toItemView).toList(),
                logs.stream().map(DamageSettlementService::toLogView).toList(),
                currentBalance, drifts, stale);
    }

    private static DamageDtos.DamageItemView toItemView(DamageSettlementItem item) {
        return new DamageDtos.DamageItemView(
                item.getId(), item.getItemSeq(), item.getPart(), item.getSeverity(),
                RangeDict.damageSeverityName(item.getSeverity()), item.getDescription(),
                item.getResponsibleId(), item.getResponsibleName(),
                item.getRuleId(), item.getSnapRuleCode(), item.getSnapEffectiveDate(),
                item.getSnapFee(), item.isSnapRepairFlag(), item.isSnapScrapFlag(),
                item.getFinalFee(), item.isWaivedFlag(), item.getWaivedBy(), item.getWaivedAt(),
                item.getWaivedReason(), item.getDisputeStatus(),
                item.getDisputeStatus() == null ? null : RangeDict.disputeStatusName(item.getDisputeStatus()),
                item.getDisputeReason(), item.getDisputedAt(), item.getDisputeHandledBy(),
                item.getDisputeHandledAt(), item.getDisputeNote(), item.getCreatedAt());
    }

    private static DamageDtos.LogView toLogView(DamageSettlementLog l) {
        return new DamageDtos.LogView(l.getAction(), RangeDict.settleActionName(l.getAction()),
                l.getOperator(), l.getRole(), RangeDict.settleRoleName(l.getRole()),
                l.getDetail(), l.getCreatedAt());
    }

    private DamageDtos.DebtView toDebtView(MemberDebt debt, boolean withPayments) {
        Member member = memberRepository.findById(debt.getMemberId()).orElse(null);
        List<DamageDtos.PaymentView> payments = withPayments
                ? paymentRepository.findByDebtIdOrderByIdAsc(debt.getId()).stream()
                        .map(p -> new DamageDtos.PaymentView(p.getId(), p.getKind(),
                                "REPAY".equals(p.getKind()) ? "补缴" : "异议撤销",
                                p.getAmount(), p.getOperator(), p.getDetail(), p.getCreatedAt()))
                        .toList()
                : List.of();
        boolean blocked = MemberDebt.STATUS_UNPAID.equals(debt.getStatus())
                && debtRepository.existsUnpaidByMember(debt.getMemberId());
        return new DamageDtos.DebtView(
                debt.getId(), debt.getDebtNo(), debt.getSettlementId(), debt.getSettleNo(),
                debt.getMemberId(), member == null ? "id=" + debt.getMemberId() : member.getName(),
                debt.getOriginalAmount(), debt.getPaidAmount(), debt.getAmount(),
                debt.getStatus(), RangeDict.debtStatusName(debt.getStatus()),
                debt.getCreatedAt(), debt.getPaidAt(), payments, blocked);
    }

    private static DamageDtos.RuleView toRuleView(DamagePricingRule rule) {
        return new DamageDtos.RuleView(
                rule.getId(), rule.getEquipType(), RangeDict.equipTypeName(rule.getEquipType()),
                rule.getSeverity(), RangeDict.damageSeverityName(rule.getSeverity()),
                rule.getEffectiveDate(), rule.getFee(), rule.isRepairFlag(), rule.isScrapFlag(),
                rule.getStatus(), "ACTIVE".equals(rule.getStatus()) ? "现行" : "已停用",
                rule.getNote(), rule.getCreatedBy(), rule.getCreatedAt());
    }
}
