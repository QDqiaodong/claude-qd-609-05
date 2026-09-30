package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.DamageDebt;
import com.archery.range.domain.DamageDispute;
import com.archery.range.domain.DamageItem;
import com.archery.range.domain.DamageLog;
import com.archery.range.domain.DamagePayment;
import com.archery.range.domain.DamageRule;
import com.archery.range.domain.DamageSettlement;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.DamageDtos;
import com.archery.range.repository.DamageDebtRepository;
import com.archery.range.repository.DamageDisputeRepository;
import com.archery.range.repository.DamageItemRepository;
import com.archery.range.repository.DamageLogRepository;
import com.archery.range.repository.DamagePaymentRepository;
import com.archery.range.repository.DamageRuleRepository;
import com.archery.range.repository.DamageSettlementRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.MemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 器材损伤清算。
 *
 * 全流程与保证：
 * <ol>
 *   <li>发起检查（{@link #startCheck}）：只能从「租出」的器材发起；处于安全联锁（LOCKED）的
 *       器材必须先由停射事件放行，不能绕过。检查开始即锁定器材行为归还处理中（SETTLING），
 *       清算结束前不能再次租借、切换维修状态或清除原租借人；同时固化四条基线——
 *       租借人、器材状态、计价规则指纹、会员余额。</li>
 *   <li>提交检查（{@link #submitCheck}）：器材管理员逐项登记部位 / 严重程度 / 现场说明 /
 *       责任会员；每一项按基线日期的现行计价规则固化规则快照（费用、是否送修、是否报废），
 *       此后调价不改变本单历史金额。可登记「零项」表示无损归还。</li>
 *   <li>值班经理减免（{@link #waive}）：只能改单个损伤项的应收金额，
 *       原检查结果与减免前金额永久保留。</li>
 *   <li>结清（{@link #settle}）：先展示费用明细、会员余额变化与器材最终状态
 *       （{@link #preview}）；结清时重算基线，租借人 / 器材状态 / 计价规则 / 会员余额
 *       任一变化都转为「待重新核对」，不沿用旧结果。结清是单一事务：
 *       余额充足一次扣清；不足则只扣到零，剩余形成待缴费用并暂停该会员租借；
 *       无损 → 在库，可修复（任一送修）→ 维修，报废 → 永久封存。任一步异常整体回滚。</li>
 *   <li>补款解限（{@link #payDebt}）：会员补足待缴费用逐笔留痕，还清自动恢复租借资格。</li>
 *   <li>异议（{@link #openDispute} / {@link #decideDispute}）：提出即冻结金额，
 *       值班经理只能维持 / 减免 / 撤销本次费用，不能改动原损伤检查。</li>
 * </ol>
 */
@Service
public class DamageSettlementService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final List<String> OPEN_STATUSES = List.of(
            DamageSettlement.STATUS_CHECKING,
            DamageSettlement.STATUS_READY,
            DamageSettlement.STATUS_RECHECK);
    private static final List<String> OPEN_DEBT_STATUSES = List.of(
            DamageDebt.STATUS_UNPAID,
            DamageDebt.STATUS_PARTIAL,
            DamageDebt.STATUS_FROZEN);

    private final DamageSettlementRepository settlementRepository;
    private final DamageItemRepository itemRepository;
    private final DamageDebtRepository debtRepository;
    private final DamagePaymentRepository paymentRepository;
    private final DamageDisputeRepository disputeRepository;
    private final DamageLogRepository logRepository;
    private final EquipmentRepository equipmentRepository;
    private final MemberRepository memberRepository;
    private final DamageRuleService ruleService;
    private final DamageRuleRepository ruleRepository;
    private final ObjectMapper objectMapper;

    public DamageSettlementService(DamageSettlementRepository settlementRepository,
            DamageItemRepository itemRepository,
            DamageRuleRepository ruleRepository,
            DamageDebtRepository debtRepository,
            DamagePaymentRepository paymentRepository,
            DamageDisputeRepository disputeRepository,
            DamageLogRepository logRepository,
            EquipmentRepository equipmentRepository,
            MemberRepository memberRepository,
            DamageRuleService ruleService,
            ObjectMapper objectMapper) {
        this.settlementRepository = settlementRepository;
        this.itemRepository = itemRepository;
        this.ruleRepository = ruleRepository;
        this.debtRepository = debtRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.logRepository = logRepository;
        this.equipmentRepository = equipmentRepository;
        this.memberRepository = memberRepository;
        this.ruleService = ruleService;
        this.objectMapper = objectMapper;
    }

    // ================= 待检查租借 =================

    /**
     * 待检查租借列表：租出中的器材（可发起检查）+ 归还处理中的器材（继续清算）。
     * LOCKED 的器材不可发起，前端列表以 locked=true 提示先放行安全联锁。
     */
    @Transactional(readOnly = true)
    public List<DamageDtos.RentedEquipView> rentedList() {
        List<Equipment> rented = equipmentRepository.findByStatusOrderByEquipCodeAsc("RENTED");
        List<Equipment> settling = equipmentRepository.findByStatusOrderByEquipCodeAsc("SETTLING");
        List<Equipment> locked = equipmentRepository.findByStatusOrderByEquipCodeAsc("LOCKED");

        Map<Long, DamageSettlement> activeByEquip = new LinkedHashMap<>();
        for (DamageSettlement s : settlementRepository.findByStatusInOrderByIdDesc(OPEN_STATUSES)) {
            activeByEquip.putIfAbsent(s.getEquipmentId(), s);
        }

        List<DamageDtos.RentedEquipView> views = new ArrayList<>();
        appendRented(views, rented, activeByEquip, false);
        appendRented(views, settling, activeByEquip, false);
        appendRented(views, locked, activeByEquip, true);
        return views;
    }

    private List<DamageDtos.RentedEquipView> appendRented(List<DamageDtos.RentedEquipView> views, List<Equipment> equips,
            Map<Long, DamageSettlement> activeByEquip, boolean locked) {
        for (Equipment e : equips) {
            // LOCKED 列表里只展示租出前状态为 RENTED 的器材（安全事件快照可追溯），在库锁定器材与清算无关
            if (locked && e.getRenterId() == null) {
                continue;
            }
            DamageSettlement active = activeByEquip.get(e.getId());
            views.add(new DamageDtos.RentedEquipView(
                    e.getId(),
                    e.getEquipCode(),
                    e.getType(),
                    RangeDict.equipTypeName(e.getType()),
                    e.getBrand(),
                    e.getRenterId(),
                    e.getRenterName(),
                    e.getRentedAt(),
                    locked,
                    active == null ? null : active.getId(),
                    active == null ? null : active.getStatus()));
        }
        return views;
    }

    // ================= 发起检查 =================

    @Transactional
    public DamageDtos.SettlementView startCheck(DamageDtos.StartCheckReq req) {
        String operator = requireName(req.operator(), "器材管理员");
        if (req.equipmentId() == null) {
            throw new BizException("请选择要检查的器材");
        }
        Equipment equipment = equipmentRepository.findByIdForUpdate(req.equipmentId())
                .orElseThrow(() -> new BizException("器材不存在：id=" + req.equipmentId()));
        // 不能绕过现有安全状态：安全联锁中的器材必须先完成放行
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 处于安全锁定（停射事件未放行），必须先完成复射放行后才能发起损伤检查");
        }
        if (!"RENTED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 当前为「"
                    + RangeDict.equipStatusName(equipment.getStatus()) + "」，只能从正在租借的器材发起检查");
        }
        if (equipment.getRenterId() == null) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 缺少租借人记录，不能发起检查");
        }
        if (settlementRepository.countByEquipmentIdAndStatusIn(equipment.getId(), OPEN_STATUSES) > 0) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 已有未结束的损伤清算，请继续处理原清算单");
        }
        Member renter = memberRepository.findByIdForUpdate(equipment.getRenterId())
                .orElseThrow(() -> new BizException("租借会员不存在：id=" + equipment.getRenterId()));
        memberServiceRejectMerged(renter);

        LocalDate today = LocalDate.now();
        BigDecimal baselineBalance = money(renter.getBalance());
        String ruleDigest = ruleService.currentRuleDigest();

        DamageSettlement settlement = new DamageSettlement();
        settlement.setSettleNo(nextSettleNo());
        settlement.setEquipmentId(equipment.getId());
        settlement.setEquipCode(equipment.getEquipCode());
        settlement.setEquipType(equipment.getType());
        settlement.setRenterId(renter.getId());
        settlement.setRenterName(renter.getName());
        settlement.setStatus(DamageSettlement.STATUS_CHECKING);
        settlement.setBaselineDate(today);
        settlement.setBaselineEquipStatus(equipment.getStatus());
        settlement.setBaselineBalance(baselineBalance);
        settlement.setBaselineRuleDigest(ruleDigest);
        settlement.setBaselineSnapshotJson(writeJson(baselineSnapshot(equipment, renter, today, ruleDigest)));
        settlement.setTotalFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        settlement.setCreatedBy(operator);
        settlement.setCreatedAt(LocalDateTime.now());
        settlement = settlementRepository.save(settlement);

        // 器材进入归还处理中：清算结束前租借人字段一律保留
        equipment.setStatus("SETTLING");
        equipmentRepository.save(equipment);

        log(settlement.getId(), null, "CREATE", operator, "KEEPER",
                "从租借器材 " + equipment.getEquipCode() + " 发起损伤检查；租借人 "
                        + renter.getName() + "，基线余额 " + baselineBalance.toPlainString()
                        + " 元；器材进入归还处理中");
        return detail(settlement.getId());
    }

    // ================= 提交检查结果 =================

    @Transactional
    public DamageDtos.SettlementView submitCheck(Long id, DamageDtos.SubmitCheckReq req) {
        DamageSettlement settlement = lockSettlement(id);
        String operator = requireName(req.operator(), "器材管理员");
        if (!DamageSettlement.STATUS_CHECKING.equals(settlement.getStatus())) {
            throw new BizException("清算单当前为「" + RangeDict.settleStatusName(settlement.getStatus())
                    + "」，不能再提交检查结果");
        }
        Equipment equipment = lockEquipment(settlement);
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 处于安全锁定，请先完成复射放行后再提交检查");
        }
        Member renter = lockRenter(settlement);

        List<DamageItem> items = buildItems(settlement, equipment, renter,
                req.items() == null ? List.of() : req.items());
        itemRepository.saveAll(items);

        BigDecimal total = sumFinal(items);
        settlement.setTotalFee(total);
        settlement.setStatus(DamageSettlement.STATUS_READY);
        settlement.setRecheckReason(null);
        settlementRepository.save(settlement);

        log(id, null, "SUBMIT", operator, "KEEPER",
                items.isEmpty()
                        ? "检查完成：未登记损伤，按无损归还结清"
                        : "检查完成：登记损伤 " + items.size() + " 项，应收合计 "
                                + total.toPlainString() + " 元（已固化计价规则快照）");
        return detail(id);
    }

    // ================= 重新核对（基线变化后） =================

    @Transactional
    public DamageDtos.SettlementView reconfirm(Long id, DamageDtos.ReconfirmReq req) {
        DamageSettlement settlement = lockSettlement(id);
        String operator = requireName(req.operator(), "器材管理员");
        if (!DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
            throw new BizException("清算单当前为「" + RangeDict.settleStatusName(settlement.getStatus())
                    + "」，无需重新核对");
        }
        Equipment equipment = lockEquipment(settlement);
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 处于安全锁定，请先完成复射放行后再重新核对");
        }
        Member renter = lockRenter(settlement);

        // 重新核对 = 以当前数据重记检查结果：原损伤检查仍逐行保留在库，只是本单改挂新的检查项，
        // 已做过的值班经理减免随之失效（金额随重新登记重算，减免须重新操作，轨迹保留）。
        itemRepository.deleteBySettlementId(id);
        itemRepository.flush();

        List<DamageItem> items = buildItems(settlement, equipment, renter,
                req.items() == null ? List.of() : req.items());
        itemRepository.saveAll(items);

        LocalDate today = LocalDate.now();
        String newDigest = ruleService.currentRuleDigest();
        settlement.setBaselineDate(today);
        settlement.setBaselineEquipStatus(equipment.getStatus());
        settlement.setBaselineBalance(money(renter.getBalance()));
        settlement.setBaselineRuleDigest(newDigest);
        settlement.setBaselineSnapshotJson(writeJson(baselineSnapshot(equipment, renter, today, newDigest)));
        settlement.setTotalFee(sumFinal(items));
        settlement.setStatus(DamageSettlement.STATUS_READY);
        settlement.setRecheckReason(null);
        settlementRepository.save(settlement);

        log(id, null, "RECONFIRM", operator, "KEEPER",
                "已按当前数据重新核对检查：登记损伤 " + items.size() + " 项，应收合计 "
                        + settlement.getTotalFee().toPlainString() + " 元；基线（租借人 / 器材状态 / 计价规则 / 余额）已刷新");
        return detail(id);
    }

    // ================= 值班经理减免 =================

    @Transactional
    public DamageDtos.SettlementView waive(Long settlementId, Long itemId, DamageDtos.WaiveReq req) {
        String operator = requireName(req.operator(), "值班经理");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请填写减免理由");
        }
        BigDecimal target = req.finalFee() == null ? null : req.finalFee().setScale(2, RoundingMode.HALF_UP);
        if (target == null || target.signum() < 0) {
            throw new BizException("减免后金额必须是不小于 0 的数字");
        }
        DamageSettlement settlement = lockSettlement(settlementId);
        if (DamageSettlement.STATUS_CHECKING.equals(settlement.getStatus())) {
            throw new BizException("检查结果尚未提交，不能减免");
        }
        if (DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())
                || DamageSettlement.STATUS_CANCELLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已结束，不能再减免损伤项");
        }
        // 待重新核对时先由管理员重新核对，经理不能在过期结果上做减免
        if (DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
            throw new BizException("清算单待重新核对，请先由器材管理员按当前数据重新核对检查结果");
        }
        Equipment lockedEquip = equipmentRepository.findById(settlement.getEquipmentId()).orElse(null);
        if (lockedEquip != null && "LOCKED".equals(lockedEquip.getStatus())) {
            throw new BizException("器材 [" + lockedEquip.getEquipCode()
                    + "] 处于安全锁定，请先完成复射放行后再处理减免");
        }
        DamageItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("损伤项不存在：id=" + itemId));
        if (!item.getSettlementId().equals(settlementId)) {
            throw new BizException("损伤项不属于该清算单");
        }
        BigDecimal snapFee = money(item.getSnapFee());
        if (target.compareTo(snapFee) >= 0) {
            throw new BizException("减免后金额必须小于减免前金额 " + snapFee.toPlainString()
                    + " 元；无需减免请保持原金额");
        }
        item.setFinalFee(target);
        item.setWaived(true);
        item.setWaivedFee(snapFee.subtract(target).setScale(2, RoundingMode.HALF_UP));
        item.setWaiveReason(reason);
        item.setWaivedBy(operator);
        item.setWaivedAt(LocalDateTime.now());
        itemRepository.save(item);

        settlement.setTotalFee(sumTotal(settlementId));
        settlementRepository.save(settlement);

        log(settlementId, null, "WAIVE", operator, "MANAGER",
                "减免第 " + item.getItemNo() + " 项（" + item.getPart() + " · "
                        + RangeDict.damageSeverityName(item.getSeverity()) + "）："
                        + snapFee.toPlainString() + " 元 → " + target.toPlainString()
                        + " 元；理由：" + reason + "。原检查结果不变");
        return detail(settlementId);
    }

    // ================= 取消检查（回到检查前状态） =================

    @Transactional
    public DamageDtos.SettlementView cancel(Long id, DamageDtos.CancelReq req) {
        String operator = requireName(req.operator(), "器材管理员");
        DamageSettlement settlement = lockSettlement(id);
        if (DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已结清，不能取消");
        }
        if (DamageSettlement.STATUS_CANCELLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已取消，请勿重复操作");
        }
        Equipment equipment = lockEquipment(settlement);
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 处于安全锁定，请先完成复射放行后再取消检查");
        }
        if (!"SETTLING".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 当前为「" + RangeDict.equipStatusName(equipment.getStatus())
                    + "」，清算状态异常，取消已中止");
        }
        // 回到检查前状态：器材恢复租出、原租借人保留不动
        equipment.setStatus("RENTED");
        equipmentRepository.save(equipment);
        settlement.setStatus(DamageSettlement.STATUS_CANCELLED);
        settlement.setCancelledAt(LocalDateTime.now());
        settlementRepository.save(settlement);
        log(id, null, "CANCEL", operator, "KEEPER",
                "取消损伤检查，器材 " + equipment.getEquipCode() + " 恢复租出，租借人仍为 "
                        + settlement.getRenterName());
        return detail(id);
    }

    // ================= 结清前预览 =================

    @Transactional(readOnly = true)
    public DamageDtos.FeePreview preview(Long id) {
        DamageSettlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new BizException("清算单不存在：id=" + id));
        List<DamageItem> items = itemRepository.findBySettlementIdOrderByItemNoAsc(id);
        Member renter = memberRepository.findById(settlement.getRenterId())
                .orElseThrow(() -> new BizException("租借会员已不存在，不能结清"));
        Optional<Equipment> equipOpt = equipmentRepository.findById(settlement.getEquipmentId());

        BigDecimal total = sumFinal(items);
        BigDecimal balance = money(renter.getBalance());
        BigDecimal deducted = balance.min(total);
        BigDecimal debt = total.subtract(deducted).setScale(2, RoundingMode.HALF_UP);

        String drift = detectDrift(settlement, equipOpt.orElse(null), renter);
        boolean recheck = drift != null;
        String finalStatus = finalEquipStatus(items);

        return new DamageDtos.FeePreview(
                settlement.getStatus(),
                RangeDict.settleStatusName(settlement.getStatus()),
                recheck,
                drift,
                total,
                balance,
                deducted.setScale(2, RoundingMode.HALF_UP),
                debt,
                Boolean.TRUE.equals(renter.getRentSuspended()),
                finalStatus,
                RangeDict.equipStatusName(finalStatus),
                items.stream().map(this::toItemView).toList());
    }

    // ================= 结清（单一事务，任一步异常整体回滚） =================

    @Transactional
    public DamageDtos.SettlementView settle(Long id, DamageDtos.SettleReq req) {
        String operator = requireName(req.operator(), "结清操作人");
        DamageSettlement settlement = lockSettlement(id);
        Equipment equipment = lockEquipment(settlement);
        Member renter = memberRepository.findByIdForUpdate(settlement.getRenterId())
                .orElseThrow(() -> new BizException("租借会员已不存在，不能结清"));
        memberServiceRejectMerged(renter);

        // 安全联锁的器材必须先放行，清算不能覆盖安全状态
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode()
                    + "] 处于安全锁定，请先完成复射放行后再结清");
        }

        List<DamageItem> items = itemRepository.findBySettlementIdOrderByItemNoAsc(id);
        String drift = detectDrift(settlement, equipment, renter);
        if (drift != null) {
            // 结清前任一基线变化：转为待重新核对，绝不沿用旧结果
            if (!DamageSettlement.STATUS_RECHECK.equals(settlement.getStatus())) {
                settlement.setStatus(DamageSettlement.STATUS_RECHECK);
                settlement.setRecheckReason(drift);
                settlementRepository.save(settlement);
                log(id, null, "RECHECK_FLAG", "清算系统", "SYSTEM",
                        "结清校验发现基线变化，已转为待重新核对：" + drift);
            }
            throw new BizException(drift + "；清算单已转为待重新核对，请由器材管理员重新核对后再结清");
        }

        if (DamageSettlement.STATUS_SETTLED.equals(settlement.getStatus())) {
            throw new BizException("清算单已结清，请勿重复操作");
        }
        if (DamageSettlement.STATUS_CHECKING.equals(settlement.getStatus())) {
            throw new BizException("检查结果尚未提交，请先提交检查（无损也要提交零项检查）");
        }

        // ---------- 1. 费用 ----------
        BigDecimal total = sumFinal(items);
        BigDecimal balanceBefore = money(renter.getBalance());
        BigDecimal deducted = balanceBefore.min(total);
        BigDecimal debtAmount = total.subtract(deducted).setScale(2, RoundingMode.HALF_UP);

        // 余额扣减（损伤赔偿不累计消费、不调整会员等级，只减余额）
        renter.setBalance(balanceBefore.subtract(deducted).setScale(2, RoundingMode.HALF_UP));

        DamageDebt debt = null;
        if (debtAmount.signum() > 0) {
            // 余额不足：剩余形成待缴费用，并暂停该会员继续租借器材
            debt = new DamageDebt();
            debt.setDebtNo(nextDebtNo());
            debt.setSettlementId(id);
            debt.setMemberId(renter.getId());
            debt.setAmount(debtAmount);
            debt.setPaidAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            debt.setStatus(DamageDebt.STATUS_UNPAID);
            debt.setCreatedAt(LocalDateTime.now());
            debt = debtRepository.save(debt);

            renter.setRentSuspended(true);
            renter.setRentSuspendedAt(LocalDateTime.now());
            renter.setRentSuspendReason("损伤清算 " + settlement.getSettleNo()
                    + " 形成待缴费用 " + debtAmount.toPlainString() + " 元");
        }
        memberRepository.save(renter);

        // ---------- 2. 器材去向：报废优先，其次送修，无损回在库；原租借人清除 ----------
        String finalStatus = finalEquipStatus(items);
        equipment.setStatus(finalStatus);
        equipment.setRenterId(null);
        equipment.setRenterName(null);
        equipment.setRentedAt(null);
        equipmentRepository.save(equipment);

        // ---------- 3. 清算单固化结果 ----------
        settlement.setStatus(DamageSettlement.STATUS_SETTLED);
        settlement.setTotalFee(total);
        settlement.setBalanceDeducted(deducted.setScale(2, RoundingMode.HALF_UP));
        settlement.setDebtAmount(debtAmount);
        settlement.setFinalEquipStatus(finalStatus);
        settlement.setSettledAt(LocalDateTime.now());
        settlement.setSettledBy(operator);
        settlementRepository.save(settlement);

        log(id, debt == null ? null : debt.getId(), "SETTLE", operator, "MANAGER",
                "结清完成：应收 " + total.toPlainString() + " 元，余额扣减 "
                        + deducted.toPlainString() + " 元（余额 "
                        + balanceBefore.toPlainString() + " → " + money(renter.getBalance()).toPlainString()
                        + "）"
                        + (debtAmount.signum() > 0
                                ? "，待缴 " + debtAmount.toPlainString() + " 元，已暂停 "
                                        + renter.getName() + " 租借器材"
                                : "，无待缴费用")
                        + "；器材去向：" + RangeDict.equipStatusName(finalStatus));

        return detail(id);
    }

    // ================= 补款解限 =================

    @Transactional
    public DamageDtos.SettlementView payDebt(Long debtId, DamageDtos.PayReq req) {
        String operator = requireName(req.operator(), "补款经手人");
        BigDecimal amount = req.amount() == null ? null : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount == null || amount.signum() <= 0) {
            throw new BizException("补款金额必须大于 0");
        }
        DamageDebt debt = debtRepository.findByIdForUpdate(debtId)
                .orElseThrow(() -> new BizException("待缴费用不存在：id=" + debtId));
        DamageSettlement settlement = lockSettlement(debt.getSettlementId());
        Member member = memberRepository.findByIdForUpdate(debt.getMemberId())
                .orElseThrow(() -> new BizException("欠费会员不存在：id=" + debt.getMemberId()));

        if (DamageDebt.STATUS_FROZEN.equals(debt.getStatus())) {
            throw new BizException("该笔待缴费用异议处理中，金额已冻结，暂不能补款");
        }
        if (DamageDebt.STATUS_PAID.equals(debt.getStatus())) {
            throw new BizException("该笔待缴费用已缴清，请勿重复补款");
        }
        if (DamageDebt.STATUS_VOID.equals(debt.getStatus())) {
            throw new BizException("该笔费用已撤销，无需缴纳");
        }
        BigDecimal remaining = money(debt.getAmount()).subtract(money(debt.getPaidAmount()))
                .setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(remaining) > 0) {
            throw new BizException("本次补款 " + amount.toPlainString() + " 元超过剩余待缴 "
                    + remaining.toPlainString() + " 元");
        }

        // 会员先补足余额再核销待缴：补款款进会员余额后立即扣减，形成完整余额轨迹
        BigDecimal balanceBefore = money(member.getBalance());
        member.setBalance(balanceBefore.add(amount).setScale(2, RoundingMode.HALF_UP));
        BigDecimal afterRecharge = money(member.getBalance());
        member.setBalance(afterRecharge.subtract(amount).setScale(2, RoundingMode.HALF_UP));
        BigDecimal balanceAfter = money(member.getBalance());

        BigDecimal paid = money(debt.getPaidAmount()).add(amount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newRemaining = money(debt.getAmount()).subtract(paid).setScale(2, RoundingMode.HALF_UP);
        debt.setPaidAmount(paid);
        boolean lifted = false;
        LocalDateTime now = LocalDateTime.now();
        if (newRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            // 多缴不允许（前面已拦），到零即缴清
            newRemaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            debt.setStatus(DamageDebt.STATUS_PAID);
            debt.setPaidAt(now);
        } else {
            debt.setStatus(DamageDebt.STATUS_PARTIAL);
        }
        debtRepository.save(debt);

        // 最后一笔还清：名下再无未结待缴时执行欠款解限，恢复租借资格
        if (DamageDebt.STATUS_PAID.equals(debt.getStatus())
                && debtRepository.countByMemberIdAndStatusIn(member.getId(), OPEN_DEBT_STATUSES) == 0) {
            member.setRentSuspended(false);
            member.setRentSuspendedAt(null);
            member.setRentSuspendReason(null);
            lifted = true;
        }
        memberRepository.save(member);

        DamagePayment payment = new DamagePayment();
        payment.setDebtId(debt.getId());
        payment.setSettlementId(settlement.getId());
        payment.setMemberId(member.getId());
        payment.setAmount(amount);
        payment.setBalanceBefore(balanceBefore);
        payment.setBalanceAfter(balanceAfter);
        payment.setDebtRemaining(newRemaining);
        payment.setLiftedRentBan(lifted);
        payment.setOperator(operator);
        payment.setCreatedAt(now);
        paymentRepository.save(payment);

        log(settlement.getId(), debt.getId(), "PAY", operator, "MANAGER",
                "补款 " + amount.toPlainString() + " 元，剩余待缴 " + newRemaining.toPlainString()
                        + " 元" + (lifted ? "；已缴清，执行欠款解限，恢复 " + member.getName() + " 的租借资格"
                                : "（部分缴纳，租借限制保持）"));
        return detail(settlement.getId());
    }

    // ================= 异议 =================

    @Transactional
    public DamageDtos.SettlementView openDispute(Long debtId, DamageDtos.DisputeReq req) {
        String memberName = requireName(req.memberName(), "会员");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请填写异议理由");
        }
        DamageDebt debt = debtRepository.findByIdForUpdate(debtId)
                .orElseThrow(() -> new BizException("待缴费用不存在：id=" + debtId));
        DamageSettlement settlement = lockSettlement(debt.getSettlementId());
        Member member = memberRepository.findById(debt.getMemberId())
                .orElseThrow(() -> new BizException("欠费会员不存在：id=" + debt.getMemberId()));
        if (!member.getName().equals(memberName)) {
            throw new BizException("提出异议的会员姓名与待缴费用记录不一致（应为 "
                    + member.getName() + "）");
        }
        if (DamageDebt.STATUS_FROZEN.equals(debt.getStatus())) {
            throw new BizException("该笔费用已在异议处理中，请勿重复提出");
        }
        if (DamageDebt.STATUS_PAID.equals(debt.getStatus())) {
            throw new BizException("该笔费用已缴清，不能再提出异议");
        }
        if (DamageDebt.STATUS_VOID.equals(debt.getStatus())) {
            throw new BizException("该笔费用已撤销");
        }
        if (disputeRepository.countByDebtIdAndStatus(debtId, DamageDispute.STATUS_OPEN) > 0) {
            throw new BizException("该笔费用已有处理中的异议");
        }

        DamageDispute dispute = new DamageDispute();
        dispute.setDebtId(debt.getId());
        dispute.setSettlementId(settlement.getId());
        dispute.setMemberId(member.getId());
        dispute.setReason(reason);
        dispute.setStatus(DamageDispute.STATUS_OPEN);
        dispute.setCreatedAt(LocalDateTime.now());
        disputeRepository.save(dispute);

        // 异议处理期间金额冻结：待缴转 FROZEN（租借限制保持，冻结期间不能补款）
        debt.setStatus(DamageDebt.STATUS_FROZEN);
        debtRepository.save(debt);

        log(settlement.getId(), debt.getId(), "DISPUTE", memberName, "MEMBER",
                "会员提出异议，待缴费用 " + money(debt.getAmount()).subtract(money(debt.getPaidAmount()))
                        .toPlainString() + " 元冻结：" + reason);
        return detail(settlement.getId());
    }

    @Transactional
    public DamageDtos.SettlementView decideDispute(Long debtId, DamageDtos.DisputeDecideReq req) {
        String operator = requireName(req.operator(), "值班经理");
        String decision = req.decision() == null ? "" : req.decision().trim();
        String note = req.note() == null ? "" : req.note().trim();
        if (note.isEmpty()) {
            throw new BizException("请填写处理意见");
        }
        if (!List.of("UPHOLD", "REDUCE", "VOID").contains(decision)) {
            throw new BizException("异议处理结论只能是 维持 / 减免 / 撤销");
        }
        DamageDebt debt = debtRepository.findByIdForUpdate(debtId)
                .orElseThrow(() -> new BizException("待缴费用不存在：id=" + debtId));
        DamageSettlement settlement = lockSettlement(debt.getSettlementId());
        Member member = memberRepository.findByIdForUpdate(debt.getMemberId())
                .orElseThrow(() -> new BizException("欠费会员不存在：id=" + debt.getMemberId()));
        if (!DamageDebt.STATUS_FROZEN.equals(debt.getStatus())) {
            throw new BizException("该笔费用当前不在异议冻结状态，不能做异议处理");
        }
        DamageDispute dispute = disputeRepository.findByDebtIdOrderByIdAsc(debtId).stream()
                .filter(d -> DamageDispute.STATUS_OPEN.equals(d.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("未找到处理中的异议记录"));

        BigDecimal outstanding = money(debt.getAmount()).subtract(money(debt.getPaidAmount()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal reduce = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String actionDetail;
        LocalDateTime now = LocalDateTime.now();

        if ("UPHOLD".equals(decision)) {
            dispute.setStatus(DamageDispute.STATUS_UPHOLD);
            debt.setStatus(money(debt.getPaidAmount()).signum() > 0
                    ? DamageDebt.STATUS_PARTIAL : DamageDebt.STATUS_UNPAID);
            actionDetail = "维持原待缴费用 " + outstanding.toPlainString() + " 元，继续冻结解除、按原金额追缴";
        } else if ("REDUCE".equals(decision)) {
            reduce = req.reduceAmount() == null ? null : req.reduceAmount().setScale(2, RoundingMode.HALF_UP);
            if (reduce == null || reduce.signum() <= 0) {
                throw new BizException("减免处理必须填写大于 0 的核减金额；若要全部撤销请选择「撤销」");
            }
            if (reduce.compareTo(outstanding) >= 0) {
                throw new BizException("核减金额 " + reduce.toPlainString()
                        + " 元不小于剩余待缴，全部撤销请直接选择「撤销」");
            }
            // 只核减待缴本金，已缴部分不退；已固化的清算金额与原检查结果不变
            BigDecimal newOutstanding = outstanding.subtract(reduce).setScale(2, RoundingMode.HALF_UP);
            debt.setAmount(money(debt.getPaidAmount()).add(newOutstanding).setScale(2, RoundingMode.HALF_UP));
            debt.setStatus(money(debt.getPaidAmount()).signum() > 0
                    ? DamageDebt.STATUS_PARTIAL : DamageDebt.STATUS_UNPAID);
            dispute.setStatus(DamageDispute.STATUS_REDUCE);
            dispute.setReduceAmount(reduce);
            actionDetail = "核减待缴费用 " + reduce.toPlainString() + " 元，剩余待缴 "
                    + newOutstanding.toPlainString() + " 元；原损伤检查结果不变";
        } else {
            // VOID：撤销本次待缴费用。已缴部分退回会员余额；未缴部分不再追缴。
            BigDecimal refund = money(debt.getPaidAmount());
            if (refund.signum() > 0) {
                member.setBalance(money(member.getBalance()).add(refund).setScale(2, RoundingMode.HALF_UP));
            }
            debt.setAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            debt.setStatus(DamageDebt.STATUS_VOID);
            debt.setPaidAt(now);
            dispute.setStatus(DamageDispute.STATUS_VOID);
            actionDetail = "撤销本次待缴费用，未缴部分不再追缴"
                    + (refund.signum() > 0 ? "，已缴 " + refund.toPlainString() + " 元已退回会员余额" : "");
        }

        dispute.setDecisionNote(note);
        dispute.setDecidedBy(operator);
        dispute.setDecidedAt(now);
        disputeRepository.save(dispute);
        debtRepository.save(debt);

        // 撤销后若名下已无未结待缴，解除租借限制；维持 / 减免后仍有余额则保持暂停
        if (debtRepository.countByMemberIdAndStatusIn(member.getId(), OPEN_DEBT_STATUSES) == 0) {
            member.setRentSuspended(false);
            member.setRentSuspendedAt(null);
            member.setRentSuspendReason(null);
        }
        memberRepository.save(member);

        log(settlement.getId(), debt.getId(), "DISPUTE_DECIDE", operator, "MANAGER",
                "异议处理：" + actionDetail + "；意见：" + note);
        return detail(settlement.getId());
    }

    // ================= 查询 =================

    @Transactional(readOnly = true)
    public List<DamageDtos.SettlementView> list(String status) {
        List<DamageSettlement> settlements = (status == null || status.isBlank())
                ? settlementRepository.findAllByOrderByIdDesc()
                : settlementRepository.findByStatusOrderByIdDesc(status);
        return settlements.stream().map(this::toSettlementView).toList();
    }

    @Transactional(readOnly = true)
    public DamageDtos.SettlementView detail(Long id) {
        DamageSettlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new BizException("清算单不存在：id=" + id));
        return toSettlementView(settlement);
    }

    @Transactional(readOnly = true)
    public List<DamageDtos.DebtView> debtList(String status, Long memberId) {
        List<DamageDebt> debts;
        if (memberId != null) {
            debts = debtRepository.findByMemberIdOrderByIdDesc(memberId);
        } else if (status == null || status.isBlank()) {
            debts = debtRepository.findAllByOrderByIdDesc();
        } else if ("OPEN".equals(status)) {
            debts = debtRepository.findByStatusInOrderByIdDesc(OPEN_DEBT_STATUSES);
        } else {
            debts = debtRepository.findByStatusInOrderByIdDesc(List.of(status));
        }
        return debts.stream().map(this::toDebtView).toList();
    }

    // ================= 内部：建项 / 基线 / 状态判定 =================

    private List<DamageItem> buildItems(DamageSettlement settlement, Equipment equipment, Member renter,
            List<DamageDtos.DamageItemReq> reqs) {
        if (reqs.size() > 20) {
            throw new BizException("一次最多登记 20 项损伤");
        }
        // 规则按基线日期取（重新核对时 baselineDate 已刷新为当天）
        LocalDate baselineDate = settlement.getBaselineDate();
        List<DamageItem> items = new ArrayList<>();
        int no = 1;
        for (DamageDtos.DamageItemReq req : reqs) {
            String part = req.part() == null ? "" : req.part().trim();
            if (part.isEmpty()) {
                throw new BizException("第 " + no + " 项：请填写损伤部位");
            }
            if (part.length() > 32) {
                throw new BizException("第 " + no + " 项：损伤部位不超过 32 字");
            }
            if (!RangeDict.isValidDamageSeverity(req.severity())) {
                throw new BizException("第 " + no + " 项：严重程度只能是 轻微 / 较重 / 严重报废");
            }
            String description = req.description() == null ? "" : req.description().trim();
            if (description.isEmpty()) {
                throw new BizException("第 " + no + " 项：请填写现场说明");
            }
            if (description.length() > 500) {
                throw new BizException("第 " + no + " 项：现场说明不超过 500 字");
            }
            Member responsible = memberRepository.findById(req.responsibleId())
                    .orElseThrow(() -> new BizException("第 " + no + " 项：责任会员不存在"));
            memberServiceRejectMerged(responsible);

            DamageRule rule = ruleService.requireApplicable(
                    equipment.getType(), req.severity(), baselineDate);

            DamageItem item = new DamageItem();
            item.setSettlementId(settlement.getId());
            item.setItemNo(no);
            item.setPart(part);
            item.setSeverity(req.severity());
            item.setDescription(description);
            item.setResponsibleId(responsible.getId());
            item.setResponsibleName(responsible.getName());
            item.setRuleId(rule.getId());
            item.setSnapEquipType(rule.getEquipType());
            item.setSnapSeverity(rule.getSeverity());
            item.setSnapEffectiveFrom(rule.getEffectiveFrom());
            item.setSnapFee(money(rule.getFee()));
            item.setSnapSendRepair(Boolean.TRUE.equals(rule.getSendRepair()));
            item.setSnapScrap(Boolean.TRUE.equals(rule.getScrap()));
            item.setFinalFee(money(rule.getFee()));
            item.setWaived(false);
            item.setWaivedFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setCreatedAt(LocalDateTime.now());
            items.add(item);
            no++;
        }
        return items;
    }

    /**
     * 结清前基线漂移检测。四类基线任一不符即返回原因（非空）；null 表示基线一致可结清。
     * 规则基线按全部现行规则计算指纹：任何类型 / 严重程度的规则发布、停用都使本单需要重新核对。
     */
    private String detectDrift(DamageSettlement settlement, Equipment equipment, Member renter) {
        List<String> reasons = new ArrayList<>();
        if (equipment == null) {
            return "清算器材已不存在，无法结清";
        }
        if (equipment.getRenterId() == null || !equipment.getRenterId().equals(settlement.getRenterId())) {
            reasons.add("租借人已变化（检查开始时为 " + settlement.getRenterName()
                    + "，当前为 " + (equipment.getRenterName() == null ? "无" : equipment.getRenterName()) + "）");
        }
        if (!"SETTLING".equals(equipment.getStatus())) {
            reasons.add("器材状态已变化（检查开始时为「归还处理中」，当前为「"
                    + RangeDict.equipStatusName(equipment.getStatus()) + "」）");
        }
        if (renter == null) {
            reasons.add("责任租借会员已不存在");
        } else {
            if (Member.STATUS_MERGED.equals(renter.getStatus())) {
                reasons.add("租借会员卡已被合并封存");
            }
            if (money(renter.getBalance()).compareTo(money(settlement.getBaselineBalance())) != 0) {
                reasons.add("会员余额已变化（基线 " + money(settlement.getBaselineBalance()).toPlainString()
                        + " 元，当前 " + money(renter.getBalance()).toPlainString() + " 元）");
            }
        }
        String digestNow = ruleService.currentRuleDigest();
        if (!digestNow.equals(settlement.getBaselineRuleDigest())) {
            reasons.add("计价规则已调整（已发布新规则或停用规则），需按当前规则重新核对");
        }
        if (reasons.isEmpty()) {
            return null;
        }
        return String.join("；", reasons);
    }

    /** 器材最终去向：任一项报废 → SCRAPPED；否则任一项送修 → REPAIR；无损 → INSTOCK */
    private static String finalEquipStatus(List<DamageItem> items) {
        boolean repair = false;
        for (DamageItem item : items) {
            if (Boolean.TRUE.equals(item.getSnapScrap())) {
                return "SCRAPPED";
            }
            if (Boolean.TRUE.equals(item.getSnapSendRepair())) {
                repair = true;
            }
        }
        return repair ? "REPAIR" : "INSTOCK";
    }

    private Map<String, Object> baselineSnapshot(Equipment equipment, Member renter,
            LocalDate baselineDate, String ruleDigest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("equipmentId", equipment.getId());
        map.put("equipCode", equipment.getEquipCode());
        map.put("equipType", equipment.getType());
        map.put("equipTypeName", RangeDict.equipTypeName(equipment.getType()));
        map.put("equipStatus", equipment.getStatus());
        map.put("renterId", renter.getId());
        map.put("renterName", renter.getName());
        map.put("balance", money(renter.getBalance()));
        map.put("baselineDate", baselineDate.toString());
        map.put("ruleDigest", ruleDigest);
        // 固化当时全部生效中的规则，供历史清算回溯
        List<Map<String, Object>> rules = new ArrayList<>();
        for (DamageRule rule : ruleRepository.findEffectiveOn(baselineDate)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rule.getId());
            r.put("equipType", rule.getEquipType());
            r.put("severity", rule.getSeverity());
            r.put("effectiveFrom", rule.getEffectiveFrom().toString());
            r.put("fee", money(rule.getFee()));
            r.put("sendRepair", Boolean.TRUE.equals(rule.getSendRepair()));
            r.put("scrap", Boolean.TRUE.equals(rule.getScrap()));
            rules.add(r);
        }
        map.put("effectiveRules", rules);
        return map;
    }

    // ================= 内部：视图组装 =================

    private DamageDtos.SettlementView toSettlementView(DamageSettlement s) {
        List<DamageDtos.ItemView> items = itemRepository.findBySettlementIdOrderByItemNoAsc(s.getId())
                .stream().map(this::toItemView).toList();
        List<DamageDtos.LogView> logs = logRepository.findBySettlementIdOrderByIdAsc(s.getId())
                .stream().map(this::toLogView).toList();

        DamageDtos.DebtView debt = null;
        List<DamageDtos.PaymentView> payments = List.of();
        List<DamageDtos.DisputeView> disputes = List.of();
        Optional<DamageDebt> debtOpt = debtRepository.findBySettlementId(s.getId());
        if (debtOpt.isPresent()) {
            DamageDebt d = debtOpt.get();
            debt = toDebtView(d);
            payments = paymentRepository.findByDebtIdOrderByIdAsc(d.getId())
                    .stream().map(this::toPaymentView).toList();
            disputes = disputeRepository.findByDebtIdOrderByIdAsc(d.getId())
                    .stream().map(this::toDisputeView).toList();
        }
        String finalStatus = s.getFinalEquipStatus();
        return new DamageDtos.SettlementView(
                s.getId(),
                s.getSettleNo(),
                s.getEquipmentId(),
                s.getEquipCode(),
                s.getEquipType(),
                RangeDict.equipTypeName(s.getEquipType()),
                s.getRenterId(),
                s.getRenterName(),
                s.getStatus(),
                RangeDict.settleStatusName(s.getStatus()),
                money(s.getBaselineBalance()),
                money(s.getTotalFee()),
                money(s.getBalanceDeducted()),
                money(s.getDebtAmount()),
                finalStatus,
                finalStatus == null ? "" : RangeDict.equipStatusName(finalStatus),
                s.getRecheckReason(),
                s.getCreatedBy(),
                s.getCreatedAt(),
                s.getSettledBy(),
                s.getSettledAt(),
                s.getCancelledAt(),
                items,
                logs,
                debt,
                payments,
                disputes);
    }

    private DamageDtos.ItemView toItemView(DamageItem item) {
        return new DamageDtos.ItemView(
                item.getId(),
                item.getItemNo(),
                item.getPart(),
                item.getSeverity(),
                RangeDict.damageSeverityName(item.getSeverity()),
                item.getDescription(),
                item.getResponsibleId(),
                item.getResponsibleName(),
                item.getRuleId(),
                item.getSnapEffectiveFrom(),
                money(item.getSnapFee()),
                Boolean.TRUE.equals(item.getSnapSendRepair()),
                Boolean.TRUE.equals(item.getSnapScrap()),
                money(item.getFinalFee()),
                money(item.getWaivedFee()),
                Boolean.TRUE.equals(item.getWaived()),
                item.getWaiveReason(),
                item.getWaivedBy(),
                item.getWaivedAt());
    }

    private DamageDtos.DebtView toDebtView(DamageDebt debt) {
        DamageSettlement s = settlementRepository.findById(debt.getSettlementId()).orElse(null);
        Member m = memberRepository.findById(debt.getMemberId()).orElse(null);
        boolean openDispute = disputeRepository.countByDebtIdAndStatus(
                debt.getId(), DamageDispute.STATUS_OPEN) > 0;
        BigDecimal remaining = money(debt.getAmount()).subtract(money(debt.getPaidAmount()))
                .setScale(2, RoundingMode.HALF_UP);
        return new DamageDtos.DebtView(
                debt.getId(),
                debt.getDebtNo(),
                debt.getSettlementId(),
                s == null ? "" : s.getSettleNo(),
                debt.getMemberId(),
                m == null ? ("会员#" + debt.getMemberId()) : m.getName(),
                s == null ? "" : s.getEquipCode(),
                money(debt.getAmount()),
                money(debt.getPaidAmount()),
                remaining,
                debt.getStatus(),
                RangeDict.debtStatusName(debt.getStatus()),
                openDispute,
                debt.getCreatedAt(),
                debt.getPaidAt());
    }

    private DamageDtos.PaymentView toPaymentView(DamagePayment p) {
        return new DamageDtos.PaymentView(
                p.getId(),
                p.getDebtId(),
                p.getSettlementId(),
                p.getMemberId(),
                money(p.getAmount()),
                money(p.getBalanceBefore()),
                money(p.getBalanceAfter()),
                money(p.getDebtRemaining()),
                Boolean.TRUE.equals(p.getLiftedRentBan()),
                p.getOperator(),
                p.getCreatedAt());
    }

    private DamageDtos.DisputeView toDisputeView(DamageDispute d) {
        return new DamageDtos.DisputeView(
                d.getId(),
                d.getDebtId(),
                d.getSettlementId(),
                d.getMemberId(),
                d.getReason(),
                d.getStatus(),
                RangeDict.disputeStatusName(d.getStatus()),
                d.getDecisionNote(),
                money(d.getReduceAmount()),
                d.getDecidedBy(),
                d.getCreatedAt(),
                d.getDecidedAt());
    }

    private DamageDtos.LogView toLogView(DamageLog l) {
        return new DamageDtos.LogView(
                l.getId(),
                l.getDebtId(),
                l.getAction(),
                RangeDict.damageActionName(l.getAction()),
                l.getOperator(),
                l.getRole(),
                RangeDict.damageRoleName(l.getRole()),
                l.getDetail(),
                l.getCreatedAt());
    }

    // ================= 内部：锁 / 工具 =================

    private DamageSettlement lockSettlement(Long id) {
        return settlementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("清算单不存在：id=" + id));
    }

    private Equipment lockEquipment(DamageSettlement settlement) {
        return equipmentRepository.findByIdForUpdate(settlement.getEquipmentId())
                .orElseThrow(() -> new BizException("清算器材已不存在：" + settlement.getEquipCode()));
    }

    private Member lockRenter(DamageSettlement settlement) {
        Member renter = memberRepository.findByIdForUpdate(settlement.getRenterId())
                .orElseThrow(() -> new BizException("租借会员不存在：id=" + settlement.getRenterId()));
        memberServiceRejectMerged(renter);
        return renter;
    }

    private void memberServiceRejectMerged(Member member) {
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException("会员 [" + member.getName() + "] 已合并封存，不能参与损伤清算");
        }
    }

    private void log(Long settlementId, Long debtId, String action, String operator, String role,
            String detail) {
        DamageLog entry = new DamageLog();
        entry.setSettlementId(settlementId);
        entry.setDebtId(debtId);
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("清算基线快照序列化失败：" + e.getMessage());
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumFinal(List<DamageItem> items) {
        return items.stream().map(i -> money(i.getFinalFee()))
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumTotal(Long settlementId) {
        return sumFinal(itemRepository.findBySettlementIdOrderByItemNoAsc(settlementId));
    }

    private String nextSettleNo() {
        String no = "D" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (settlementRepository.existsBySettleNo(no)) {
            no = "D" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq++;
        }
        return no;
    }

    private String nextDebtNo() {
        String no = "DB" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (debtRepository.existsByDebtNo(no)) {
            no = "DB" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq++;
        }
        return no;
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
}
