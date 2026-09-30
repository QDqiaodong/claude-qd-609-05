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
 * 损伤待缴费用：结清时会员余额只扣到零，剩余部分落本表。
 *
 * 状态：UNPAID 待缴 → PARTIAL 部分缴纳 → PAID 已缴清；
 * 会员提出异议期间 FROZEN（金额冻结，不能补款）；异议撤销费用后 VOID。
 * 会员名下存在 UNPAID / PARTIAL / FROZEN 待缴单时，租借入口一律暂停。
 */
@Entity
@Table(name = "damage_debt")
public class DamageDebt {

    /** 待缴（未发生补款） */
    public static final String STATUS_UNPAID = "UNPAID";
    /** 部分缴纳 */
    public static final String STATUS_PARTIAL = "PARTIAL";
    /** 异议处理中，金额冻结 */
    public static final String STATUS_FROZEN = "FROZEN";
    /** 已缴清（欠款解限） */
    public static final String STATUS_PAID = "PAID";
    /** 已撤销（异议成立，本次费用撤销） */
    public static final String STATUS_VOID = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "debt_no", nullable = false, unique = true, length = 24)
    private String debtNo;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDebtNo() {
        return debtNo;
    }

    public void setDebtNo(String debtNo) {
        this.debtNo = debtNo;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
