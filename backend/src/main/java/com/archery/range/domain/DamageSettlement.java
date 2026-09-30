package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 检查开始即绑定四条基线（见 baseline* 字段）：检查开始时的租借人、器材状态、
 * 当时适用的计价规则指纹、会员余额。结清前任一基线变化都不能沿用旧结果，
 * 单子转为 RECHECK（待重新核对），由器材管理员重新核对后才能结清。
 */
@Entity
@Table(name = "damage_settlement")
public class DamageSettlement {

    /** 检查中：管理员正在逐项登记损伤（器材 SETTLING） */
    public static final String STATUS_CHECKING = "CHECKING";
    /** 待结清：检查已提交、费用明细已出，可结清 */
    public static final String STATUS_READY = "READY";
    /** 待重新核对：结清前基线发生变化（规则调价 / 租借人变化 / 余额变化） */
    public static final String STATUS_RECHECK = "RECHECK";
    /** 已结清：费用扣减与待缴、会员限制、器材去向已一次完成 */
    public static final String STATUS_SETTLED = "SETTLED";
    /** 已取消：检查中止，器材恢复租出 */
    public static final String STATUS_CANCELLED = "CANCELLED";

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

    @Column(name = "renter_id", nullable = false)
    private Long renterId;

    @Column(name = "renter_name", nullable = false, length = 32)
    private String renterName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "baseline_date", nullable = false)
    private LocalDate baselineDate;

    @Column(name = "baseline_equip_status", nullable = false, length = 16)
    private String baselineEquipStatus;

    @Column(name = "baseline_balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal baselineBalance;

    @Column(name = "baseline_rule_digest", nullable = false, length = 64)
    private String baselineRuleDigest;

    /** 基线明细快照（器材 / 租借人 / 余额 / 规则列表） */
    @jakarta.persistence.Lob
    @Column(name = "baseline_snapshot_json", nullable = false)
    private String baselineSnapshotJson;

    @Column(name = "total_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(name = "balance_deducted", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceDeducted = BigDecimal.ZERO;

    @Column(name = "debt_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal debtAmount = BigDecimal.ZERO;

    /** INSTOCK 在库 / REPAIR 维修 / SCRAPPED 报废封存 */
    @Column(name = "final_equip_status", length = 16)
    private String finalEquipStatus;

    @Column(name = "recheck_reason", length = 500)
    private String recheckReason;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settled_by", length = 32)
    private String settledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

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

    public Long getRenterId() {
        return renterId;
    }

    public void setRenterId(Long renterId) {
        this.renterId = renterId;
    }

    public String getRenterName() {
        return renterName;
    }

    public void setRenterName(String renterName) {
        this.renterName = renterName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getBaselineDate() {
        return baselineDate;
    }

    public void setBaselineDate(LocalDate baselineDate) {
        this.baselineDate = baselineDate;
    }

    public String getBaselineEquipStatus() {
        return baselineEquipStatus;
    }

    public void setBaselineEquipStatus(String baselineEquipStatus) {
        this.baselineEquipStatus = baselineEquipStatus;
    }

    public BigDecimal getBaselineBalance() {
        return baselineBalance;
    }

    public void setBaselineBalance(BigDecimal baselineBalance) {
        this.baselineBalance = baselineBalance;
    }

    public String getBaselineRuleDigest() {
        return baselineRuleDigest;
    }

    public void setBaselineRuleDigest(String baselineRuleDigest) {
        this.baselineRuleDigest = baselineRuleDigest;
    }

    public String getBaselineSnapshotJson() {
        return baselineSnapshotJson;
    }

    public void setBaselineSnapshotJson(String baselineSnapshotJson) {
        this.baselineSnapshotJson = baselineSnapshotJson;
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

    public String getFinalEquipStatus() {
        return finalEquipStatus;
    }

    public void setFinalEquipStatus(String finalEquipStatus) {
        this.finalEquipStatus = finalEquipStatus;
    }

    public String getRecheckReason() {
        return recheckReason;
    }

    public void setRecheckReason(String recheckReason) {
        this.recheckReason = recheckReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }

    public String getSettledBy() {
        return settledBy;
    }

    public void setSettledBy(String settledBy) {
        this.settledBy = settledBy;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
