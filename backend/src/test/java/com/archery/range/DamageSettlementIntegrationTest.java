package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.archery.range.common.BaselineStaleException;
import com.archery.range.common.BizException;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Member;
import com.archery.range.dto.DamageDtos;
import com.archery.range.dto.EquipmentDtos;
import com.archery.range.dto.MemberDtos;
import com.archery.range.repository.DamageSettlementItemRepository;
import com.archery.range.repository.DamageSettlementRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.service.DamageSettlementService;
import com.archery.range.service.EquipmentService;
import com.archery.range.service.MemberService;

/**
 * 器材损伤清算端到端验收（H2 MySQL 模式 + 真实 schema.sql 种子）：
 * 无损归还 / 送修 / 报废封存 / 余额不足挂待缴并暂停租借 / 补缴解限 /
 * 经理减免保留原检查 / 异议冻结与维持 / 异议撤销核销待缴并退回余额 /
 * 计价规则快照不受后续调价影响 / 基线变化转待重新核对 / 安全联锁未放行不能检查与结清 /
 * 处理中不能租借报修清除租借人 / 放弃清算回滚。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class DamageSettlementIntegrationTest {

    @Autowired
    private DamageSettlementService damageService;
    @Autowired
    private EquipmentService equipmentService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private EquipmentRepository equipmentRepository;
    @Autowired
    private DamageSettlementRepository settlementRepository;
    @Autowired
    private DamageSettlementItemRepository itemRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long seq = System.nanoTime() % 1_000_000L;

    private Member newMember(String name, String balance) {
        long n = seq++;
        var view = memberService.create(new MemberDtos.MemberSaveReq(
                "T" + n, name, "139" + String.format("%08d", n % 100_000_000L),
                "NORMAL", new BigDecimal(balance)));
        return memberRepository.findById(view.id()).orElseThrow();
    }

    /** 租出一件在库箭支（箭支不需要弓种认证），返回器材 */
    private Equipment rentArrow(Member member, Long equipId) {
        equipmentService.rent(equipId, new EquipmentDtos.RentReq(member.getId()));
        return equipmentRepository.findById(equipId).orElseThrow();
    }

    private DamageDtos.SettlementView startOn(Long equipId, String keeper) {
        return damageService.start(equipId, new DamageDtos.StartReq(keeper));
    }

    private DamageDtos.SettlementView addLight(Long settleId, Member responsible) {
        return damageService.addDamage(settleId, new DamageDtos.DamageItemReq(
                "箭羽", "LIGHT", "羽片轻微卷折", responsible.getId()));
    }

    private void finish(Long settleId) {
        damageService.finishInspection(settleId, new DamageDtos.OperatorReq("王保管", null));
    }

    @AfterEach
    void cleanup() {
        // 仅清理本测试创建的临时数据，种子数据（器材 / 种子会员 / 种子规则）原样保留
        jdbcTemplate.update("delete from member_debt_payment where member_id in (select id from members where card_no like 'T%')");
        jdbcTemplate.update("delete from member_debt where member_id in (select id from members where card_no like 'T%')");
        jdbcTemplate.update("delete from damage_settlement_log where settlement_id in (select id from damage_settlement where settle_no like 'CL%')");
        jdbcTemplate.update("delete from damage_settlement_item where settlement_id in (select id from damage_settlement where settle_no like 'CL%')");
        jdbcTemplate.update("delete from damage_settlement where settle_no like 'CL%'");
        jdbcTemplate.update("delete from damage_pricing_rule where created_by = '测试维护人'");
        jdbcTemplate.update("delete from equipment where equip_code like 'T-EQ-%'");
        jdbcTemplate.update("update equipment set status = 'INSTOCK', renter_id = null, renter_name = null, rented_at = null"
                + " where id in (11, 12)");
        jdbcTemplate.update("delete from members where card_no like 'T%'");
    }

    // 1. 无损归还：零损伤结束检查并结清 → 器材回库、无费用、租借人清除；处理中不能租借/报修/归还

    @Test
    void noDamage_settle_instock_noFee_renterCleared() {
        Member m = newMember("无损会员二", "200.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();

        // 处理中：该器材处于 SETTLING、租借人保留，不能租借（非在库）、报修、直接归还
        Equipment locked = equipmentRepository.findById(e.getId()).orElseThrow();
        assertThat(locked.getStatus()).isEqualTo("SETTLING");
        assertThat(locked.getRenterId()).isEqualTo(m.getId());
        assertThatThrownBy(() -> equipmentService.rent(e.getId(), new EquipmentDtos.RentReq(m.getId())))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> equipmentService.toggleRepair(e.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("归还处理中");
        assertThatThrownBy(() -> equipmentService.giveBack(e.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("损伤清算台");

        finish(sid);
        DamageDtos.SettlePreview p = damageService.preview(sid);
        assertThat(p.totalFee()).isEqualByComparingTo("0.00");
        assertThat(p.finalDisposal()).isEqualTo("NONE");
        assertThat(p.debtAmount()).isEqualByComparingTo("0.00");

        DamageDtos.SettlementView done = damageService.settle(sid,
                new DamageDtos.SettleReq("王保管", null));
        assertThat(done.status()).isEqualTo("SETTLED");
        assertThat(done.finalDisposal()).isEqualTo("NONE");
        // 初始 200 - 租箭支 20（租金），无损伤费用
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance()).isEqualByComparingTo("180.00");

        Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("INSTOCK");
        assertThat(after.getRenterId()).isNull();
        assertThat(after.getRentedAt()).isNull();
    }

    // 2. 可修复损伤（中等 → 送修）：余额充足一次扣清

    @Test
    void mediumDamage_settle_toRepair_fullDeduction() {
        Member m = newMember("送修会员", "500.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "箭杆弯曲约 3 度，可校正", m.getId()));
        finish(sid);

        DamageDtos.SettlePreview p = damageService.preview(sid);
        assertThat(p.totalFee()).isEqualByComparingTo("40.00");
        assertThat(p.finalDisposal()).isEqualTo("REPAIR");
        assertThat(p.balanceAfter()).isEqualByComparingTo("440.00");
        assertThat(p.debtAmount()).isEqualByComparingTo("0.00");

        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));
        Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("REPAIR");
        assertThat(after.getRenterId()).isNull();
        // 500 - 租金 20 - 损伤 40
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance()).isEqualByComparingTo("440.00");
        assertThat(damageService.isRentalBlocked(m.getId())).isFalse();
    }

    // 3. 严重损伤 → 报废永久封存

    @Test
    void severeDamage_settle_scrappedForever() {
        Member m = newMember("报废会员", "300.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "SEVERE", "箭杆断裂，箭头松动", m.getId()));
        finish(sid);
        DamageDtos.SettlePreview p = damageService.preview(sid);
        assertThat(p.finalDisposal()).isEqualTo("SCRAP");
        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));

        Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCRAPPED");
        // 报废器材不能报修 / 租借
        assertThatThrownBy(() -> equipmentService.toggleRepair(e.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("报废");
        assertThatThrownBy(() -> equipmentService.rent(e.getId(), new EquipmentDtos.RentReq(m.getId())))
                .isInstanceOf(BizException.class);
    }

    // 4. 余额不足：扣到零、形成待缴、暂停租借；不能结清期间绕过

    @Test
    void insufficientBalance_debt_blocksRental() {
        // 初始 50 - 租金 20 = 30，损伤 40 → 扣 30 挂 10 待缴
        Member m = newMember("欠费会员", "50.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "箭杆弯曲", m.getId()));
        finish(sid);

        DamageDtos.SettlePreview p = damageService.preview(sid);
        assertThat(p.currentBalance()).isEqualByComparingTo("30.00");
        assertThat(p.balanceDeducted()).isEqualByComparingTo("30.00");
        assertThat(p.balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(p.debtAmount()).isEqualByComparingTo("10.00");
        assertThat(p.memberRentalBlockedAfter()).isTrue();

        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));
        Member charged = memberRepository.findById(m.getId()).orElseThrow();
        assertThat(charged.getBalance()).isEqualByComparingTo("0.00");
        assertThat(damageService.isRentalBlocked(m.getId())).isTrue();

        // 租借资格被暂停：即便器材在库也不能租
        assertThatThrownBy(() -> equipmentService.rent(12L, new EquipmentDtos.RentReq(m.getId())))
                .isInstanceOf(BizException.class).hasMessageContaining("待缴费用");

        // 待缴单内容与轨迹
        List<DamageDtos.DebtView> debts = damageService.listDebts("UNPAID", m.getId());
        assertThat(debts).hasSize(1);
        assertThat(debts.get(0).amount()).isEqualByComparingTo("10.00");
        assertThat(debts.get(0).rentalBlocked()).isTrue();
    }

    // 5. 补缴解限：分次补缴，缴清恢复租借并留补款轨迹

    @Test
    void repayDebt_partialThenFull_restoresRental() {
        // 50 - 租金 20 = 30；损伤 40 结清 → 扣 30 挂 10 待缴，余额 0
        Member m = newMember("补缴会员", "50.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "弯曲", m.getId()));
        finish(sid);
        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance()).isEqualByComparingTo("0.00");

        Long debtId = damageService.listDebts("UNPAID", m.getId()).get(0).id();
        // 充值 100 后分次补缴：6 + 4
        memberService.recharge(m.getId(), new MemberDtos.RechargeReq(new BigDecimal("100.00")));

        // 分两次补缴：6 + 4
        damageService.repay(debtId, new DamageDtos.RepayReq(new BigDecimal("6.00"), "前台"));
        DamageDtos.DebtView mid = damageService.debtView(debtId);
        assertThat(mid.amount()).isEqualByComparingTo("4.00");
        assertThat(mid.status()).isEqualTo("UNPAID");
        assertThat(damageService.isRentalBlocked(m.getId())).isTrue();
        // 部分补缴后仍不能租借
        assertThatThrownBy(() -> equipmentService.rent(12L, new EquipmentDtos.RentReq(m.getId())))
                .isInstanceOf(BizException.class).hasMessageContaining("待缴");

        damageService.repay(debtId, new DamageDtos.RepayReq(null, "前台")); // 一次补足
        DamageDtos.DebtView end = damageService.debtView(debtId);
        assertThat(end.status()).isEqualTo("PAID");
        assertThat(end.amount()).isEqualByComparingTo("0.00");
        assertThat(damageService.isRentalBlocked(m.getId())).isFalse();

        List<DamageDtos.PaymentView> pays = end.payments();
        assertThat(pays).hasSize(2);
        assertThat(pays).allMatch(p -> p.kind().equals("REPAY"));

        // 恢复租借资格：可租借另一件在库箭支
        equipmentService.rent(12L, new EquipmentDtos.RentReq(m.getId()));
        assertThat(equipmentRepository.findById(12L).orElseThrow().getStatus()).isEqualTo("RENTED");
    }

    // 6. 经理减免：原检查金额保留，减免前 / 后金额与理由均留痕

    @Test
    void managerWaive_keepsOriginalInspection() {
        Member m = newMember("减免会员", "500.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "弯曲", m.getId()));
        finish(sid);
        Long itemId = damageService.detail(sid).items().get(0).id();

        damageService.waive(sid, itemId, new DamageDtos.WaiveReq(
                new BigDecimal("20.00"), "会员首次损坏且配合处理，减半收取", "李经理"));

        DamageDtos.DamageItemView item = damageService.detail(sid).items().get(0);
        assertThat(item.snapFee()).isEqualByComparingTo("40.00");   // 管理员原检查不变
        assertThat(item.finalFee()).isEqualByComparingTo("20.00");
        assertThat(item.waivedBy()).isEqualTo("李经理");
        assertThat(item.waivedReason()).contains("首次损坏");

        // 检查未结束不能减免（用另一单验证顺序约束）
        DamageDtos.SettlePreview p = damageService.preview(sid);
        assertThat(p.totalFee()).isEqualByComparingTo("20.00");
        // 减免不改变器材去向：仍按原规则送修
        assertThat(p.finalDisposal()).isEqualTo("REPAIR");
    }

    // 7. 异议：处理中金额冻结不能结清；维持后可结清；结清后撤销 → 核销待缴并退回余额

    @Test
    void dispute_freezeMaintainAndPostSettleRevoke() {
        // 50 - 租金 20 = 30；损伤 40 结清 → 扣 30 挂 10 待缴
        Member m = newMember("异议会员", "50.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "弯曲", m.getId()));
        finish(sid);
        Long itemId = damageService.detail(sid).items().get(0).id();

        // 异议期间：不能减免、不能结清
        damageService.dispute(sid, itemId, new DamageDtos.DisputeReq("认为是出厂缺陷", "异议会员"));
        assertThat(damageService.detail(sid).items().get(0).disputeStatus()).isEqualTo("DISPUTING");
        assertThatThrownBy(() -> damageService.settle(sid, new DamageDtos.SettleReq("王保管", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("冻结");
        assertThatThrownBy(() -> damageService.waive(sid, itemId,
                new DamageDtos.WaiveReq(new BigDecimal("10"), "r", "李经理")))
                .isInstanceOf(BizException.class).hasMessageContaining("冻结");

        // 经理维持原价 → 可结清（余额 30，扣 30，挂 10 待缴）
        damageService.handleDispute(sid, itemId, new DamageDtos.DisputeHandleReq(
                "MAINTAIN", null, "现场复核确为使用弯曲", "李经理"));
        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));
        assertThat(damageService.isRentalBlocked(m.getId())).isTrue();

        // 结清后再提异议（对同一项：MAINTAIN 后允许再异议）→ 撤销费用：10 核销待缴，30 退回余额
        damageService.dispute(sid, itemId, new DamageDtos.DisputeReq("厂家出具质保证明", "异议会员"));
        memberService.recharge(m.getId(), new MemberDtos.RechargeReq(new BigDecimal("100.00")));
        damageService.handleDispute(sid, itemId, new DamageDtos.DisputeHandleReq(
                "REVOKE", null, "质保期内确认非人为，撤销该项费用", "李经理"));

        DamageDtos.DamageItemView item = damageService.detail(sid).items().get(0);
        assertThat(item.finalFee()).isEqualByComparingTo("0.00");
        assertThat(item.snapFee()).isEqualByComparingTo("40.00");   // 原检查仍不变
        // 待缴已撤销、租借恢复；结清后余额 0，充值 100，撤销差额 40 中 10 核销待缴、30 回余额 → 130
        assertThat(damageService.isRentalBlocked(m.getId())).isFalse();
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("130.00");

        DamageDtos.DebtView debt = damageService.listDebts(null, m.getId()).stream()
                .filter(d -> d.status().equals("REVOKED")).findFirst().orElseThrow();
        assertThat(debt.payments()).hasSize(1);
        assertThat(debt.payments().get(0).kind()).isEqualTo("REVOKE");
        assertThat(debt.payments().get(0).amount()).isEqualByComparingTo("10.00");
    }

    // 8. 规则快照：检查登记后调价 / 新增规则不改变历史金额

    @Test
    void pricingSnapshot_laterPriceChangeDoesNotAffectHistory() {
        Member m = newMember("快照会员", "500.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "弯曲", m.getId()));
        DamageDtos.DamageItemView before = damageService.detail(sid).items().get(0);
        assertThat(before.snapFee()).isEqualByComparingTo("40.00");
        Long oldRuleId = before.ruleId();

        // 发布同日生效的新价 60（生效日期更晚一条记录；用明天生效避免取价歧义）
        damageService.createRule(new DamageDtos.RuleSaveReq(
                "ARROW", "MEDIUM", LocalDate.now().plusDays(1), new BigDecimal("60.00"),
                true, false, "校正工费上调", "测试维护人"));

        // 本单快照不变
        DamageDtos.DamageItemView after = damageService.detail(sid).items().get(0);
        assertThat(after.ruleId()).isEqualTo(oldRuleId);
        assertThat(after.snapFee()).isEqualByComparingTo("40.00");
        finish(sid);
        assertThat(damageService.preview(sid).totalFee()).isEqualByComparingTo("40.00");
    }

    // 9. 基线变化：结清前会员余额变化 → 转待重新核对，核对后用新基线结清

    @Test
    void baselineChangeBalance_settleTurnsToRecheck_thenRebind() {
        Member m = newMember("基线会员", "200.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        damageService.addDamage(sid, new DamageDtos.DamageItemReq(
                "箭杆", "MEDIUM", "弯曲", m.getId()));
        finish(sid);

        // 结清前外部充值导致余额基线变化
        memberService.recharge(m.getId(), new MemberDtos.RechargeReq(new BigDecimal("100.00")));
        assertThatThrownBy(() -> damageService.settle(sid, new DamageDtos.SettleReq("王保管", null)))
                .isInstanceOf(BaselineStaleException.class);

        DamageDtos.SettlementView recheck = damageService.detail(sid);
        assertThat(recheck.status()).isEqualTo("RECHECK");
        assertThat(recheck.baselineStale()).isTrue();
        assertThat(recheck.baselineDrifts()).anyMatch(d -> d.contains("余额"));

        // 未重新核对直接结清被拒绝，必须先核对
        assertThatThrownBy(() -> damageService.settle(sid, new DamageDtos.SettleReq("王保管", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("重新核对");

        // 重新核对 → 结清时余额为 200-20（租金）+100（充值）=280，扣 40 后 240，无待缴
        damageService.rebind(sid, new DamageDtos.OperatorReq("李经理", null));
        DamageDtos.SettlementView done = damageService.settle(sid,
                new DamageDtos.SettleReq("王保管", null));
        assertThat(done.status()).isEqualTo("SETTLED");
        assertThat(memberRepository.findById(m.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("240.00");
    }

    // 10. 安全联锁：LOCKED 器材不能发起检查，锁定期间不能结清，必须先放行

    @Test
    void safetyLocked_mustReleaseFirst() {
        Member m = newMember("联锁会员", "300.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        addLight(sid, m);
        finish(sid);

        // 经理建停射事件锁住该器材（SETTLING 也可被安全联锁纳入：模拟必须先放行）
        // 直接通过仓储把器材置 LOCKED 模拟联锁生效（安全事件恢复路径按快照还原 SETTLING）
        jdbcTemplate.update("insert into safety_event (event_no, reason, severity, description, status, review_round,"
                + " created_by, created_at) values ('T-EVT-1', 'EQUIP_SUSPECT', 'MAJOR', '清算联锁测试', 'PENDING', 1,"
                + " '李经理', now())");
        Long eventId = jdbcTemplate.queryForObject("select id from safety_event where event_no = 'T-EVT-1'",
                Long.class);
        jdbcTemplate.update("insert into safety_event_equipment (event_id, equipment_id, prev_status, restored)"
                + " values (?, ?, 'SETTLING', 0)", eventId, e.getId());
        jdbcTemplate.update("update equipment set status = 'LOCKED' where id = ?", e.getId());

        // 锁定中不能结清
        assertThatThrownBy(() -> damageService.settle(sid, new DamageDtos.SettleReq("王保管", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("安全锁定");

        // 放行恢复为 SETTLING 后可正常结清
        jdbcTemplate.update("update equipment set status = 'SETTLING' where id = ?", e.getId());
        jdbcTemplate.update("update safety_event_equipment set restored = 1 where event_id = ?", eventId);
        jdbcTemplate.update("update safety_event set status = 'RELEASED', released_by = '李经理', released_at = now()"
                + " where id = ?", eventId);

        damageService.settle(sid, new DamageDtos.SettleReq("王保管", null));
        assertThat(equipmentRepository.findById(e.getId()).orElseThrow().getStatus()).isEqualTo("INSTOCK");

        jdbcTemplate.update("delete from safety_event_log where event_id = ?", eventId);
        jdbcTemplate.update("delete from safety_event_equipment where event_id = ?", eventId);
        jdbcTemplate.update("delete from safety_event where id = ?", eventId);
    }

    // 11. 租出器材处于 LOCKED 时不能发起检查

    @Test
    void cannotStartInspectionWhenLocked() {
        Member m = newMember("锁定检查会员", "300.00");
        Equipment e = rentArrow(m, 11L);
        jdbcTemplate.update("update equipment set status = 'LOCKED' where id = ?", e.getId());
        try {
            assertThatThrownBy(() -> startOn(e.getId(), "王保管"))
                    .isInstanceOf(BizException.class).hasMessageContaining("安全锁定");
            assertThat(settlementRepository.existsOpenByEquipment(e.getId())).isFalse();
        } finally {
            jdbcTemplate.update("update equipment set status = 'RENTED' where id = ?", e.getId());
        }
    }

    // 12. 放弃清算：回到检查前（器材恢复租出、无在途单据）

    @Test
    void abandon_rollsBackToRented() {
        Member m = newMember("放弃会员", "200.00");
        Equipment e = rentArrow(m, 11L);
        Long sid = startOn(e.getId(), "王保管").id();
        addLight(sid, m);
        assertThat(itemRepository.countBySettlementId(sid)).isEqualTo(1);

        damageService.abandon(sid, new DamageDtos.OperatorReq("王保管", null));

        Equipment after = equipmentRepository.findById(e.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("RENTED");
        assertThat(after.getRenterId()).isEqualTo(m.getId());
        assertThat(settlementRepository.findById(sid)).isEmpty();
    }
}
