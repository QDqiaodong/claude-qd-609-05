package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 器材损伤清算模块的入参与出参。
 */
public final class DamageDtos {

    private DamageDtos() {
    }

    // ---------------- 计价规则 ----------------

    public record RuleSaveReq(
            @NotBlank(message = "请选择器材类型") String equipType,
            @NotBlank(message = "请选择严重程度") String severity,
            @NotNull(message = "请选择生效日期") LocalDate effectiveDate,
            @NotNull(message = "请填写费用") @DecimalMin(value = "0", message = "费用不能为负") BigDecimal fee,
            boolean repairFlag,
            boolean scrapFlag,
            String note,
            @NotBlank(message = "请填写维护人") String operator) {
    }

    public record RuleView(
            Long id,
            String equipType,
            String equipTypeName,
            String severity,
            String severityName,
            LocalDate effectiveDate,
            BigDecimal fee,
            boolean repairFlag,
            boolean scrapFlag,
            String status,
            String statusName,
            String note,
            String createdBy,
            LocalDateTime createdAt) {
    }

    // ---------------- 清算单 ----------------

    /** 发起检查：从正在租借的器材发起；责任会员默认租借人，可在登记损伤时逐项指定 */
    public record StartReq(@NotBlank(message = "请填写器材管理员姓名") String inspector) {
    }

    /** 登记损伤项：部位 / 严重程度 / 现场说明 / 责任会员 */
    public record DamageItemReq(
            @NotBlank(message = "请填写损伤部位") String part,
            @NotBlank(message = "请选择严重程度") String severity,
            @NotBlank(message = "请填写现场说明") String description,
            @NotNull(message = "请选择责任会员") Long responsibleMemberId) {
    }

    public record OperatorReq(@NotBlank(message = "请填写操作人") String operator, String note) {
    }

    /** 经理减免单个损伤项 */
    public record WaiveReq(
            @NotNull(message = "请填写减免后金额") @DecimalMin(value = "0", message = "减免后金额不能为负") BigDecimal finalFee,
            @NotBlank(message = "请填写减免理由") String reason,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    /** 会员提出异议 */
    public record DisputeReq(
            @NotBlank(message = "请填写异议理由") String reason,
            @NotBlank(message = "请填写操作人") String operator) {
    }

    /** 异议处理：MAINTAIN 维持 / REDUCE 减免（需 finalFee）/ REVOKE 撤销（finalFee 置 0） */
    public record DisputeHandleReq(
            @NotBlank(message = "请选择处理结论") String decision,
            BigDecimal finalFee,
            @NotBlank(message = "请填写处理说明") String note,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    /** 结清清算单（结清前会先做基线核对与费用预览） */
    public record SettleReq(
            @NotBlank(message = "请填写结清操作人") String operator,
            String note) {
    }

    /** 补缴欠款解限：amount 为空表示一次补足全部剩余 */
    public record RepayReq(
            BigDecimal amount,
            @NotBlank(message = "请填写收款人") String operator) {
    }

    public record DamageItemView(
            Long id,
            Integer itemSeq,
            String part,
            String severity,
            String severityName,
            String description,
            Long responsibleId,
            String responsibleName,
            Long ruleId,
            String snapRuleCode,
            LocalDate snapEffectiveDate,
            BigDecimal snapFee,
            boolean snapRepairFlag,
            boolean snapScrapFlag,
            BigDecimal finalFee,
            boolean waivedFlag,
            String waivedBy,
            LocalDateTime waivedAt,
            String waivedReason,
            String disputeStatus,
            String disputeStatusName,
            String disputeReason,
            LocalDateTime disputedAt,
            String disputeHandledBy,
            LocalDateTime disputeHandledAt,
            String disputeNote,
            LocalDateTime createdAt) {
    }

    public record LogView(
            String action,
            String actionName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 清算单列表 / 详情共用头信息 */
    public record SettlementView(
            Long id,
            String settleNo,
            Long equipmentId,
            String equipCode,
            String equipType,
            String equipTypeName,
            Long memberId,
            String memberName,
            String status,
            String statusName,
            String baselineEquipStatus,
            String baselineEquipStatusName,
            Long baselineRenterId,
            String baselineRenterName,
            LocalDateTime baselineRentedAt,
            BigDecimal baselineBalance,
            String finalDisposal,
            String finalDisposalName,
            BigDecimal totalFee,
            BigDecimal balanceDeducted,
            BigDecimal debtAmount,
            String inspector,
            LocalDateTime createdAt,
            String settledBy,
            LocalDateTime settledAt,
            String settleNote,
            List<DamageItemView> items,
            List<LogView> logs,
            /** 结清后 / 当前会员余额（详情页实时带出） */
            BigDecimal currentBalance,
            /** 基线核对结果（结清预览 / 待核对时填充） */
            List<String> baselineDrifts,
            Boolean baselineStale) {
    }

    /** 结清前费用预览：费用明细、会员余额变化、器材最终状态 */
    public record SettlePreview(
            Long settlementId,
            String settleNo,
            String equipCode,
            String equipTypeName,
            String memberName,
            BigDecimal currentBalance,
            BigDecimal baselineBalance,
            BigDecimal totalFee,
            BigDecimal balanceAfter,
            BigDecimal balanceDeducted,
            BigDecimal debtAmount,
            String finalDisposal,
            String finalDisposalName,
            boolean memberRentalBlockedAfter,
            boolean baselineStale,
            List<String> baselineDrifts,
            List<FeeLine> feeLines) {
    }

    public record FeeLine(
            Integer itemSeq,
            String part,
            String severityName,
            String responsibleName,
            BigDecimal snapFee,
            BigDecimal finalFee,
            boolean repair,
            boolean scrap,
            String adjustmentText) {
    }

    // ---------------- 待缴 / 补款 ----------------

    public record DebtView(
            Long id,
            String debtNo,
            Long settlementId,
            String settleNo,
            Long memberId,
            String memberName,
            BigDecimal originalAmount,
            BigDecimal paidAmount,
            BigDecimal amount,
            String status,
            String statusName,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            List<PaymentView> payments,
            boolean rentalBlocked) {
    }

    public record PaymentView(
            Long id,
            String kind,
            String kindName,
            BigDecimal amount,
            String operator,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 待检查租借：当前租出且不在安全锁定 / 清算中的器材 */
    public record RentedEquipView(
            Long id,
            String equipCode,
            String type,
            String typeName,
            String brand,
            Long renterId,
            String renterName,
            LocalDateTime rentedAt,
            String status,
            String statusName,
            boolean locked,
            boolean settling,
            Long openSettlementId) {
    }
}
