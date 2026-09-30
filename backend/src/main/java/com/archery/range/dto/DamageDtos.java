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
            @NotNull(message = "请选择生效日期") LocalDate effectiveFrom,
            @NotNull(message = "请填写损伤费用") @DecimalMin(value = "0", message = "损伤费用不能为负") BigDecimal fee,
            @NotNull(message = "请指明是否送修") Boolean sendRepair,
            @NotNull(message = "请指明是否报废封存") Boolean scrap,
            String note,
            @NotBlank(message = "请填写维护人（值班经理）") String operator) {
    }

    public record RuleView(
            Long id,
            String equipType,
            String equipTypeName,
            String severity,
            String severityName,
            LocalDate effectiveFrom,
            BigDecimal fee,
            Boolean sendRepair,
            Boolean scrap,
            String status,
            String statusName,
            String note,
            String createdBy,
            LocalDateTime createdAt,
            /** 当前（查看日）是否为该 类型×严重程度 的生效规则 */
            Boolean effectiveNow) {
    }

    // ---------------- 检查发起 / 提交 ----------------

    public record StartCheckReq(
            @NotNull(message = "请选择要检查的器材") Long equipmentId,
            @NotBlank(message = "请填写器材管理员姓名") String operator) {
    }

    public record DamageItemReq(
            @NotBlank(message = "请填写损伤部位") String part,
            @NotBlank(message = "请选择严重程度") String severity,
            @NotBlank(message = "请填写现场说明") String description,
            @NotNull(message = "请选择责任会员") Long responsibleId) {
    }

    public record SubmitCheckReq(
            List<DamageItemReq> items,
            @NotBlank(message = "请填写器材管理员姓名") String operator) {
    }

    public record ReconfirmReq(
            List<DamageItemReq> items,
            @NotBlank(message = "请填写器材管理员姓名") String operator) {
    }

    public record OperatorReq(@NotBlank(message = "请填写操作人姓名") String operator) {
    }

    /** 值班经理减免单个损伤项 */
    public record WaiveReq(
            @NotNull(message = "请填写减免后金额") @DecimalMin(value = "0", message = "减免后金额不能为负") BigDecimal finalFee,
            @NotBlank(message = "请填写减免理由") String reason,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    public record SettleReq(@NotBlank(message = "请填写结清操作人") String operator) {
    }

    public record CancelReq(@NotBlank(message = "请填写取消操作人") String operator) {
    }

    // ---------------- 补款 / 异议 ----------------

    public record PayReq(
            @NotNull(message = "请填写补款金额") @DecimalMin(value = "0.01", message = "补款金额必须大于 0") BigDecimal amount,
            @NotBlank(message = "请填写补款经手人") String operator) {
    }

    public record DisputeReq(
            @NotBlank(message = "请填写会员姓名") String memberName,
            @NotBlank(message = "请填写异议理由") String reason) {
    }

    /**
     * 异议处理：decision = UPHOLD 维持 / REDUCE 减免（需带 reduceAmount）/ VOID 撤销。
     * 只处理本次待缴费用，不接受也不允许改动原损伤检查。
     */
    public record DisputeDecideReq(
            @NotBlank(message = "请选择处理结论") String decision,
            @DecimalMin(value = "0", message = "减免金额不能为负") BigDecimal reduceAmount,
            @NotBlank(message = "请填写处理意见") String note,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    // ---------------- 出参 ----------------

    public record ItemView(
            Long id,
            Integer itemNo,
            String part,
            String severity,
            String severityName,
            String description,
            Long responsibleId,
            String responsibleName,
            Long ruleId,
            LocalDate snapEffectiveFrom,
            BigDecimal snapFee,
            Boolean snapSendRepair,
            Boolean snapScrap,
            BigDecimal finalFee,
            BigDecimal waivedFee,
            Boolean waived,
            String waiveReason,
            String waivedBy,
            LocalDateTime waivedAt) {
    }

    /** 结清前的费用预览：费用明细 + 会员余额变化 + 器材最终状态 */
    public record FeePreview(
            String status,
            String statusName,
            Boolean recheck,
            String recheckReason,
            BigDecimal totalFee,
            BigDecimal memberBalance,
            BigDecimal balanceDeducted,
            BigDecimal debtAmount,
            Boolean memberRentSuspended,
            String finalEquipStatus,
            String finalEquipStatusName,
            List<ItemView> items) {
    }

    public record DebtView(
            Long id,
            String debtNo,
            Long settlementId,
            String settleNo,
            Long memberId,
            String memberName,
            String equipCode,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal remaining,
            String status,
            String statusName,
            Boolean openDispute,
            LocalDateTime createdAt,
            LocalDateTime paidAt) {
    }

    public record PaymentView(
            Long id,
            Long debtId,
            Long settlementId,
            Long memberId,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            BigDecimal debtRemaining,
            Boolean liftedRentBan,
            String operator,
            LocalDateTime createdAt) {
    }

    public record DisputeView(
            Long id,
            Long debtId,
            Long settlementId,
            Long memberId,
            String reason,
            String status,
            String statusName,
            String decisionNote,
            BigDecimal reduceAmount,
            String decidedBy,
            LocalDateTime createdAt,
            LocalDateTime decidedAt) {
    }

    public record LogView(
            Long id,
            Long debtId,
            String action,
            String actionName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    public record SettlementView(
            Long id,
            String settleNo,
            Long equipmentId,
            String equipCode,
            String equipType,
            String equipTypeName,
            Long renterId,
            String renterName,
            String status,
            String statusName,
            BigDecimal baselineBalance,
            BigDecimal totalFee,
            BigDecimal balanceDeducted,
            BigDecimal debtAmount,
            String finalEquipStatus,
            String finalEquipStatusName,
            String recheckReason,
            String createdBy,
            LocalDateTime createdAt,
            String settledBy,
            LocalDateTime settledAt,
            LocalDateTime cancelledAt,
            List<ItemView> items,
            List<LogView> logs,
            DebtView debt,
            List<PaymentView> payments,
            List<DisputeView> disputes) {
    }

    /** 待检查租借：当前处于租出状态、可由器材管理员发起损伤检查的器材 */
    public record RentedEquipView(
            Long equipmentId,
            String equipCode,
            String equipType,
            String equipTypeName,
            String brand,
            Long renterId,
            String renterName,
            LocalDateTime rentedAt,
            Boolean locked,
            Long activeSettlementId,
            String activeSettlementStatus) {
    }
}
