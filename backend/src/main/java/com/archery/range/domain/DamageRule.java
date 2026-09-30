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
 * 损伤计价规则（器材类型 × 严重程度，带生效日期）。
 *
 * 调价方式：同一（类型，严重程度）用新的生效日期再发布一条规则，旧规则保留在库；
 * 检查开始时取「已生效（effective_from <= 检查日）的最新一条 ACTIVE 规则」，
 * 并把规则内容快照进损伤明细（damage_item），此后调价绝不改写历史清算金额。
 */
@Entity
@Table(name = "damage_rule")
public class DamageRule {

    /** 现行 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已停用 */
    public static final String STATUS_RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equip_type", nullable = false, length = 16)
    private String equipType;

    /** LIGHT 轻微 / MEDIUM 较重 / SEVERE 严重报废 */
    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(name = "send_repair", nullable = false)
    private Boolean sendRepair = false;

    @Column(nullable = false)
    private Boolean scrap = false;

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

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public Boolean getSendRepair() {
        return sendRepair;
    }

    public void setSendRepair(Boolean sendRepair) {
        this.sendRepair = sendRepair;
    }

    public Boolean getScrap() {
        return scrap;
    }

    public void setScrap(Boolean scrap) {
        this.scrap = scrap;
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
