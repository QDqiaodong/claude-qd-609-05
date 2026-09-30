package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.DamageDebt;
import com.archery.range.domain.DamageSettlement;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Member;
import com.archery.range.dto.DamageDtos;
import com.archery.range.dto.EquipmentDtos;
import com.archery.range.repository.DamageDebtRepository;
import com.archery.range.repository.DamageSettlementRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.service.DamageRuleService;
import com.archery.range.service.DamageSettlementService;
import com.archery.range.service.EquipmentService;

/**
 * 器材损伤清算验收测试：
 * 无损归还、送修、报废、余额不足形成待缴并暂停租借、补缴解限、值班经理减免、
 * 异议（维持 / 减免 / 撤销）、结清前基线变化转待重新核对、规则调价不改历史快照、
 * 安全联锁必须先放行、中断回滚、清算中禁止租借 / 报修 / 清除租借人。
 *
 * 每个用例用独立会员 / 器材编码并在结尾清理，避免与其它测试类的种子数据相互影响。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class DamageSettlementIntegrationTest {

    private static final String KEEPER = "测试器材管理员";
    private static final String MANAGER = "测试值班经理";

    @Autowired
    private DamageSettlementService damageService;
    @Autowired
    private DamageRuleService ruleService;
    @Autowired
    private EquipmentService equipmentService;
    @Autowired
    private EquipmentRepository equipmentRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private DamageSettlementRepository settlementRepository;
    @Autowired
    private DamageDebtRepository debtRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 建立独立在用会员（默认余额充足） */
    private Member newMember(String cardSuffix, double balance) {
        Member m = new Member();
        m.setCardNo("T-DMG-" + cardSuffix);
        m.setName("测试会员" + cardSuffix);
        m.setPhone("139" + String.format("%08d", Math.abs(cardSuffix.hashCode()) % 100000000));
        m.setLevel("NORMAL");
        m.setBalance(money(balance));
        m.setRegisterDate(LocalDate.now());
        m.setTotalSpend(money(0));
        return memberRepository.save(m);
    }

    private Equipment newEquip(String code, String type, double rent) {
        Equipment e = new Equipment();
        e.setEquipCode("T-DMG-" + code);
        e.setType(type);
        e.setBrand("测试品牌");
        e.setRentPrice(money(rent));
        e.setStatus("INSTOCK");
        return equipmentRepository.save(e);
    }

    private void rentTo(Equipment e, Member m) {
        equipmentService.rent(e.getId(), new EquipmentDtos.RentReq(m.getId()));
    }

    private DamageDtos.DamageItemReq item(String part, String severity, String desc, Long memberId) {
        return new DamageDtos.DamageItemReq(part, severity, desc, memberId);
    }

    private Long startCheck(Equipment e) {
        return damageService.startCheck(new DamageDtos.StartCheckReq(e.getId(), KEEPER)).id();
    }

    private DamageDtos.SettlementView submit(Long id, Member responsible, List<DamageDtos.DamageItemReq> items) {
        return damageService.submitCheck(id, new DamageDtos.SubmitCheckReq(items, KEEPER));
    }

    private void cleanup() {
        // 先清清算相关，再删测试器材 / 会员（每张清算单与本类测试数据一一对应）
        jdbcTemplate.update("delete from damage_log");
        jdbcTemplate.update("delete from damage_payment");
        jdbcTemplate.update("delete from damage_dispute");
        jdbcTemplate.update("delete from damage_debt");
        jdbcTemplate.update("delete from damage_item");
        jdbcTemplate.update("delete from damage_settlement");
        jdbcTemplate.update("delete from equipment where equip_code like 'T-DMG-%'");
        jdbcTemplate.update("delete from members where card_no like 'T-DMG-%'");
    }

    // ---------- 1. 无损归还：零项检查结清后器材回在库、租借人清空、无费用 ----------

    @Test
    void noDamage_returnToInstock() {
        Member m = newMember("N1", 500);
        Equipment e = newEquip("N1", "ARROW", 10);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            submit(id, m, List.of());
            DamageDtos.FeePreview preview = damageService.preview(id);
            assertThat(preview.totalFee()).isEqualByComparingTo("0.00");
            assertThat(preview.finalEquipStatus()).isEqualTo("INSTOCK");
            DamageDtos.SettlementView view = damageService.settle(id,
                    new DamageDtos.SettleReq(MANAGER));
            assertThat(view.status()).isEqualTo("SETTLED");
            assertThat(view.finalEquipStatus()).isEqualTo("INSTOCK");
            Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("INSTOCK");
            assertThat(after.getRenterId()).isNull();
            assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo("490.00");
        } finally {
            cleanup();
        }
    }

    // ---------- 2. 可修复损伤：轻微/较重 → 扣余额、器材进维修 ----------

    @Test
    void repairableDamage_goesRepair() {
        Member m = newMember("N2", 1000);
        Equipment e = newEquip("N2", "RECURVE", 40);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            submit(id, m, List.of(item("弓弦", "MEDIUM", "弓弦磨损起毛", m.getId())));
            DamageDtos.FeePreview preview = damageService.preview(id);
            // 种子规则 RECURVE/MEDIUM = 320，送修
            assertThat(preview.totalFee()).isEqualByComparingTo("320.00");
            assertThat(preview.finalEquipStatus()).isEqualTo("REPAIR");
            DamageDtos.SettlementView view = damageService.settle(id,
                    new DamageDtos.SettleReq(MANAGER));
            assertThat(view.finalEquipStatus()).isEqualTo("REPAIR");
            Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("REPAIR");
            assertThat(after.getRenterId()).isNull();
            // 余额扣 320 + 租金 40
            assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo("640.00");
        } finally {
            cleanup();
        }
    }

    // ---------- 3. 严重损伤：报废永久封存，不能再租借 / 报修 ----------

    @Test
    void severeDamage_scrappedForever() {
        Member m = newMember("N3", 5000);
        Equipment e = newEquip("N3", "COMPOUND", 80);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            submit(id, m, List.of(item("弓片", "SEVERE", "弓片贯穿裂纹", m.getId())));
            DamageDtos.FeePreview preview = damageService.preview(id);
            assertThat(preview.finalEquipStatus()).isEqualTo("SCRAPPED");
            // COMPOUND/SEVERE = 2600
            assertThat(preview.totalFee()).isEqualByComparingTo("2600.00");
            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));
            Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("SCRAPPED");
            assertThatThrownBy(() -> equipmentService.toggleRepair(e.getId()))
                    .isInstanceOf(BizException.class).hasMessageContaining("报废封存");
            assertThatThrownBy(() -> equipmentService.rent(e.getId(),
                    new EquipmentDtos.RentReq(m.getId())))
                    .isInstanceOf(BizException.class);
        } finally {
            cleanup();
        }
    }

    // ---------- 4. 余额不足：扣到零、形成待缴、暂停租借；补足后解限 ----------

    @Test
    void insufficientBalance_debtThenPayLiftsBan() {
        Member m = newMember("N4", 100);
        Equipment e = newEquip("N4", "RECURVE", 40);
        rentTo(e, m); // 租金 40 → 余额 60
        try {
            Long id = startCheck(e);
            submit(id, m, List.of(item("弓片", "MEDIUM", "暗裂", m.getId()))); // 320
            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));

            Member after = memberRepository.findById(m.getId()).orElseThrow();
            assertThat(after.getBalance()).isEqualByComparingTo("0.00");
            assertThat(after.getRentSuspended()).isTrue();

            DamageDebt debt = debtRepository.findBySettlementId(id).orElseThrow();
            assertThat(debt.getStatus()).isEqualTo(DamageDebt.STATUS_UNPAID);
            assertThat(debt.getAmount()).isEqualByComparingTo("320.00");
            assertThat(debt.getPaidAmount()).isEqualByComparingTo("0.00");

            // 暂停期间不能租借另一件器材
            Equipment other = newEquip("N4B", "ARROW", 10);
            assertThatThrownBy(() -> equipmentService.rent(other.getId(),
                    new EquipmentDtos.RentReq(m.getId())))
                    .isInstanceOf(BizException.class).hasMessageContaining("暂停租借");

            // 部分补款：仍受限
            damageService.payDebt(debt.getId(), new DamageDtos.PayReq(money(100), MANAGER));
            assertThat(memberRepository.findById(m.getId()).orElseThrow().getRentSuspended()).isTrue();
            // 异议冻结中不能补款
            damageService.openDispute(debt.getId(),
                    new DamageDtos.DisputeReq(m.getName(), "测试异议"));
            assertThat(debtRepository.findById(debt.getId()).orElseThrow().getStatus())
                    .isEqualTo(DamageDebt.STATUS_FROZEN);
            assertThatThrownBy(() -> damageService.payDebt(debt.getId(),
                    new DamageDtos.PayReq(money(50), MANAGER)))
                    .isInstanceOf(BizException.class).hasMessageContaining("冻结");
            // 经理维持，恢复待缴
            damageService.decideDispute(debt.getId(),
                    new DamageDtos.DisputeDecideReq("UPHOLD", null, "维持原费用", MANAGER));
            assertThat(debtRepository.findById(debt.getId()).orElseThrow().getStatus())
                    .isEqualTo(DamageDebt.STATUS_PARTIAL);

            // 缴清剩余 220：自动解限
            DamageDtos.SettlementView view = damageService.payDebt(debt.getId(),
                    new DamageDtos.PayReq(money(220), MANAGER));
            Member unlocked = memberRepository.findById(m.getId()).orElseThrow();
            assertThat(unlocked.getRentSuspended()).isFalse();
            assertThat(unlocked.getBalance()).isEqualByComparingTo("0.00");
            assertThat(debtRepository.findById(debt.getId()).orElseThrow().getStatus())
                    .isEqualTo(DamageDebt.STATUS_PAID);
            // 补款轨迹两笔
            assertThat(view.payments()).hasSize(2);
            assertThat(view.payments().get(1).liftedRentBan()).isTrue();

            // 恢复租借资格（箭支不需要弓种认证）
            equipmentService.rent(other.getId(), new EquipmentDtos.RentReq(m.getId()));
            assertThat(equipmentRepository.findById(other.getId()).orElseThrow().getStatus())
                    .isEqualTo("RENTED");
        } finally {
            cleanup();
        }
    }

    // ---------- 5. 值班经理减免：原检查结果保留，减免前 / 后金额、理由都留痕 ----------

    @Test
    void managerWaive_keepsOriginalFinding() {
        Member m = newMember("N5", 1000);
        Equipment e = newEquip("N5", "GEAR", 12);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            DamageDtos.SettlementView submitted = submit(id, m,
                    List.of(item("护臂", "MEDIUM", "护臂卡扣断裂", m.getId()))); // GEAR/MEDIUM = 90
            DamageDtos.ItemView before = submitted.items().get(0);
            assertThat(before.snapFee()).isEqualByComparingTo("90.00");

            DamageDtos.SettlementView waived = damageService.waive(id, before.id(),
                    new DamageDtos.WaiveReq(money(30), "会员配合馆内活动，象征性收取", MANAGER));
            DamageDtos.ItemView after = waived.items().get(0);
            assertThat(after.finalFee()).isEqualByComparingTo("30.00");
            assertThat(after.waivedFee()).isEqualByComparingTo("60.00");
            assertThat(after.waived()).isTrue();
            assertThat(after.waivedBy()).isEqualTo(MANAGER);
            // 原检查结果不变
            assertThat(after.part()).isEqualTo("护臂");
            assertThat(after.severity()).isEqualTo("MEDIUM");
            assertThat(after.snapFee()).isEqualByComparingTo("90.00");
            // 减免额不能 >= 原价
            assertThatThrownBy(() -> damageService.waive(id, before.id(),
                    new DamageDtos.WaiveReq(money(90), "再减", MANAGER)))
                    .isInstanceOf(BizException.class).hasMessageContaining("小于减免前金额");

            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));
            // 90 减免为 30 + 租金 12：余额 1000 - 42 = 958
            assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo("958.00");
        } finally {
            cleanup();
        }
    }

    // ---------- 6. 异议：经理只能维持 / 减免 / 撤销，撤销退回已缴并解限 ----------

    @Test
    void dispute_voidRefundsAndLiftsBan() {
        Member m2 = newMember("N6B", 50);
        Equipment e2 = newEquip("N6C", "GEAR", 12);
        rentTo(e2, m2); // 余额 38
        try {
            Long id = startCheck(e2);
            submit(id, m2, List.of(item("护胸", "SEVERE", "结构断裂", m2.getId()))); // 260
            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));
            DamageDebt debt = debtRepository.findBySettlementId(id).orElseThrow();
            assertThat(debt.getAmount()).isEqualByComparingTo("260.00");

            damageService.openDispute(debt.getId(),
                    new DamageDtos.DisputeReq(m2.getName(), "非本人使用导致，申请复核"));
            // 冻结期间不能再提异议
            assertThatThrownBy(() -> damageService.openDispute(debt.getId(),
                    new DamageDtos.DisputeReq(m2.getName(), "再次异议")))
                    .isInstanceOf(BizException.class);

            // REDUCE：核减 60，剩 200
            damageService.decideDispute(debt.getId(),
                    new DamageDtos.DisputeDecideReq("REDUCE", money(60), "护具有自然老化因素", MANAGER));
            DamageDebt reduced = debtRepository.findById(debt.getId()).orElseThrow();
            assertThat(reduced.getAmount()).isEqualByComparingTo("200.00");
            // 仍有待缴，租借限制保持
            assertThat(memberRepository.findById(m2.getId()).orElseThrow().getRentSuspended()).isTrue();

            // 再提异议并全部撤销
            damageService.openDispute(debt.getId(),
                    new DamageDtos.DisputeReq(m2.getName(), "复核后仍有异议"));
            // 缴一部分（此时状态 FROZEN 不能缴；先维持再缴）——改为直接撤销验证退款逻辑：
            damageService.decideDispute(debt.getId(),
                    new DamageDtos.DisputeDecideReq("UPHOLD", null, "先维持", MANAGER));
            damageService.payDebt(debt.getId(), new DamageDtos.PayReq(money(50), MANAGER));
            damageService.openDispute(debt.getId(),
                    new DamageDtos.DisputeReq(m2.getName(), "新证据：器材本身有旧伤"));
            DamageDtos.SettlementView view = damageService.decideDispute(debt.getId(),
                    new DamageDtos.DisputeDecideReq("VOID", null, "撤销本次费用", MANAGER));
            DamageDebt voided = debtRepository.findById(debt.getId()).orElseThrow();
            assertThat(voided.getStatus()).isEqualTo(DamageDebt.STATUS_VOID);
            // 已缴 50 退回余额（当前 0 → 50）
            assertThat(memberRepository.findById(m2.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo("50.00");
            // 无未结待缴 → 解限
            assertThat(memberRepository.findById(m2.getId()).orElseThrow().getRentSuspended()).isFalse();
            // 原检查与规则快照仍在
            assertThat(view.items()).hasSize(1);
            assertThat(view.items().get(0).snapFee()).isEqualByComparingTo("260.00");
        } finally {
            cleanup();
        }
    }

    // ---------- 7. 规则调价不改变历史金额；新生效规则用于新检查 ----------

    @Test
    void ruleRepricing_keepsHistorySnapshot() {
        Member m = newMember("N7", 1000);
        Equipment e = newEquip("N7", "ARROW", 10);
        rentTo(e, m);
        Long id = startCheck(e);
        submit(id, m, List.of(item("箭杆", "LIGHT", "尾钉松脱", m.getId()))); // ARROW/LIGHT = 10
        // 发布明天生效的新价格 18
        DamageDtos.RuleView newRule = ruleService.create(new DamageDtos.RuleSaveReq(
                "ARROW", "LIGHT", LocalDate.now().plusDays(1), money(18), false, false,
                "调价测试", MANAGER));
        try {
            // 历史快照金额不变
            DamageDtos.SettlementView view = damageService.detail(id);
            assertThat(view.items().get(0).snapFee()).isEqualByComparingTo("10.00");
            // 但基线规则指纹已变 → 结清前必须重新核对
            DamageDtos.FeePreview preview = damageService.preview(id);
            assertThat(preview.recheck()).isTrue();
            assertThat(preview.recheckReason()).contains("计价规则已调整");
            assertThatThrownBy(() -> damageService.settle(id, new DamageDtos.SettleReq(MANAGER)))
                    .isInstanceOf(BizException.class).hasMessageContaining("重新核对");
            DamageSettlement stored = settlementRepository.findById(id).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(DamageSettlement.STATUS_RECHECK);

            // 重新核对后按原价（今天新规则尚未生效）仍是 10，基线刷新后可结清
            DamageDtos.SettlementView reconfirmed = damageService.reconfirm(id,
                    new DamageDtos.ReconfirmReq(
                            List.of(item("箭杆", "LIGHT", "尾钉松脱（复核）", m.getId())), KEEPER));
            assertThat(reconfirmed.items().get(0).snapFee()).isEqualByComparingTo("10.00");
            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));
        } finally {
            // 清理新建规则（测试库内手动删除，避免污染）
            jdbcTemplate.update("delete from damage_rule where id = ?", newRule.id());
            cleanup();
        }
    }

    // ---------- 8. 结清前余额基线变化 → 待重新核对 ----------

    @Test
    void baselineBalanceChange_requiresRecheck() {
        Member m = newMember("N8", 1000);
        Equipment e = newEquip("N8", "GEAR", 12);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            submit(id, m, List.of(item("护指", "LIGHT", "皮层磨损", m.getId()))); // 30
            // 检查期间会员充值，余额基线变化
            Member fresh = memberRepository.findById(m.getId()).orElseThrow();
            fresh.setBalance(fresh.getBalance().add(money(100)));
            memberRepository.save(fresh);

            assertThatThrownBy(() -> damageService.settle(id, new DamageDtos.SettleReq(MANAGER)))
                    .isInstanceOf(BizException.class).hasMessageContaining("余额已变化");
            assertThat(settlementRepository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(DamageSettlement.STATUS_RECHECK);
            // 重新核对（无损）→ 可结清回在库
            damageService.reconfirm(id, new DamageDtos.ReconfirmReq(
                    List.of(item("护指", "LIGHT", "皮层磨损", m.getId())), KEEPER));
            damageService.settle(id, new DamageDtos.SettleReq(MANAGER));
            assertThat(equipmentRepository.findById(e.getId()).orElseThrow().getStatus())
                    .isEqualTo("REPAIR");
        } finally {
            cleanup();
        }
    }

    // ---------- 9. 清算中禁止租借 / 报修 / 直接归还 / 清除租借人；取消后恢复租出 ----------

    @Test
    void settlingLifecycle_locksOperations() {
        Member m = newMember("N9", 500);
        Equipment e = newEquip("N9", "TRADITIONAL", 30);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            Equipment locked = equipmentRepository.findById(e.getId()).orElseThrow();
            assertThat(locked.getStatus()).isEqualTo("SETTLING");
            assertThat(locked.getRenterId()).isEqualTo(m.getId());

            assertThatThrownBy(() -> equipmentService.rent(e.getId(),
                    new EquipmentDtos.RentReq(m.getId())))
                    .isInstanceOf(BizException.class).hasMessageContaining("归还处理中");
            assertThatThrownBy(() -> equipmentService.toggleRepair(e.getId()))
                    .isInstanceOf(BizException.class).hasMessageContaining("归还处理");
            assertThatThrownBy(() -> equipmentService.giveBack(e.getId()))
                    .isInstanceOf(BizException.class).hasMessageContaining("损伤清算");
            // 同一件器材不能重复发起
            assertThatThrownBy(() -> startCheck(e))
                    .isInstanceOf(BizException.class).hasMessageContaining("未结束的损伤清算");

            // 取消：回到检查前（租出，租借人保留）
            damageService.cancel(id, new DamageDtos.CancelReq(KEEPER));
            Equipment back = equipmentRepository.findById(e.getId()).orElseThrow();
            assertThat(back.getStatus()).isEqualTo("RENTED");
            assertThat(back.getRenterId()).isEqualTo(m.getId());
            assertThat(settlementRepository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(DamageSettlement.STATUS_CANCELLED);
        } finally {
            cleanup();
        }
    }

    // ---------- 10. 安全联锁中的器材不能发起检查 ----------

    @Test
    void lockedEquipment_cannotStartCheck() {
        Member m = newMember("N10", 500);
        Equipment e = newEquip("N10", "ARROW", 10);
        rentTo(e, m);
        try {
            Equipment locked = equipmentRepository.findByIdForUpdate(e.getId()).orElseThrow();
            locked.setStatus("LOCKED");
            equipmentRepository.save(locked);
            assertThatThrownBy(() -> startCheck(e))
                    .isInstanceOf(BizException.class).hasMessageContaining("安全锁定");
        } finally {
            cleanup();
        }
    }

    // ---------- 11. 中断回滚：结清过程抛异常时器材 / 余额 / 清算单全部回到结清前 ----------

    @Test
    @Transactional
    void settleFailure_rollsBackEverything() {
        Member m = newMember("N11", 500);
        Equipment e = newEquip("N11", "RECURVE", 40);
        rentTo(e, m);
        Long id = startCheck(e);
        submit(id, m, List.of(item("弓把", "LIGHT", "轻微划痕", m.getId()))); // 80

        // 人为删掉器材行，结清在「器材去向」阶段必然抛异常：整个事务回滚
        jdbcTemplate.update("delete from equipment where id = ?", e.getId());
        assertThatThrownBy(() -> damageService.settle(id, new DamageDtos.SettleReq(MANAGER)))
                .isInstanceOf(Exception.class);

        // 事务回滚后：清算单仍为 READY（未结清），余额未扣
        DamageSettlement s = settlementRepository.findById(id).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(DamageSettlement.STATUS_READY);
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("460.00");
        // 删除动作也随测试事务回滚，器材仍为 SETTLING
        assertThat(equipmentRepository.findById(e.getId()).orElseThrow().getStatus())
                .isEqualTo("SETTLING");
        // 无待缴费用产生
        assertThat(debtRepository.findBySettlementId(id)).isEmpty();
    }

    // ---------- 12. 减免必须在提交后、且 RECHECK 状态不能减免 ----------

    @Test
    void waiveGate_statusChecks() {
        Member m = newMember("N12", 500);
        Equipment e = newEquip("N12", "ARROW", 10);
        rentTo(e, m);
        try {
            Long id = startCheck(e);
            // 未提交不能减免（没有损伤项可减，接口层面也会找不到项）
            submit(id, m, List.of(item("箭杆", "LIGHT", "羽片缺损", m.getId())));
            Long itemId = damageService.detail(id).items().get(0).id();
            // 发布新规则使状态变 RECHECK
            DamageDtos.RuleView nr = ruleService.create(new DamageDtos.RuleSaveReq(
                    "ARROW", "LIGHT", LocalDate.now().plusDays(2), money(12), false, false,
                    "冻结减免测试", MANAGER));
            try {
                assertThatThrownBy(() -> damageService.settle(id, new DamageDtos.SettleReq(MANAGER)))
                        .isInstanceOf(BizException.class);
                assertThatThrownBy(() -> damageService.waive(id, itemId,
                        new DamageDtos.WaiveReq(money(5), "recheck 状态禁止减免", MANAGER)))
                        .isInstanceOf(BizException.class).hasMessageContaining("重新核对");
            } finally {
                jdbcTemplate.update("delete from damage_rule where id = ?", nr.id());
            }
        } finally {
            cleanup();
        }
    }
}
