<template>
  <div class="page damage-page">
    <div class="page-head">
      <h2 class="page-title">器材损伤清算 <em>检查 · 计价快照 · 减免异议 · 结清 · 补缴解限</em></h2>
      <div class="page-tools">
        <el-radio-group v-model="tab" size="small" @change="onTabChange">
          <el-radio-button label="rented">待检查租借</el-radio-button>
          <el-radio-button label="settlements">清算单</el-radio-button>
          <el-radio-button label="debts">待缴 / 补款</el-radio-button>
          <el-radio-button label="rules">计价规则</el-radio-button>
          <el-radio-button label="history">历史清算</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- ================= 待检查租借 ================= -->
    <template v-if="tab === 'rented'">
      <div class="rule-tip">
        器材管理员对<b>正在租借</b>的器材发起检查；发起后器材进入「归还处理中」，结清前不能再次租借、报修或清除租借人。
        处于<b>安全锁定</b>的器材必须先到联锁台放行，不能绕过安全状态。
      </div>
      <div class="panel">
        <el-table :data="rentedList" v-loading="loading">
          <el-table-column prop="equipCode" label="器材编号" width="120" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.typeName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="brand" label="品牌" width="120" />
          <el-table-column prop="renterName" label="租借人" width="110" />
          <el-table-column label="租出时间" width="140">
            <template #default="{ row }">{{ shortTime(row.rentedAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'RENTED'"
                link
                type="primary"
                @click="openStart(row)"
              >发起损伤检查</el-button>
              <el-button v-if="row.settling && row.openSettlementId" link type="warning"
                @click="openSettlement(row.openSettlementId)">继续清算 #{{ row.openSettlementId }}</el-button>
              <span v-if="row.locked" class="muted">安全锁定中，请先在联锁台放行</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ================= 清算单列表 ================= -->
    <template v-if="tab === 'settlements'">
      <div class="panel filter-bar">
        <el-select v-model="filterStatus" clearable placeholder="全部状态" style="width: 150px" @change="loadSettlements">
          <el-option v-for="s in options.settleStatuses || []" :key="s.code" :label="s.name" :value="s.code" />
        </el-select>
        <el-button @click="loadSettlements">刷新</el-button>
      </div>
      <div class="panel">
        <el-table :data="settlementList" v-loading="loading">
          <el-table-column prop="settleNo" label="清算单号" width="160" />
          <el-table-column prop="equipCode" label="器材" width="110" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.equipTypeName }}</template>
          </el-table-column>
          <el-table-column prop="memberName" label="租借人" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="settleStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额 / 去向" min-width="200">
            <template #default="{ row }">
              <span v-if="row.totalFee != null" class="fee-cell">
                应收 ¥{{ money(row.totalFee) }}
                <el-tag size="small" effect="plain" style="margin-left: 6px">{{ row.finalDisposalName }}</el-tag>
              </span>
              <span v-else class="muted">未结清</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSettlement(row.id)">处理 / 查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ================= 待缴 / 补款解限 ================= -->
    <template v-if="tab === 'debts'">
      <div class="rule-tip">
        结清时余额不足，余额扣到零后剩余形成待缴费用，同时<b>暂停该会员租借资格</b>。
        会员补足后执行补缴（可分次），全部缴清自动恢复租借资格并留下补款轨迹。
      </div>
      <div class="panel filter-bar">
        <el-select v-model="debtFilter" style="width: 150px" @change="loadDebts">
          <el-option label="待缴" value="UNPAID" />
          <el-option label="已缴清" value="PAID" />
          <el-option label="已撤销" value="REVOKED" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button @click="loadDebts">刷新</el-button>
      </div>
      <div class="panel">
        <el-table :data="debtList" v-loading="loading">
          <el-table-column prop="debtNo" label="待缴单号" width="160" />
          <el-table-column prop="settleNo" label="来源清算单" width="160" />
          <el-table-column prop="memberName" label="会员" width="110" />
          <el-table-column label="形成金额" width="110">
            <template #default="{ row }">¥{{ money(row.originalAmount) }}</template>
          </el-table-column>
          <el-table-column label="已补缴" width="100">
            <template #default="{ row }">¥{{ money(row.paidAmount) }}</template>
          </el-table-column>
          <el-table-column label="剩余待缴" width="110">
            <template #default="{ row }"><b>¥{{ money(row.amount) }}</b></template>
          </el-table-column>
          <el-table-column label="状态 / 资格" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'UNPAID' ? 'danger' : 'success'" effect="dark">
                {{ row.statusName }}
              </el-tag>
              <div v-if="row.rentalBlocked" class="muted" style="font-size: 11px">租借已暂停</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'UNPAID'" link type="primary" @click="openRepay(row)">补缴解限</el-button>
              <el-button link @click="openDebtTrail(row)">补款轨迹</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ================= 计价规则 ================= -->
    <template v-if="tab === 'rules'">
      <div class="rule-tip">
        按器材类型 × 严重程度维护<b>带生效日期</b>的规则：费用、是否送修、是否报废封存。
        规则只影响新登记的损伤——每张清算单在登记时固化规则快照，后续调价不改变历史金额。
      </div>
      <div class="panel filter-bar">
        <el-button type="primary" @click="openRuleDialog">新增计价规则</el-button>
      </div>
      <div class="panel">
        <el-table :data="ruleList" v-loading="loading">
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ row.equipTypeName }}</template>
          </el-table-column>
          <el-table-column label="严重程度" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="sevType(row.severity)" effect="dark">{{ row.severityName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生效日期" width="120">
            <template #default="{ row }">{{ row.effectiveDate }}</template>
          </el-table-column>
          <el-table-column label="费用" width="110">
            <template #default="{ row }">¥{{ money(row.fee) }}</template>
          </el-table-column>
          <el-table-column label="处置" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.scrapFlag" size="small" type="danger" effect="dark">报废封存</el-tag>
              <el-tag v-else-if="row.repairFlag" size="small" type="warning" effect="dark">送修</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">回库</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="220" />
        </el-table>
      </div>
    </template>

    <!-- ================= 历史清算 ================= -->
    <template v-if="tab === 'history'">
      <div class="panel filter-bar">
        <el-button @click="loadHistory">刷新</el-button>
        <span class="muted">仅展示已结清清算单</span>
      </div>
      <div class="panel">
        <el-table :data="historyList" v-loading="loading">
          <el-table-column prop="settleNo" label="清算单号" width="160" />
          <el-table-column prop="equipCode" label="器材" width="110" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.equipTypeName }}</template>
          </el-table-column>
          <el-table-column prop="memberName" label="责任租借人" width="110" />
          <el-table-column label="应收" width="100">
            <template #default="{ row }">¥{{ money(row.totalFee) }}</template>
          </el-table-column>
          <el-table-column label="余额扣减" width="100">
            <template #default="{ row }">¥{{ money(row.balanceDeducted) }}</template>
          </el-table-column>
          <el-table-column label="待缴形成" width="100">
            <template #default="{ row }">¥{{ money(row.debtAmount) }}</template>
          </el-table-column>
          <el-table-column label="器材去向" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="disposalType(row.finalDisposal)" effect="dark">
                {{ row.finalDisposalName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结清时间" width="160">
            <template #default="{ row }">{{ shortTime(row.settledAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link @click="openSettlement(row.id)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ============ 发起检查对话框 ============ -->
    <el-dialog v-model="startVisible" :title="`发起损伤检查 · ${startRow ? startRow.equipCode : ''}`" width="420px">
      <div v-if="startRow" class="dlg-tip">
        {{ startRow.typeName }} · {{ startRow.brand }}；当前租借人 <b>{{ startRow.renterName }}</b>，
        发起后器材立即进入「归还处理中」。
      </div>
      <el-form label-width="100px">
        <el-form-item label="器材管理员">
          <el-input v-model="inspector" placeholder="请填写器材管理员姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStart">发起检查</el-button>
      </template>
    </el-dialog>

    <!-- ============ 清算单工作台抽屉 ============ -->
    <el-drawer v-model="drawerVisible" size="62%" :title="drawerTitle" destroy-on-close>
      <div v-if="cur" class="settle-desk">
        <!-- 基线条 -->
        <el-alert
          v-if="cur.status !== 'SETTLED'"
          :type="cur.baselineStale ? 'error' : 'success'"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #title>
            基线：租借人 {{ cur.baselineRenterName }} · 器材状态「{{ cur.baselineEquipStatusName }}」
            · 余额基线 ¥{{ money(cur.baselineBalance) }}
            <span v-if="cur.baselineStale">；检测到基线变化：{{ cur.baselineDrifts.join('；') }}</span>
            <span v-else>；当前基线一致</span>
          </template>
        </el-alert>

        <!-- 待重新核对操作 -->
        <div v-if="cur.status === 'RECHECK'" class="recheck-bar">
          <el-alert type="error" :closable="false" show-icon>
            <template #title>
              清算单已转为<b>待重新核对</b>，不能沿用旧结果。请现场确认器材仍处于归还处理中且租借人一致后，
              由值班经理重新核对基线；规则快照与管理员原检查结果不会改变。
            </template>
          </el-alert>
          <div class="bar-actions">
            <el-input v-model="rebindOperator" placeholder="值班经理姓名" style="width: 180px" />
            <el-button type="primary" @click="doRebind">确认重新核对</el-button>
          </div>
        </div>

        <!-- 损伤明细表 -->
        <div class="desk-section">
          <div class="desk-section-head">
            <h4>损伤明细（{{ cur.items.length }} 项）</h4>
            <el-button v-if="canEdit" type="primary" size="small" @click="openItemDialog">登记损伤</el-button>
          </div>
          <el-table :data="cur.items" size="small" border>
            <el-table-column label="#" prop="itemSeq" width="42" />
            <el-table-column label="部位" prop="part" width="110" />
            <el-table-column label="严重度" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="sevType(row.severity)">{{ row.severityName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="现场说明" min-width="160" show-overflow-tooltip />
            <el-table-column prop="responsibleName" label="责任会员" width="90" />
            <el-table-column label="规则快照" width="150">
              <template #default="{ row }">
                <div style="font-size: 11px">
                  {{ row.snapRuleCode }} · {{ row.snapEffectiveDate }}
                  <el-tag v-if="row.snapScrapFlag" size="small" type="danger">报废</el-tag>
                  <el-tag v-else-if="row.snapRepairFlag" size="small" type="warning">送修</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="原价/应收" width="130">
              <template #default="{ row }">
                <span class="muted">¥{{ money(row.snapFee) }}</span>
                → <b>¥{{ money(row.finalFee) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="处理状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.disputeStatus === 'DISPUTING'" type="danger" size="small">异议中·冻结</el-tag>
                <el-tag v-else-if="row.disputeStatus === 'MAINTAIN'" type="info" size="small">异议维持</el-tag>
                <el-tag v-else-if="row.disputeStatus === 'REDUCE'" type="warning" size="small">异议减免</el-tag>
                <el-tag v-else-if="row.disputeStatus === 'REVOKE'" type="success" size="small">已撤销</el-tag>
                <el-tag v-else-if="row.waivedFlag" type="warning" size="small">经理减免</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <template v-if="cur.status !== 'SETTLED'">
                  <el-button v-if="canEdit" link type="danger" @click="removeItem(row)">移除</el-button>
                  <el-button v-if="canWaive && row.disputeStatus !== 'DISPUTING' &&
                    !row.waivedFlag && row.finalFee > 0 && row.disputeStatus !== 'REDUCE' &&
                    row.disputeStatus !== 'REVOKE'" link type="warning" @click="openWaive(row)">经理减免</el-button>
                  <el-button v-if="!canEdit && row.disputeStatus !== 'DISPUTING' &&
                    row.disputeStatus !== 'REVOKE'" link @click="openDispute(row)">提出异议</el-button>
                  <el-button v-if="row.disputeStatus === 'DISPUTING'" link type="primary"
                    @click="openHandleDispute(row)">处理异议</el-button>
                </template>
                <template v-else>
                  <el-button v-if="row.disputeStatus !== 'DISPUTING' && row.disputeStatus !== 'REVOKE'"
                    link @click="openDispute(row)">提出异议</el-button>
                  <el-button v-if="row.disputeStatus === 'DISPUTING'" link type="primary"
                    @click="openHandleDispute(row)">处理异议</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 结清前费用预览 -->
        <div v-if="cur.status !== 'SETTLED'" class="desk-section">
          <div class="desk-section-head"><h4>结清前费用明细预览</h4>
            <el-button size="small" @click="loadPreview">刷新预览</el-button>
          </div>
          <div v-if="preview" class="preview-box">
            <el-table :data="preview.feeLines" size="small" border>
              <el-table-column label="#" prop="itemSeq" width="42" />
              <el-table-column prop="part" label="部位" width="110" />
              <el-table-column prop="severityName" label="严重度" width="80" />
              <el-table-column prop="responsibleName" label="责任会员" width="90" />
              <el-table-column label="原价" width="90">
                <template #default="{ row }">¥{{ money(row.snapFee) }}</template>
              </el-table-column>
              <el-table-column label="应收" width="90">
                <template #default="{ row }"><b>¥{{ money(row.finalFee) }}</b></template>
              </el-table-column>
              <el-table-column label="去向" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.scrap" size="small" type="danger">报废</el-tag>
                  <el-tag v-else-if="row.repair" size="small" type="warning">送修</el-tag>
                  <el-tag v-else size="small" type="success">回库</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="adjustmentText" label="调整说明" min-width="130" />
            </el-table>
            <div class="preview-summary">
              <div><span>当前余额：</span><b>¥{{ money(preview.currentBalance) }}</b>
                <span class="muted">（检查时基线 ¥{{ money(preview.baselineBalance) }}）</span></div>
              <div><span>应收总额：</span><b>¥{{ money(preview.totalFee) }}</b></div>
              <div><span>本次余额扣减：</span><b>¥{{ money(preview.balanceDeducted) }}</b>，
                扣后余额 <b>¥{{ money(preview.balanceAfter) }}</b></div>
              <div>
                <span>待缴费用：</span>
                <b :class="preview.debtAmount > 0 ? 'danger-text' : ''">¥{{ money(preview.debtAmount) }}</b>
                <el-tag v-if="preview.memberRentalBlockedAfter" type="danger" size="small" effect="dark"
                  style="margin-left: 8px">结清后暂停租借</el-tag>
              </div>
              <div><span>器材最终状态：</span>
                <el-tag :type="disposalType(preview.finalDisposal)" effect="dark" size="small">
                  {{ preview.finalDisposalName }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>

        <!-- 已结清结果 -->
        <div v-if="cur.status === 'SETTLED'" class="desk-section">
          <el-alert type="success" :closable="false" show-icon>
            <template #title>
              已结清（{{ cur.settledBy }} · {{ shortTime(cur.settledAt) }}）：应收 ¥{{ money(cur.totalFee) }}，
              余额扣减 ¥{{ money(cur.balanceDeducted) }}，形成待缴 ¥{{ money(cur.debtAmount) }}；
              器材去向「{{ cur.finalDisposalName }}」；会员当前余额 ¥{{ money(cur.currentBalance) }}
            </template>
          </el-alert>
        </div>

        <!-- 操作区 -->
        <div v-if="cur.status !== 'SETTLED'" class="desk-actions">
          <template v-if="cur.status === 'INSPECTING'">
            <el-input v-model="finishOperator" placeholder="器材管理员姓名" style="width: 180px" />
            <el-button @click="doAbandon">放弃清算（恢复租出）</el-button>
            <el-button type="success" @click="doFinish">检查结束 / 无损结清</el-button>
          </template>
          <template v-else-if="cur.status === 'PENDING_SETTLE'">
            <el-input v-model="settleOperator" placeholder="结清操作人" style="width: 180px" />
            <el-button type="primary" :disabled="hasDisputing" @click="doSettle">
              确认结清（扣余额 / 不足挂待缴 / 器材去向落库）
            </el-button>
            <span v-if="hasDisputing" class="muted">存在异议处理中的损伤项，金额冻结，不能结清</span>
          </template>
        </div>

        <!-- 轨迹 -->
        <div class="desk-section">
          <h4>操作轨迹</h4>
          <el-timeline>
            <el-timeline-item v-for="(l, i) in cur.logs" :key="i" :timestamp="fullTime(l.createdAt)"
              :type="l.action === 'SETTLE' ? 'success' : l.action.startsWith('DISPUTE') ? 'warning' : 'primary'">
              <el-tag size="small" effect="plain">{{ l.roleName }} · {{ l.operator }}</el-tag>
              <b>{{ l.actionName }}</b>
              <div class="muted log-detail">{{ l.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>

    <!-- ============ 登记损伤对话框 ============ -->
    <el-dialog v-model="itemVisible" title="登记损伤项" width="480px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="损伤部位">
          <el-input v-model="itemForm.part" placeholder="如 弓片 / 弓弦 / 箭杆 / 护臂" />
        </el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="itemForm.severity">
            <el-radio-button v-for="s in options.severities || []" :key="s.code" :label="s.code">
              {{ s.name }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="现场说明">
          <el-input v-model="itemForm.description" type="textarea" :rows="3"
            placeholder="如 上弓片距握把 5cm 处可见贯穿裂纹" />
        </el-form-item>
        <el-form-item label="责任会员">
          <el-select v-model="itemForm.responsibleMemberId" style="width: 100%">
            <el-option v-for="m in members" :key="m.id"
              :label="`${m.name}（${m.cardNo}）· 余额 ¥${money(m.balance)}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="itemRuleHint" label="适用规则">
          <el-tag size="small" type="info" effect="plain">{{ itemRuleHint }}</el-tag>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemVisible = false">取消</el-button>
        <el-button type="primary" @click="submitItem">登记（固化规则快照）</el-button>
      </template>
    </el-dialog>

    <!-- ============ 经理减免对话框 ============ -->
    <el-dialog v-model="waiveVisible" title="值班经理减免" width="420px" append-to-body>
      <div v-if="waiveRow" class="dlg-tip">
        损伤 #{{ waiveRow.itemSeq }}（{{ waiveRow.part }}），管理员原检查金额
        <b>¥{{ money(waiveRow.snapFee) }}</b> 保留不变；减免后金额必须小于原价。
      </div>
      <el-form label-width="90px">
        <el-form-item label="减免后金额">
          <el-input-number v-model="waiveForm.finalFee" :min="0" :max="waiveRow.snapFee - 0.01" :step="10"
            :precision="2" />
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="waiveForm.reason" type="textarea" :rows="2" placeholder="减免理由必填" />
        </el-form-item>
        <el-form-item label="值班经理">
          <el-input v-model="waiveForm.operator" placeholder="值班经理姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="waiveVisible = false">取消</el-button>
        <el-button type="warning" @click="submitWaive">确认减免</el-button>
      </template>
    </el-dialog>

    <!-- ============ 异议对话框 ============ -->
    <el-dialog v-model="disputeVisible" title="会员提出异议" width="420px" append-to-body>
      <div v-if="activeRow" class="dlg-tip">
        异议处理期间该项金额冻结，值班经理只能维持、减免或撤销本次费用，不能改动管理员原损伤检查。
      </div>
      <el-form label-width="90px">
        <el-form-item label="异议理由">
          <el-input v-model="disputeForm.reason" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="disputeForm.operator" placeholder="会员 / 代办人姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disputeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDispute">提交异议（冻结金额）</el-button>
      </template>
    </el-dialog>

    <!-- ============ 异议处理对话框 ============ -->
    <el-dialog v-model="handleVisible" title="值班经理处理异议" width="460px" append-to-body>
      <div v-if="activeRow" class="dlg-tip">
        损伤 #{{ activeRow.itemSeq }}（{{ activeRow.part }}）：原价 ¥{{ money(activeRow.snapFee) }}，
        当前应收 ¥{{ money(activeRow.finalFee) }}。只能调整费用，不能改动部位 / 严重度 / 说明 / 责任会员。
      </div>
      <el-form label-width="90px">
        <el-form-item label="处理结论">
          <el-radio-group v-model="handleForm.decision">
            <el-radio label="MAINTAIN">维持</el-radio>
            <el-radio label="REDUCE">减免</el-radio>
            <el-radio label="REVOKE">撤销该项</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="handleForm.decision === 'REDUCE'" label="减免后金额">
          <el-input-number v-model="handleForm.finalFee" :min="0"
            :max="Number(activeRow ? activeRow.snapFee : 0) - 0.01" :step="10" :precision="2" />
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="handleForm.note" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="值班经理">
          <el-input v-model="handleForm.operator" />
        </el-form-item>
        <el-form-item v-if="cur && cur.status === 'SETTLED' && handleForm.decision !== 'MAINTAIN'" label="">
          <span class="muted">单已结清：减免 / 撤销差额先核销待缴，剩余退回会员余额。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">提交处理</el-button>
      </template>
    </el-dialog>

    <!-- ============ 补缴对话框 ============ -->
    <el-dialog v-model="repayVisible" title="补缴欠款解限" width="420px" append-to-body>
      <div v-if="repayRow" class="dlg-tip">
        清算单 {{ repayRow.settleNo }} · {{ repayRow.memberName }}：剩余待缴
        <b>¥{{ money(repayRow.amount) }}</b>。留空金额默认一次补足；全部缴清自动恢复租借资格。
      </div>
      <el-form label-width="90px">
        <el-form-item label="补缴金额">
          <el-input-number v-model="repayForm.amount" :min="0.01" :max="Number(repayRow ? repayRow.amount : 0)"
            :step="50" :precision="2" />
          <el-button link @click="repayForm.amount = repayRow ? Number(repayRow.amount) : 0">一次补足</el-button>
        </el-form-item>
        <el-form-item label="收款人">
          <el-input v-model="repayForm.operator" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="repayVisible = false">取消</el-button>
        <el-button type="success" @click="submitRepay">执行补缴</el-button>
      </template>
    </el-dialog>

    <!-- ============ 补款轨迹对话框 ============ -->
    <el-dialog v-model="trailVisible" title="补款 / 撤销轨迹" width="520px" append-to-body>
      <el-timeline v-if="trailDebt">
        <el-timeline-item v-for="p in trailDebt.payments" :key="p.id" :timestamp="fullTime(p.createdAt)">
          <el-tag size="small" :type="p.kind === 'REPAY' ? 'success' : 'warning'">{{ p.kindName }}</el-tag>
          <b>¥{{ money(p.amount) }}</b> · {{ p.operator }}
          <div class="muted">{{ p.detail }}</div>
        </el-timeline-item>
        <div v-if="!trailDebt.payments.length" class="muted">暂无补款记录</div>
      </el-timeline>
    </el-dialog>

    <!-- ============ 新增计价规则对话框 ============ -->
    <el-dialog v-model="ruleVisible" title="新增计价规则（带生效日期）" width="480px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="器材类型">
          <el-select v-model="ruleForm.equipType" style="width: 100%">
            <el-option v-for="t in options.types || []" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="ruleForm.severity">
            <el-radio-button v-for="s in options.severities || []" :key="s.code" :label="s.code">
              {{ s.name }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="ruleForm.effectiveDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择生效日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="费用">
          <el-input-number v-model="ruleForm.fee" :min="0" :step="20" :precision="2" />
        </el-form-item>
        <el-form-item label="处置">
          <el-checkbox v-model="ruleForm.repairFlag">送修</el-checkbox>
          <el-checkbox v-model="ruleForm.scrapFlag">报废封存</el-checkbox>
          <span class="muted">（都不勾选 = 结清后回库）</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="ruleForm.note" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="维护人">
          <el-input v-model="ruleForm.operator" placeholder="值班经理姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRule">发布规则</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { damageApi, memberApi, money, shortTime } from '../api'

const route = useRoute()
const router = useRouter()

const tab = ref('rented')
const loading = ref(false)
const options = ref({})
const members = ref([])

const rentedList = ref([])
const settlementList = ref([])
const historyList = ref([])
const debtList = ref([])
const ruleList = ref([])
const filterStatus = ref('')
const debtFilter = ref('UNPAID')

// 发起检查
const startVisible = ref(false)
const startRow = ref(null)
const inspector = ref('')

// 清算工作台
const drawerVisible = ref(false)
const cur = ref(null)
const preview = ref(null)
const finishOperator = ref('')
const settleOperator = ref('')
const rebindOperator = ref('')

// 登记损伤
const itemVisible = ref(false)
const itemForm = reactive({ part: '', severity: 'LIGHT', description: '', responsibleMemberId: null })

// 减免
const waiveVisible = ref(false)
const waiveRow = ref(null)
const waiveForm = reactive({ finalFee: 0, reason: '', operator: '' })

// 异议
const disputeVisible = ref(false)
const handleVisible = ref(false)
const activeRow = ref(null)
const disputeForm = reactive({ reason: '', operator: '' })
const handleForm = reactive({ decision: 'MAINTAIN', finalFee: 0, note: '', operator: '' })

// 补缴
const repayVisible = ref(false)
const repayRow = ref(null)
const repayForm = reactive({ amount: 0, operator: '' })

// 轨迹
const trailVisible = ref(false)
const trailDebt = ref(null)

// 规则
const ruleVisible = ref(false)
const ruleForm = reactive({
  equipType: 'RECURVE', severity: 'LIGHT', effectiveDate: null,
  fee: 100, repairFlag: false, scrapFlag: false, note: '', operator: ''
})

const drawerTitle = computed(() => {
  if (!cur.value) return '清算工作台'
  return `清算单 ${cur.value.settleNo} · ${cur.value.equipCode} · ${cur.value.statusName}`
})

const canEdit = computed(() => cur.value && cur.value.status === 'INSPECTING')
const canWaive = computed(() => cur.value &&
  (cur.value.status === 'PENDING_SETTLE' || cur.value.status === 'RECHECK'))
const hasDisputing = computed(() => cur.value &&
  cur.value.items.some((i) => i.disputeStatus === 'DISPUTING'))

// 登记损伤时实时提示将适用的现行规则（费用 / 送修 / 报废），登记后即固化
const itemRuleHint = computed(() => {
  if (!cur.value) return ''
  const rule = (ruleList.value || []).find((r) =>
    r.equipType === cur.value.equipType && r.severity === itemForm.severity && r.status === 'ACTIVE')
  if (!rule) return '该类型 + 严重度暂无生效规则，登记会被拒绝，请先维护计价规则'
  return `${rule.equipTypeName} · ${rule.severityName}：¥${money(rule.fee)}` +
    `${rule.scrapFlag ? ' · 报废封存' : rule.repairFlag ? ' · 送修' : ' · 回库'}（${rule.effectiveDate} 生效，登记即固化）`
})

function statusType(status) {
  if (status === 'INSTOCK') return 'success'
  if (status === 'RENTED') return 'primary'
  if (status === 'LOCKED') return 'danger'
  if (status === 'SETTLING') return 'warning'
  if (status === 'SCRAPPED') return 'info'
  return 'info'
}

function settleStatusType(status) {
  if (status === 'SETTLED') return 'success'
  if (status === 'RECHECK') return 'danger'
  if (status === 'INSPECTING') return 'warning'
  return 'primary'
}

function sevType(sev) {
  if (sev === 'SEVERE') return 'danger'
  if (sev === 'MEDIUM') return 'warning'
  return 'success'
}

function disposalType(d) {
  if (d === 'SCRAP') return 'danger'
  if (d === 'REPAIR') return 'warning'
  return 'success'
}

function fullTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : ''
}

async function loadRented() {
  loading.value = true
  try {
    rentedList.value = await damageApi.rented()
  } finally {
    loading.value = false
  }
}

async function loadSettlements() {
  loading.value = true
  try {
    settlementList.value = await damageApi.list(filterStatus.value ? { status: filterStatus.value } : {})
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  loading.value = true
  try {
    historyList.value = await damageApi.list({ status: 'SETTLED' })
  } finally {
    loading.value = false
  }
}

async function loadDebts() {
  loading.value = true
  try {
    debtList.value = await damageApi.debts(debtFilter.value ? { status: debtFilter.value } : {})
  } finally {
    loading.value = false
  }
}

async function loadRules() {
  loading.value = true
  try {
    ruleList.value = await damageApi.rules()
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  members.value = await memberApi.options()
}

function onTabChange() {
  if (tab.value === 'rented') loadRented()
  if (tab.value === 'settlements') loadSettlements()
  if (tab.value === 'debts') loadDebts()
  if (tab.value === 'rules') loadRules()
  if (tab.value === 'history') loadHistory()
}

// ---------- 发起检查 ----------
function openStart(row) {
  startRow.value = row
  inspector.value = ''
  startVisible.value = true
}

async function submitStart() {
  if (!inspector.value.trim()) {
    ElMessage.warning('请填写器材管理员姓名')
    return
  }
  const view = await damageApi.start(startRow.value.id, inspector.value.trim())
  ElMessage.success('已发起检查，器材进入归还处理中')
  startVisible.value = false
  await loadRented()
  await openSettlement(view.id)
}

// ---------- 工作台 ----------
async function refreshCur(id) {
  cur.value = await damageApi.detail(id)
  if (cur.value.status !== 'SETTLED') {
    try {
      preview.value = await damageApi.preview(id)
    } catch (e) {
      preview.value = null
    }
  } else {
    preview.value = null
  }
}

async function openSettlement(id) {
  tab.value = 'settlements'
  await refreshCur(id)
  drawerVisible.value = true
}

function openItemDialog() {
  Object.assign(itemForm, {
    part: '', severity: 'LIGHT', description: '',
    responsibleMemberId: cur.value ? cur.value.memberId : null
  })
  itemVisible.value = true
}

async function submitItem() {
  if (!itemForm.part.trim() || !itemForm.description.trim()) {
    ElMessage.warning('请填写损伤部位与现场说明')
    return
  }
  if (!itemForm.responsibleMemberId) {
    ElMessage.warning('请选择责任会员')
    return
  }
  await damageApi.addDamage(cur.value.id, { ...itemForm })
  ElMessage.success('损伤已登记，计价规则快照已固化')
  itemVisible.value = false
  await refreshCur(cur.value.id)
}

async function removeItem(row) {
  try {
    const { value } = await ElMessageBox.prompt(`移除损伤 #${row.itemSeq} 的原因`, '移除损伤项', {
      confirmButtonText: '移除', cancelButtonText: '取消', inputPlaceholder: '请填写移除原因'
    })
    await damageApi.removeDamage(cur.value.id, row.id, { operator: inspector.value || '器材管理员', note: value })
    ElMessage.success('已移除')
    await refreshCur(cur.value.id)
  } catch (e) { /* cancel */ }
}

async function doFinish() {
  if (!finishOperator.value.trim()) {
    ElMessage.warning('请填写器材管理员姓名')
    return
  }
  await damageApi.finishInspection(cur.value.id, finishOperator.value.trim())
  ElMessage.success('检查结束，进入待结清')
  await refreshCur(cur.value.id)
}

async function doAbandon() {
  try {
    await ElMessageBox.confirm('放弃后器材恢复为租出、清算单删除，确定吗？', '放弃清算', { type: 'warning' })
    await damageApi.abandon(cur.value.id, finishOperator.value.trim() || '器材管理员')
    ElMessage.success('已放弃，器材恢复租出')
    drawerVisible.value = false
    await loadRented()
    await loadSettlements()
  } catch (e) { /* cancel */ }
}

async function doRebind() {
  if (!rebindOperator.value.trim()) {
    ElMessage.warning('请填写值班经理姓名')
    return
  }
  await damageApi.rebind(cur.value.id, rebindOperator.value.trim())
  ElMessage.success('重新核对完成，恢复待结清')
  await refreshCur(cur.value.id)
}

async function doSettle() {
  if (!settleOperator.value.trim()) {
    ElMessage.warning('请填写结清操作人')
    return
  }
  if (preview.value && preview.value.baselineStale) {
    ElMessage.error('基线已变化，请先完成重新核对')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将一次性完成：扣减余额 ¥${preview.value ? money(preview.value.balanceDeducted) : '0.00'}、` +
      `形成待缴 ¥${preview.value ? money(preview.value.debtAmount) : '0.00'}、` +
      `器材转为「${preview.value ? preview.value.finalDisposalName : ''}」。确认结清？`,
      '确认结清', { confirmButtonText: '确认结清', cancelButtonText: '取消', type: 'warning' })
  } catch (e) { return }
  await damageApi.settle(cur.value.id, { operator: settleOperator.value.trim(), note: '' })
  ElMessage.success('清算单已结清')
  await refreshCur(cur.value.id)
  await loadSettlements()
  await loadRented()
}

// ---------- 减免 ----------
function openWaive(row) {
  waiveRow.value = row
  Object.assign(waiveForm, { finalFee: Number(row.finalFee), reason: '', operator: '' })
  waiveVisible.value = true
}

async function submitWaive() {
  if (!waiveForm.reason.trim()) {
    ElMessage.warning('请填写减免理由')
    return
  }
  if (waiveForm.finalFee >= Number(waiveRow.value.snapFee)) {
    ElMessage.warning('减免后金额必须小于原检查金额')
    return
  }
  await damageApi.waive(cur.value.id, waiveRow.value.id, { ...waiveForm })
  ElMessage.success('已减免，原检查结果保留')
  waiveVisible.value = false
  await refreshCur(cur.value.id)
}

// ---------- 异议 ----------
function openDispute(row) {
  activeRow.value = row
  Object.assign(disputeForm, { reason: '', operator: '' })
  disputeVisible.value = true
}

async function submitDispute() {
  if (!disputeForm.reason.trim()) {
    ElMessage.warning('请填写异议理由')
    return
  }
  await damageApi.dispute(cur.value.id, activeRow.value.id, { ...disputeForm })
  ElMessage.success('已提出异议，金额冻结')
  disputeVisible.value = false
  await refreshCur(cur.value.id)
}

function openHandleDispute(row) {
  activeRow.value = row
  Object.assign(handleForm, {
    decision: 'MAINTAIN', finalFee: Number(row.finalFee), note: '', operator: ''
  })
  handleVisible.value = true
}

async function submitHandle() {
  if (!handleForm.note.trim()) {
    ElMessage.warning('请填写处理说明')
    return
  }
  const payload = {
    decision: handleForm.decision,
    finalFee: handleForm.decision === 'REDUCE' ? handleForm.finalFee : null,
    note: handleForm.note,
    operator: handleForm.operator
  }
  await damageApi.handleDispute(cur.value.id, activeRow.value.id, payload)
  ElMessage.success('异议已处理')
  handleVisible.value = false
  await refreshCur(cur.value.id)
  await loadDebts()
}

// ---------- 补缴 ----------
function openRepay(row) {
  repayRow.value = row
  Object.assign(repayForm, { amount: Number(row.amount), operator: '' })
  repayVisible.value = true
}

async function submitRepay() {
  await damageApi.repay(repayRow.value.id, {
    amount: repayForm.amount, operator: repayForm.operator.trim()
  })
  ElMessage.success('补缴完成')
  repayVisible.value = false
  await loadDebts()
}

async function openDebtTrail(row) {
  trailDebt.value = await damageApi.debtDetail(row.id)
  trailVisible.value = true
}

// ---------- 规则 ----------
function openRuleDialog() {
  Object.assign(ruleForm, {
    equipType: 'RECURVE', severity: 'LIGHT', effectiveDate: new Date().toISOString().slice(0, 10),
    fee: 100, repairFlag: false, scrapFlag: false, note: '', operator: ''
  })
  ruleVisible.value = true
}

async function submitRule() {
  if (!ruleForm.effectiveDate || !ruleForm.operator.trim()) {
    ElMessage.warning('请填写生效日期与维护人')
    return
  }
  if (ruleForm.repairFlag && ruleForm.scrapFlag) {
    ElMessage.warning('不能既送修又报废')
    return
  }
  await damageApi.createRule({ ...ruleForm })
  ElMessage.success('计价规则已发布，仅影响之后登记的损伤')
  ruleVisible.value = false
  await loadRules()
}

onMounted(async () => {
  options.value = await damageApi.options()
  await Promise.all([loadMembers(), loadRules()])
  const equipId = route.query.equip
  if (equipId) {
    tab.value = 'rented'
    await loadRented()
    router.replace({ path: '/damage' })
    return
  }
  await loadRented()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 10px;
}

.fee-cell {
  white-space: nowrap;
}

.danger-text {
  color: var(--el-color-danger);
}

.dlg-tip {
  margin-bottom: 12px;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
  color: #04463c;
}

.settle-desk {
  padding: 0 4px;
}

.desk-section {
  margin-bottom: 18px;
}

.desk-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.desk-section-head h4 {
  margin: 0;
}

.recheck-bar {
  margin-bottom: 12px;
}

.bar-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.preview-box {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px;
  background: #fafcfc;
}

.preview-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 16px;
  margin-top: 10px;
  font-size: 13px;
}

.desk-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px;
  border-top: 1px dashed var(--line);
  margin-bottom: 16px;
}

.log-detail {
  font-size: 12px;
  margin-top: 2px;
}
</style>
