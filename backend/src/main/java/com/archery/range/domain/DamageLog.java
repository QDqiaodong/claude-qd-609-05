package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 损伤清算操作轨迹：发起检查 / 提交检查 / 减免 / 基线变化转核对 / 重新核对 /
 * 结清 / 取消 / 补款解限 / 异议提出与处理，全程留痕。
 */
@Entity
@Table(name = "damage_log")
public class DamageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "debt_id")
    private Long debtId;

    /** CREATE / SUBMIT / WAIVE / RECHECK_FLAG / RECONFIRM / SETTLE / CANCEL / PAY / DISPUTE / DISPUTE_DECIDE */
    @Column(nullable = false, length = 24)
    private String action;

    @Column(nullable = false, length = 32)
    private String operator;

    /** KEEPER 器材管理员 / MANAGER 值班经理 / MEMBER 会员 / SYSTEM 清算系统 */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(length = 500)
    private String detail;

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

    public Long getDebtId() {
        return debtId;
    }

    public void setDebtId(Long debtId) {
        this.debtId = debtId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
