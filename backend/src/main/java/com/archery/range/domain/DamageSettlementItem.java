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
 * 清算单损伤明细项。
 *
 * 管理员原检查结果（部位 / 严重度 / 现场说明 / 责任会员 / 计价规则快照 / snapFee）登记后永不被改写：
 * 经理减免与会员异议只能调整 finalFee 与减免 / 异议列。撤销异议时 finalFee 回 snapFee。
 *
 * disputeStatus：
 * null       未提出异议；
 * DISPUTING  异议处理中，金额冻结（既不能减免也不能结清本单）；
 * MAINTAIN   维持原费用；REDUCE 经理减免；REVOKE 撤销该项费用（finalFee=0）。
 */
@Entity
@Table(name = "damage_settlement_item")
public class DamageSettlementItem {

    public static final String DISPUTE_DISPUTING = "DISPUTING";
    public static final String DISPUTE_MAINTAIN = "MAINTAIN";
    public static final String DISPUTE_REDUCE = "REDUCE";
    public static final String DISPUTE_REVOKE = "REVOKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "item_seq", nullable = false)
    private Integer itemSeq;

    @Column(nullable = false, length = 64)
    private String part;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "responsible_id", nullable = false)
    private Long responsibleId;

    @Column(name = "responsible_name", nullable = false, length = 32)
    private String responsibleName;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "snap_rule_code", nullable = false, length = 40)
    private String snapRuleCode;

    @Column(name = "snap_effective_date", nullable = false)
    private LocalDate snapEffectiveDate;

    @Column(name = "snap_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal snapFee;

    @Column(name = "snap_repair_flag", nullable = false)
    private boolean snapRepairFlag;

    @Column(name = "snap_scrap_flag", nullable = false)
    private boolean snapScrapFlag;

    @Column(name = "final_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalFee;

    @Column(name = "waived_flag", nullable = false)
    private boolean waivedFlag;

    @Column(name = "waived_by", length = 32)
    private String waivedBy;

    @Column(name = "waived_at")
    private LocalDateTime waivedAt;

    @Column(name = "waived_reason", length = 500)
    private String waivedReason;

    @Column(name = "dispute_status", length = 16)
    private String disputeStatus;

    @Column(name = "dispute_reason", length = 500)
    private String disputeReason;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @Column(name = "dispute_handled_by", length = 32)
    private String disputeHandledBy;

    @Column(name = "dispute_handled_at")
    private LocalDateTime disputeHandledAt;

    @Column(name = "dispute_note", length = 500)
    private String disputeNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public Integer getItemSeq() {
        return itemSeq;
    }

    public void setItemSeq(Integer itemSeq) {
        this.itemSeq = itemSeq;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getResponsibleId() {
        return responsibleId;
    }

    public void setResponsibleId(Long responsibleId) {
        this.responsibleId = responsibleId;
    }

    public String getResponsibleName() {
        return responsibleName;
    }

    public void setResponsibleName(String responsibleName) {
        this.responsibleName = responsibleName;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getSnapRuleCode() {
        return snapRuleCode;
    }

    public void setSnapRuleCode(String snapRuleCode) {
        this.snapRuleCode = snapRuleCode;
    }

    public LocalDate getSnapEffectiveDate() {
        return snapEffectiveDate;
    }

    public void setSnapEffectiveDate(LocalDate snapEffectiveDate) {
        this.snapEffectiveDate = snapEffectiveDate;
    }

    public BigDecimal getSnapFee() {
        return snapFee;
    }

    public void setSnapFee(BigDecimal snapFee) {
        this.snapFee = snapFee;
    }

    public boolean isSnapRepairFlag() {
        return snapRepairFlag;
    }

    public void setSnapRepairFlag(boolean snapRepairFlag) {
        this.snapRepairFlag = snapRepairFlag;
    }

    public boolean isSnapScrapFlag() {
        return snapScrapFlag;
    }

    public void setSnapScrapFlag(boolean snapScrapFlag) {
        this.snapScrapFlag = snapScrapFlag;
    }

    public BigDecimal getFinalFee() {
        return finalFee;
    }

    public void setFinalFee(BigDecimal finalFee) {
        this.finalFee = finalFee;
    }

    public boolean isWaivedFlag() {
        return waivedFlag;
    }

    public void setWaivedFlag(boolean waivedFlag) {
        this.waivedFlag = waivedFlag;
    }

    public String getWaivedBy() {
        return waivedBy;
    }

    public void setWaivedBy(String waivedBy) {
        this.waivedBy = waivedBy;
    }

    public LocalDateTime getWaivedAt() {
        return waivedAt;
    }

    public void setWaivedAt(LocalDateTime waivedAt) {
        this.waivedAt = waivedAt;
    }

    public String getWaivedReason() {
        return waivedReason;
    }

    public void setWaivedReason(String waivedReason) {
        this.waivedReason = waivedReason;
    }

    public String getDisputeStatus() {
        return disputeStatus;
    }

    public void setDisputeStatus(String disputeStatus) {
        this.disputeStatus = disputeStatus;
    }

    public String getDisputeReason() {
        return disputeReason;
    }

    public void setDisputeReason(String disputeReason) {
        this.disputeReason = disputeReason;
    }

    public LocalDateTime getDisputedAt() {
        return disputedAt;
    }

    public void setDisputedAt(LocalDateTime disputedAt) {
        this.disputedAt = disputedAt;
    }

    public String getDisputeHandledBy() {
        return disputeHandledBy;
    }

    public void setDisputeHandledBy(String disputeHandledBy) {
        this.disputeHandledBy = disputeHandledBy;
    }

    public LocalDateTime getDisputeHandledAt() {
        return disputeHandledAt;
    }

    public void setDisputeHandledAt(LocalDateTime disputeHandledAt) {
        this.disputeHandledAt = disputeHandledAt;
    }

    public String getDisputeNote() {
        return disputeNote;
    }

    public void setDisputeNote(String disputeNote) {
        this.disputeNote = disputeNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
