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
import com.archery.range.service.DamageSettlementService;

import jakarta.validation.Valid;

/**
 * 模块十：器材损伤清算。
 *
 * 页面分区：待检查租借（发起检查）/ 清算单（损伤登记、减免、结清）/ 费用明细预览 /
 * 异议处理 / 补缴解限 / 计价规则 / 历史清算查询。
 */
@RestController
@RequestMapping("/api/damage")
public class DamageController {

    private final DamageSettlementService service;

    public DamageController(DamageSettlementService service) {
        this.service = service;
    }

    // ---------------- 选项与规则 ----------------

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("severities", List.of(
                Map.of("code", "LIGHT", "name", RangeDict.damageSeverityName("LIGHT")),
                Map.of("code", "MEDIUM", "name", RangeDict.damageSeverityName("MEDIUM")),
                Map.of("code", "SEVERE", "name", RangeDict.damageSeverityName("SEVERE"))));
        options.put("types", List.of(
                Map.of("code", "RECURVE", "name", RangeDict.equipTypeName("RECURVE")),
                Map.of("code", "COMPOUND", "name", RangeDict.equipTypeName("COMPOUND")),
                Map.of("code", "TRADITIONAL", "name", RangeDict.equipTypeName("TRADITIONAL")),
                Map.of("code", "GEAR", "name", RangeDict.equipTypeName("GEAR")),
                Map.of("code", "ARROW", "name", RangeDict.equipTypeName("ARROW"))));
        options.put("settleStatuses", List.of(
                Map.of("code", "INSPECTING", "name", RangeDict.settleStatusName("INSPECTING")),
                Map.of("code", "PENDING_SETTLE", "name", RangeDict.settleStatusName("PENDING_SETTLE")),
                Map.of("code", "RECHECK", "name", RangeDict.settleStatusName("RECHECK")),
                Map.of("code", "SETTLED", "name", RangeDict.settleStatusName("SETTLED"))));
        options.put("disposals", List.of(
                Map.of("code", "NONE", "name", RangeDict.damageDisposalName("NONE")),
                Map.of("code", "REPAIR", "name", RangeDict.damageDisposalName("REPAIR")),
                Map.of("code", "SCRAP", "name", RangeDict.damageDisposalName("SCRAP"))));
        return ApiResponse.success(options);
    }

    @GetMapping("/rules")
    public ApiResponse<List<DamageDtos.RuleView>> rules() {
        return ApiResponse.success(service.listRules());
    }

    @PostMapping("/rules")
    public ApiResponse<DamageDtos.RuleView> createRule(@Valid @RequestBody DamageDtos.RuleSaveReq req) {
        return ApiResponse.success("计价规则已生效（新登记的损伤按此取价，历史清算单不变）", service.createRule(req));
    }

    // ---------------- 待检查租借 / 发起检查 ----------------

    @GetMapping("/rented")
    public ApiResponse<List<DamageDtos.RentedEquipView>> rented() {
        return ApiResponse.success(service.listRented());
    }

    @PostMapping("/equipment/{equipmentId}/inspection")
    public ApiResponse<DamageDtos.SettlementView> start(@PathVariable Long equipmentId,
            @Valid @RequestBody DamageDtos.StartReq req) {
        return ApiResponse.success("已发起检查，器材进入归还处理中", service.start(equipmentId, req));
    }

    // ---------------- 清算单 ----------------

    @GetMapping("/settlements")
    public ApiResponse<List<DamageDtos.SettlementView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long equipmentId) {
        return ApiResponse.success(service.listSettlements(status, memberId, equipmentId));
    }

    @GetMapping("/settlements/{id}")
    public ApiResponse<DamageDtos.SettlementView> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/settlements/{id}/items")
    public ApiResponse<DamageDtos.SettlementView> addDamage(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.DamageItemReq req) {
        return ApiResponse.success("损伤已登记（计价规则已固化）", service.addDamage(id, req));
    }

    @PostMapping("/settlements/{id}/items/{itemId}/remove")
    public ApiResponse<DamageDtos.SettlementView> removeDamage(@PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody DamageDtos.OperatorReq req) {
        return ApiResponse.success("损伤项已移除", service.removeDamage(id, itemId, req));
    }

    @PostMapping("/settlements/{id}/finish-inspection")
    public ApiResponse<DamageDtos.SettlementView> finishInspection(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.OperatorReq req) {
        return ApiResponse.success("检查已结束，进入待结清", service.finishInspection(id, req));
    }

    @PostMapping("/settlements/{id}/abandon")
    public ApiResponse<Void> abandon(@PathVariable Long id, @Valid @RequestBody DamageDtos.OperatorReq req) {
        service.abandon(id, req);
        return ApiResponse.success("已放弃清算，器材恢复为租出", null);
    }

    // ---------------- 结清前费用明细预览 ----------------

    @GetMapping("/settlements/{id}/preview")
    public ApiResponse<DamageDtos.SettlePreview> preview(@PathVariable Long id) {
        return ApiResponse.success(service.preview(id));
    }

    @PostMapping("/settlements/{id}/rebind")
    public ApiResponse<DamageDtos.SettlementView> rebind(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.OperatorReq req) {
        return ApiResponse.success("重新核对完成，恢复待结清", service.rebind(id, req));
    }

    @PostMapping("/settlements/{id}/settle")
    public ApiResponse<DamageDtos.SettlementView> settle(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.SettleReq req) {
        return ApiResponse.success("清算单已结清", service.settle(id, req));
    }

    // ---------------- 经理减免 ----------------

    @PostMapping("/settlements/{id}/items/{itemId}/waive")
    public ApiResponse<DamageDtos.SettlementView> waive(@PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody DamageDtos.WaiveReq req) {
        return ApiResponse.success("已减免，原检查结果保留", service.waive(id, itemId, req));
    }

    // ---------------- 异议处理 ----------------

    @PostMapping("/settlements/{id}/items/{itemId}/dispute")
    public ApiResponse<DamageDtos.SettlementView> dispute(@PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody DamageDtos.DisputeReq req) {
        return ApiResponse.success("已提出异议，处理期间金额冻结", service.dispute(id, itemId, req));
    }

    @PostMapping("/settlements/{id}/items/{itemId}/dispute-handle")
    public ApiResponse<DamageDtos.SettlementView> handleDispute(@PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody DamageDtos.DisputeHandleReq req) {
        return ApiResponse.success("异议已处理", service.handleDispute(id, itemId, req));
    }

    // ---------------- 待缴费用 / 补缴解限 ----------------

    @GetMapping("/debts")
    public ApiResponse<List<DamageDtos.DebtView>> debts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long memberId) {
        return ApiResponse.success(service.listDebts(status, memberId));
    }

    @GetMapping("/debts/{id}")
    public ApiResponse<DamageDtos.DebtView> debtDetail(@PathVariable Long id) {
        return ApiResponse.success(service.debtView(id));
    }

    @PostMapping("/debts/{id}/repay")
    public ApiResponse<DamageDtos.DebtView> repay(@PathVariable Long id,
            @Valid @RequestBody DamageDtos.RepayReq req) {
        DamageDtos.DebtView view = service.repay(id, req);
        boolean restored = !view.rentalBlocked() && "PAID".equals(view.status());
        return ApiResponse.success(restored ? "已补足全部欠款，租借资格恢复" : "补缴成功，仍有待缴费用", view);
    }
}
