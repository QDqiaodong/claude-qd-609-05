package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 器材损伤清算单。
 *
 * 状态机：
 * INSPECTING 检查中 —— 器材为「归还处理中」（SETTLING），不能再次租借 / 切换维修 / 清除租借人；
 * PENDING_SETTLE 待结清 —— 检查登记完毕，等待结清；
 * RECHECK 待重新核对 —— 结清前发现基线（租借人 / 器材状态 / 会员余额）漂移，核对后回 PENDING_SETTLE；
 * SETTLED 已结清 —— 器材按去向落到 在库 / 维修 / 报废封存。
 *
 * 检查开始时绑定四条基线：租借人、器材状态、计价规则（见损伤项快照）、会员余额。
 * 结清事务一次完成：损伤明细 / 余额扣减 / 待缴费用 / 会员租借限制 / 器材去向 / 轨迹，任一步异常整体回滚。
 */
@Entity
@Table(name = "damage_settlement")
public class DamageSettlement {

    public static final String STATUS_INSPECTING = "INSPECTING";
    public static final String STATUS_PENDING_SETTLE = "PENDING_SETTLE";
    public static final String STATUS_RECHECK = "RECHECK";
    public static final String STATUS_SETTLED = "SETTLED";

    /** 结清去向：无损回库 / 送修 / 报废封存 */
    public static final String DISPOSAL_NONE = "NONE";
    public static final String DISPOSAL_REPAIR = "REPAIR";
    public static final String DISPOSAL_SCRAP = "SCRAP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settle_no", nullable = false, unique = true, length = 24)
    private String settleNo;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "equip_code", nullable = false, length = 24)
    private String equipCode;

    @Column(name = "equip_type", nullable = false, length = 16)
    private String equipType;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "member_name", nullable = false, length = 32)
    private String memberName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "baseline_equip_status", nullable = false, length = 16)
    private String baselineEquipStatus;

    @Column(name = "baseline_renter_id", nullable = false)
    private Long baselineRenterId;

    @Column(name = "baseline_renter_name", nullable = false, length = 32)
    private String baselineRenterName;

    @Column(name = "baseline_rented_at")
    private LocalDateTime baselineRentedAt;

    @Column(name = "baseline_balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal baselineBalance;

    @Column(name = "final_disposal", length = 16)
    private String finalDisposal;

    @Column(name = "total_fee", precision = 10, scale = 2)
    private BigDecimal totalFee;

    @Column(name = "balance_deducted", precision = 10, scale = 2)
    private BigDecimal balanceDeducted;

    @Column(name = "debt_amount", precision = 10, scale = 2)
    private BigDecimal debtAmount;

    @Column(nullable = false, length = 32)
    private String inspector;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "settled_by", length = 32)
    private String settledBy;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settle_note", length = 500)
    private String settleNote;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSettleNo() {
        return settleNo;
    }

    public void setSettleNo(String settleNo) {
        this.settleNo = settleNo;
    }

    public Long getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(Long equipmentId) {
        this.equipmentId = equipmentId;
    }

    public String getEquipCode() {
        return equipCode;
    }

    public void setEquipCode(String equipCode) {
        this.equipCode = equipCode;
    }

    public String getEquipType() {
        return equipType;
    }

    public void setEquipType(String equipType) {
        this.equipType = equipType;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBaselineEquipStatus() {
        return baselineEquipStatus;
    }

    public void setBaselineEquipStatus(String baselineEquipStatus) {
        this.baselineEquipStatus = baselineEquipStatus;
    }

    public Long getBaselineRenterId() {
        return baselineRenterId;
    }

    public void setBaselineRenterId(Long baselineRenterId) {
        this.baselineRenterId = baselineRenterId;
    }

    public String getBaselineRenterName() {
        return baselineRenterName;
    }

    public void setBaselineRenterName(String baselineRenterName) {
        this.baselineRenterName = baselineRenterName;
    }

    public LocalDateTime getBaselineRentedAt() {
        return baselineRentedAt;
    }

    public void setBaselineRentedAt(LocalDateTime baselineRentedAt) {
        this.baselineRentedAt = baselineRentedAt;
    }

    public BigDecimal getBaselineBalance() {
        return baselineBalance;
    }

    public void setBaselineBalance(BigDecimal baselineBalance) {
        this.baselineBalance = baselineBalance;
    }

    public String getFinalDisposal() {
        return finalDisposal;
    }

    public void setFinalDisposal(String finalDisposal) {
        this.finalDisposal = finalDisposal;
    }

    public BigDecimal getTotalFee() {
        return totalFee;
    }

    public void setTotalFee(BigDecimal totalFee) {
        this.totalFee = totalFee;
    }

    public BigDecimal getBalanceDeducted() {
        return balanceDeducted;
    }

    public void setBalanceDeducted(BigDecimal balanceDeducted) {
        this.balanceDeducted = balanceDeducted;
    }

    public BigDecimal getDebtAmount() {
        return debtAmount;
    }

    public void setDebtAmount(BigDecimal debtAmount) {
        this.debtAmount = debtAmount;
    }

    public String getInspector() {
        return inspector;
    }

    public void setInspector(String inspector) {
        this.inspector = inspector;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSettledBy() {
        return settledBy;
    }

    public void setSettledBy(String settledBy) {
        this.settledBy = settledBy;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    public String getSettleNote() {
        return settleNote;
    }

    public void setSettleNote(String settleNote) {
        this.settleNote = settleNote;
    }
}
