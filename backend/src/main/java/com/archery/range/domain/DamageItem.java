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
 * 损伤明细项：管理员登记的原始检查结果 + 提交时固化的计价规则快照。
 *
 * 原始检查字段（part / severity / description / responsible* / snap*）一经提交不可改写；
 * 值班经理的减免只动 finalFee 及 waived* 字段，原检查结果与减免前金额始终保留可查。
 */
@Entity
@Table(name = "damage_item")
public class DamageItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "item_no", nullable = false)
    private Integer itemNo;

    @Column(nullable = false, length = 32)
    private String part;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "responsible_id", nullable = false)
    private Long responsibleId;

    @Column(name = "responsible_name", nullable = false, length = 32)
    private String responsibleName;

    @Column(name = "rule_id")
    private Long ruleId;

    // ---------------- 规则快照（提交检查时固化，调价不改写） ----------------

    @Column(name = "snap_equip_type", nullable = false, length = 16)
    private String snapEquipType;

    @Column(name = "snap_severity", nullable = false, length = 16)
    private String snapSeverity;

    @Column(name = "snap_effective_from", nullable = false)
    private LocalDate snapEffectiveFrom;

    @Column(name = "snap_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal snapFee;

    @Column(name = "snap_send_repair", nullable = false)
    private Boolean snapSendRepair = false;

    @Column(name = "snap_scrap", nullable = false)
    private Boolean snapScrap = false;

    // ---------------- 减免（值班经理） ----------------

    @Column(name = "final_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalFee;

    @Column(nullable = false)
    private Boolean waived = false;

    @Column(name = "waived_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal waivedFee = BigDecimal.ZERO;

    @Column(name = "waive_reason", length = 500)
    private String waiveReason;

    @Column(name = "waived_by", length = 32)
    private String waivedBy;

    @Column(name = "waived_at")
    private LocalDateTime waivedAt;

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

    public Integer getItemNo() {
        return itemNo;
    }

    public void setItemNo(Integer itemNo) {
        this.itemNo = itemNo;
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

    public String getSnapEquipType() {
        return snapEquipType;
    }

    public void setSnapEquipType(String snapEquipType) {
        this.snapEquipType = snapEquipType;
    }

    public String getSnapSeverity() {
        return snapSeverity;
    }

    public void setSnapSeverity(String snapSeverity) {
        this.snapSeverity = snapSeverity;
    }

    public LocalDate getSnapEffectiveFrom() {
        return snapEffectiveFrom;
    }

    public void setSnapEffectiveFrom(LocalDate snapEffectiveFrom) {
        this.snapEffectiveFrom = snapEffectiveFrom;
    }

    public BigDecimal getSnapFee() {
        return snapFee;
    }

    public void setSnapFee(BigDecimal snapFee) {
        this.snapFee = snapFee;
    }

    public Boolean getSnapSendRepair() {
        return snapSendRepair;
    }

    public void setSnapSendRepair(Boolean snapSendRepair) {
        this.snapSendRepair = snapSendRepair;
    }

    public Boolean getSnapScrap() {
        return snapScrap;
    }

    public void setSnapScrap(Boolean snapScrap) {
        this.snapScrap = snapScrap;
    }

    public BigDecimal getFinalFee() {
        return finalFee;
    }

    public void setFinalFee(BigDecimal finalFee) {
        this.finalFee = finalFee;
    }

    public Boolean getWaived() {
        return waived;
    }

    public void setWaived(Boolean waived) {
        this.waived = waived;
    }

    public BigDecimal getWaivedFee() {
        return waivedFee;
    }

    public void setWaivedFee(BigDecimal waivedFee) {
        this.waivedFee = waivedFee;
    }

    public String getWaiveReason() {
        return waiveReason;
    }

    public void setWaiveReason(String waiveReason) {
        this.waiveReason = waiveReason;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
