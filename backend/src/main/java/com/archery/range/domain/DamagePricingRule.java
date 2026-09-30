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
 * 损伤计价规则：按器材类型 + 严重程度维护，带生效日期。
 *
 * 登记损伤时取 effectiveDate <= 检查开始时间 的最新 ACTIVE 版本，并把费用 / 是否送修 / 是否报废
 * 固化到损伤项快照（DamageSettlementItem.snap*）。规则随后调价、停用都不改变已登记的历史金额。
 */
@Entity
@Table(name = "damage_pricing_rule")
public class DamagePricingRule {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equip_type", nullable = false, length = 16)
    private String equipType;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(name = "repair_flag", nullable = false)
    private boolean repairFlag;

    @Column(name = "scrap_flag", nullable = false)
    private boolean scrapFlag;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(length = 200)
    private String note;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEquipType() {
        return equipType;
    }

    public void setEquipType(String equipType) {
        this.equipType = equipType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public boolean isRepairFlag() {
        return repairFlag;
    }

    public void setRepairFlag(boolean repairFlag) {
        this.repairFlag = repairFlag;
    }

    public boolean isScrapFlag() {
        return scrapFlag;
    }

    public void setScrapFlag(boolean scrapFlag) {
        this.scrapFlag = scrapFlag;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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
}
