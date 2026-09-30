package com.archery.range.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.DamageDtos;
import com.archery.range.service.DamageRuleService;
import com.archery.range.service.DamageSettlementService;

import jakarta.validation.Valid;

/**
 * 模块十：器材损伤清算（计价规则 · 检查登记 · 费用结清 · 待缴补款 · 异议处理 · 历史查询）。
 */
@RestController
@RequestMapping("/api/damage")
public class DamageController {

    private final DamageSettlementService settlementService;
    private final DamageRuleService ruleService;

    public DamageController(DamageSettlementService settlementService, DamageRuleService ruleService) {
        this.settlementService = settlementService;
        this.ruleService = ruleService;
    }

    // ---------------- 选项 ----------------

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("equipTypes", List.of(
                Map.of("code", "RECURVE", "name", RangeDict.equipTypeName("RECURVE")),
                Map.of("code", "COMPOUND", "name", RangeDict.equipTypeName("COMPOUND")),
                Map.of("code", "TRADITIONAL", "name", RangeDict.equipTypeName("TRADITIONAL")),
                Map.of("code", "GEAR", "name", RangeDict.equipTypeName("GEAR")),
                Map.of("code", "ARROW", "name", RangeDict.equipTypeName("ARROW"))));
        options.put("severities", List.of(
                Map.of("code", "LIGHT", "name", RangeDict.damageSeverityName("LIGHT")),
                Map.of("code", "MEDIUM", "name", RangeDict.damageSeverityName("MEDIUM")),
                Map.of("code", "SEVERE", "name", RangeDict.damageSeverityName("SEVERE"))));
        options.put("settleStatuses", List.of(
                Map.of("code", "CHECKING", "name", RangeDict.settleStatusName("CHECKING")),
                Map.of("code", "READY", "name", RangeDict.settleStatusName("READY")),
                Map.of("code", "RECHECK", "name", RangeDict.settleStatusName("RECHECK")),
                Map.of("code", "SETTLED", "name", RangeDict.settleStatusName("SETTLED")),
                Map.of("code", "CANCELLED", "name", RangeDict.settleStatusName("CANCELLED"))));
        options.put("debtStatuses", List.of(
                Map.of("code", "UNPAID", "name", RangeDict.debtStatusName("UNPAID")),
                Map.of("code", "PARTIAL", "name", RangeDict.debtStatusName("PARTIAL")),
                Map.of("code", "FROZEN", "name", RangeDict.debtStatusName("FROZEN")),
                Map.of("code", "PAID", "name", RangeDict.debtStatusName("PAID")),
                Map.of("code", "VOID", "name", RangeDict.debtStatusName("VOID"))));
        return ApiResponse.success(options);
    }

    // ---------------- 计价规则 ----------------

    @GetMapping("/rules")
    public ApiResponse<List<DamageDtos.RuleView>> rules(@RequestParam(required = false) String status) {
        return ApiResponse.success(ruleService.list(status));
    }

    @PostMapping("/rules")
    public ApiResponse<DamageDtos.RuleView> createRule(@Valid @RequestBody DamageDtos.RuleSaveReq req) {
        return ApiResponse.success("计价规则已发布（旧规则保留，历史清算金额不变）", ruleService.create(req));
    }

    @PostMapping("/rules/{id}/retire")
    public ApiResponse<DamageDtos.RuleView> retireRule(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.OperatorReq req) {
        return ApiResponse.success("规则已停用", ruleService.retire(id, req.operator()));
    }

    // ---------------- 待检查租借 / 清算单 ----------------

    @GetMapping("/rented")
    public ApiResponse<List<DamageDtos.RentedEquipView>> rented() {
        return ApiResponse.success(settlementService.rentedList());
    }

    @GetMapping("/settlements")
    public ApiResponse<List<DamageDtos.SettlementView>> list(
            @RequestParam(required = false) String status) {
        return ApiResponse.success(settlementService.list(status));
    }

    @GetMapping("/settlements/{id}")
    public ApiResponse<DamageDtos.SettlementView> detail(@PathVariable Long id) {
        return ApiResponse.success(settlementService.detail(id));
    }

    @PostMapping("/settlements")
    public ApiResponse<DamageDtos.SettlementView> start(@Valid @RequestBody DamageDtos.StartCheckReq req) {
        return ApiResponse.success("检查已开始，器材进入归还处理中", settlementService.startCheck(req));
    }

    @PostMapping("/settlements/{id}/submit")
    public ApiResponse<DamageDtos.SettlementView> submit(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.SubmitCheckReq req) {
        return ApiResponse.success("检查结果已提交", settlementService.submitCheck(id, req));
    }

    @PostMapping("/settlements/{id}/reconfirm")
    public ApiResponse<DamageDtos.SettlementView> reconfirm(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.ReconfirmReq req) {
        return ApiResponse.success("已按当前数据重新核对", settlementService.reconfirm(id, req));
    }

    @PostMapping("/settlements/{id}/preview")
    public ApiResponse<DamageDtos.FeePreview> preview(@PathVariable Long id) {
        return ApiResponse.success(settlementService.preview(id));
    }

    @PostMapping("/settlements/{id}/settle")
    public ApiResponse<DamageDtos.SettlementView> settle(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.SettleReq req) {
        return ApiResponse.success("清算已结清", settlementService.settle(id, req));
    }

    @PostMapping("/settlements/{id}/cancel")
    public ApiResponse<DamageDtos.SettlementView> cancel(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.CancelReq req) {
        return ApiResponse.success("检查已取消，器材恢复租出", settlementService.cancel(id, req));
    }

    // ---------------- 减免 ----------------

    @PostMapping("/settlements/{id}/items/{itemId}/waive")
    public ApiResponse<DamageDtos.SettlementView> waive(@PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody DamageDtos.WaiveReq req) {
        return ApiResponse.success("损伤项已减免（原检查结果保留）", settlementService.waive(id, itemId, req));
    }

    // ---------------- 待缴费用 / 补款 ----------------

    @GetMapping("/debts")
    public ApiResponse<List<DamageDtos.DebtView>> debts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long memberId) {
        return ApiResponse.success(settlementService.debtList(status, memberId));
    }

    @PostMapping("/debts/{id}/pay")
    public ApiResponse<DamageDtos.SettlementView> pay(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.PayReq req) {
        return ApiResponse.success("补款已登记", settlementService.payDebt(id, req));
    }

    // ---------------- 异议 ----------------

    @PostMapping("/debts/{id}/dispute")
    public ApiResponse<DamageDtos.SettlementView> dispute(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.DisputeReq req) {
        return ApiResponse.success("异议已受理，费用冻结等待处理", settlementService.openDispute(id, req));
    }

    @PostMapping("/debts/{id}/dispute-decide")
    public ApiResponse<DamageDtos.SettlementView> disputeDecide(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.DisputeDecideReq req) {
        return ApiResponse.success("异议已处理", settlementService.decideDispute(id, req));
    }
}
