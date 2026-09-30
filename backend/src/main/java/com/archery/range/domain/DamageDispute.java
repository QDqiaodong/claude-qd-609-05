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
 * 待缴费用异议：会员提出后待缴单进入 FROZEN（金额冻结）。
 * 值班经理只能 UPHOLD 维持 / REDUCE 减免 / VOID 撤销本次费用，
 * 不能改动管理员的原损伤检查结果。
 */
@Entity
@Table(name = "damage_dispute")
public class DamageDispute {

    /** 处理中（金额冻结） */
    public static final String STATUS_OPEN = "OPEN";
    /** 维持原费用 */
    public static final String STATUS_UPHOLD = "UPHOLD";
    /** 减免（核减部分待缴金额） */
    public static final String STATUS_REDUCE = "REDUCE";
    /** 撤销本次费用（已缴部分退回余额，待缴清零，解除租借限制） */
    public static final String STATUS_VOID = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "debt_id", nullable = false)
    private Long debtId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "reduce_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal reduceAmount = BigDecimal.ZERO;

    @Column(name = "decided_by", length = 32)
    private String decidedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDebtId() {
        return debtId;
    }

    public void setDebtId(Long debtId) {
        this.debtId = debtId;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public BigDecimal getReduceAmount() {
        return reduceAmount;
    }

    public void setReduceAmount(BigDecimal reduceAmount) {
        this.reduceAmount = reduceAmount;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
