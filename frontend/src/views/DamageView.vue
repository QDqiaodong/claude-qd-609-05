<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">器材损伤清算 <em>检查登记 · 费用结清 · 补款解限 · 异议 · 历史</em></h2>
      <div class="page-tools">
        <el-radio-group v-model="role" size="small">
          <el-radio-button v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="operator" :placeholder="`当前${roleName}姓名`" style="width: 160px" />
        <el-button @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <div class="rule-tip">
      器材管理员从「正在租借」的器材发起检查，逐项登记损伤部位、严重程度、现场说明和责任会员；
      检查开始后器材进入<b>归还处理中</b>，结清前不能再次租借、切换维修状态或清除原租借人，
      处于安全联锁的器材必须先放行。计价按器材类型 × 严重程度取<b>生效日期当天的规则</b>，
      每张清算单固化规则快照，调价不改历史金额。结清时：无损回在库、可修复送维修、报废永久封存；
      余额不足只扣到零，剩余形成待缴费用并暂停租借，补足后自动解限；会员可提出异议，冻结期间不能补款。
    </div>

    <el-tabs v-model="tab" class="damage-tabs">
      <!-- ============ 1. 待检查租借 ============ -->
      <el-tab-pane name="rented">
        <template #label>
          <span>🔍 待检查租借 <em v-if="rented.length" class="tab-cnt">{{ rented.length }}</em></span>
        </template>
        <div class="panel">
          <el-table :data="rented">
            <el-table-column prop="equipCode" label="器材编号" width="120" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ row.equipTypeName }}</template>
            </el-table-column>
            <el-table-column prop="brand" label="品牌" width="110" />
            <el-table-column label="当前状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="rentedStatusType(row)" effect="dark">
                  {{ row.locked ? '安全锁定' : statusName(row.activeSettlementStatus) || '租出' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="租借人" min-width="160">
              <template #default="{ row }">
                <span v-if="row.renterName">{{ row.renterName }} · {{ shortTime(row.rentedAt) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button
                  v-if="!row.locked && !row.activeSettlementId"
                  link type="primary"
                  :disabled="!isKeeper"
                  @click="openStart(row)"
                >发起检查</el-button>
                <el-button
                  v-if="row.activeSettlementId"
                  link type="warning"
                  @click="goSettlement(row.activeSettlementId)"
                >继续清算（{{ statusName(row.activeSettlementStatus) }}）</el-button>
                <span v-if="row.locked" class="c-danger">停射事件未放行，不能检查</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 2. 清算工作台（费用明细） ============ -->
      <el-tab-pane name="work">
        <template #label>
          <span>🧰 清算工作台 <em v-if="openCount" class="tab-cnt">{{ openCount }}</em></span>
        </template>
        <div class="work-layout">
          <div class="panel work-list">
            <div class="panel-title">
              清算单
              <el-select v-model="workFilter" size="small" style="width: 130px; margin-left: 8px" @change="loadSettlements">
                <el-option label="进行中" value="OPEN" />
                <el-option label="检查中" value="CHECKING" />
                <el-option label="待结清" value="READY" />
                <el-option label="待重新核对" value="RECHECK" />
                <el-option label="已结清" value="SETTLED" />
                <el-option label="已取消" value="CANCELLED" />
                <el-option label="全部" value="ALL" />
              </el-select>
            </div>
            <el-table
              :data="settlements"
              height="600"
              highlight-current-row
              :current-row-key="selectedId"
              row-key="id"
              @current-change="onPickSettlement"
            >
              <el-table-column prop="settleNo" label="清算单号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.settleNo }}</span></template>
              </el-table-column>
              <el-table-column prop="equipCode" label="器材" width="100" />
              <el-table-column prop="renterName" label="租借人" width="90" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="settleStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="应收" width="90" align="right">
                <template #default="{ row }">¥{{ money(row.totalFee) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel work-detail" v-if="current">
            <div class="detail-head">
              <div>
                <span class="mono">{{ current.settleNo }}</span>
                <el-tag size="small" :type="settleStatusType(current.status)" effect="dark" style="margin-left: 8px">
                  {{ current.statusName }}
                </el-tag>
              </div>
              <div class="muted">
                {{ current.equipCode }} · {{ current.equipTypeName }} · 租借人 {{ current.renterName }}
                · 发起 {{ shortTime(current.createdAt) }} by {{ current.createdBy }}
              </div>
            </div>

            <el-alert
              v-if="current.status === 'RECHECK'"
              type="warning"
              :closable="false"
              show-icon
              title="基线已变化，待重新核对"
              :description="current.recheckReason"
              style="margin: 10px 0"
            />

            <!-- 检查中：登记损伤项 -->
            <template v-if="current.status === 'CHECKING'">
              <div class="panel-title" style="margin-top: 10px">
                登记损伤检查
                <small>无损归还：不添加任何损伤项，直接提交即可</small>
              </div>
              <el-table :data="draftItems" size="small">
                <el-table-column label="部位" width="130">
                  <template #default="{ row }">
                    <el-input v-model="row.part" placeholder="弓片 / 弓弦 / 箭杆…" />
                  </template>
                </el-table-column>
                <el-table-column label="严重程度" width="150">
                  <template #default="{ row }">
                    <el-select v-model="row.severity" style="width: 100%">
                      <el-option v-for="s in options.severities || []" :key="s.code" :label="s.name" :value="s.code" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="现场说明" min-width="200">
                  <template #default="{ row }">
                    <el-input v-model="row.description" placeholder="如：弓片外侧可见 3cm 裂纹" />
                  </template>
                </el-table-column>
                <el-table-column label="责任会员" width="180">
                  <template #default="{ row }">
                    <el-select v-model="row.responsibleId" filterable style="width: 100%">
                      <el-option
                        v-for="m in members"
                        :key="m.id"
                        :label="`${m.name}（余额 ¥${money(m.balance)}）`"
                        :value="m.id"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column width="60">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="draftItems.splice($index, 1)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="detail-actions">
                <el-button @click="addDraftItem" :disabled="!isKeeper">+ 添加损伤项</el-button>
                <el-button type="success" :disabled="!isKeeper" @click="submitCheck">
                  提交检查（无损，0 项）
                </el-button>
                <el-button type="primary" :disabled="!isKeeper || !draftItems.length" @click="submitCheck">
                  提交检查（{{ draftItems.length }} 项）
                </el-button>
                <el-button type="info" plain :disabled="!isKeeper" @click="cancelSettlement">取消检查（恢复租出）</el-button>
              </div>
            </template>

            <!-- 待重新核对：按当前数据重记 -->
            <template v-if="current.status === 'RECHECK'">
              <div class="panel-title" style="margin-top: 10px">
                重新核对检查结果
                <small>以当前规则与数据重新登记；原检查与减免轨迹保留可查</small>
              </div>
              <el-table :data="draftItems" size="small">
                <el-table-column label="部位" width="130">
                  <template #default="{ row }"><el-input v-model="row.part" /></template>
                </el-table-column>
                <el-table-column label="严重程度" width="150">
                  <template #default="{ row }">
                    <el-select v-model="row.severity" style="width: 100%">
                      <el-option v-for="s in options.severities || []" :key="s.code" :label="s.name" :value="s.code" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="现场说明" min-width="200">
                  <template #default="{ row }"><el-input v-model="row.description" /></template>
                </el-table-column>
                <el-table-column label="责任会员" width="180">
                  <template #default="{ row }">
                    <el-select v-model="row.responsibleId" filterable style="width: 100%">
                      <el-option v-for="m in members" :key="m.id" :label="m.name" :value="m.id" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column width="60">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="draftItems.splice($index, 1)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="detail-actions">
                <el-button @click="addDraftItem" :disabled="!isKeeper">+ 添加损伤项</el-button>
                <el-button type="success" :disabled="!isKeeper" @click="() => reconfirm(true)">按无损重新核对</el-button>
                <el-button type="primary" :disabled="!isKeeper || !draftItems.length" @click="() => reconfirm(false)">
                  重新核对（{{ draftItems.length }} 项）
                </el-button>
              </div>
            </template>

            <!-- 损伤明细（提交后固化 + 减免） -->
            <template v-if="['READY', 'RECHECK', 'SETTLED', 'CANCELLED'].includes(current.status)">
              <div class="panel-title" style="margin-top: 10px">损伤明细与规则快照</div>
              <el-table :data="current.items" size="small" border>
                <el-table-column prop="itemNo" label="#" width="44" />
                <el-table-column prop="part" label="部位" width="100" />
                <el-table-column label="严重程度" width="90">
                  <template #default="{ row }">{{ row.severityName }}</template>
                </el-table-column>
                <el-table-column prop="description" label="现场说明" min-width="160" show-overflow-tooltip />
                <el-table-column prop="responsibleName" label="责任会员" width="90" />
                <el-table-column label="规则快照" width="200">
                  <template #default="{ row }">
                    <div class="snap">
                      <el-tag size="small" :type="row.snapScrap ? 'danger' : row.snapSendRepair ? 'warning' : 'success'" effect="plain">
                        {{ row.snapScrap ? '报废封存' : row.snapSendRepair ? '送修' : '可继续使用' }}
                      </el-tag>
                      <span class="muted">生效 {{ row.snapEffectiveFrom }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="减免前" width="80" align="right">
                  <template #default="{ row }">¥{{ money(row.snapFee) }}</template>
                </el-table-column>
                <el-table-column label="应收" width="80" align="right">
                  <template #default="{ row }">
                    <b :class="{ 'c-danger': row.waived }">¥{{ money(row.finalFee) }}</b>
                  </template>
                </el-table-column>
                <el-table-column label="减免" width="80" align="center">
                  <template #default="{ row }">
                    <el-button
                      v-if="current.status === 'READY'"
                      link type="warning"
                      :disabled="!isManager"
                      @click="openWaive(row)"
                    >减免</el-button>
                    <el-tooltip v-else-if="row.waived" :content="`${row.waivedBy}：${row.waiveReason}`">
                      <el-tag size="small" type="warning">已减 ¥{{ money(row.waivedFee) }}</el-tag>
                    </el-tooltip>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 结清前费用预览 / 结清动作 -->
            <template v-if="current.status === 'READY' || current.status === 'RECHECK'">
              <div class="preview-box">
                <div class="panel-title">结清前费用明细与去向</div>
                <el-descriptions v-if="preview" :column="3" border size="small">
                  <el-descriptions-item label="应收合计">¥{{ money(preview.totalFee) }}</el-descriptions-item>
                  <el-descriptions-item label="会员当前余额">¥{{ money(preview.memberBalance) }}</el-descriptions-item>
                  <el-descriptions-item label="余额扣减">¥{{ money(preview.balanceDeducted) }}</el-descriptions-item>
                  <el-descriptions-item label="形成待缴">
                    <b :class="preview.debtAmount > 0 ? 'c-danger' : ''">¥{{ money(preview.debtAmount) }}</b>
                  </el-descriptions-item>
                  <el-descriptions-item label="器材最终状态">
                    <el-tag size="small" :type="finalStatusType(preview.finalEquipStatus)" effect="dark">
                      {{ preview.finalEquipStatusName }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="租借资格">
                    {{ preview.debtAmount > 0 ? '扣后待缴，暂停租借' : '不受影响' }}
                  </el-descriptions-item>
                </el-descriptions>
                <el-alert
                  v-if="preview && preview.recheck"
                  type="error"
                  :closable="false"
                  show-icon
                  title="基线发生变化，不能沿用旧结果结清"
                  :description="preview.recheckReason"
                  style="margin-top: 8px"
                />
                <div class="detail-actions">
                  <el-button @click="loadPreview">重新计算预览</el-button>
                  <el-button
                    type="primary"
                    :disabled="!isManager || (preview && preview.recheck)"
                    @click="doSettle"
                  >确认结清</el-button>
                </div>
              </div>
            </template>

            <!-- 结清结果 -->
            <template v-if="current.status === 'SETTLED'">
              <div class="result-box">
                <el-tag type="success" effect="dark">已结清 {{ shortTime(current.settledAt) }} · {{ current.settledBy }}</el-tag>
                <span style="margin-left: 10px">
                  应收 ¥{{ money(current.totalFee) }} · 余额扣减 ¥{{ money(current.balanceDeducted) }}
                  · 待缴 ¥{{ money(current.debtAmount) }}
                </span>
                <el-tag
                  v-if="current.finalEquipStatus"
                  size="small"
                  :type="finalStatusType(current.finalEquipStatus)"
                  effect="dark"
                  style="margin-left: 10px"
                >器材去向：{{ current.finalEquipStatusName }}</el-tag>
              </div>
            </template>

            <!-- 待缴 / 补款 / 异议摘要 -->
            <template v-if="current.debt">
              <div class="panel-title" style="margin-top: 12px">待缴费用与处理</div>
              <div class="debt-line">
                <el-tag :type="debtStatusType(current.debt.status)" effect="dark">{{ current.debt.statusName }}</el-tag>
                <span style="margin: 0 8px">
                  待缴本金 ¥{{ money(current.debt.amount) }} · 已缴 ¥{{ money(current.debt.paidAmount) }}
                  · 剩余 <b class="c-danger">¥{{ money(current.debt.remaining) }}</b>
                </span>
                <el-button
                  v-if="['UNPAID', 'PARTIAL'].includes(current.debt.status)"
                  size="small" type="primary"
                  :disabled="!isManager"
                  @click="openPay(current.debt)"
                >补款解限</el-button>
                <el-button
                  v-if="['UNPAID', 'PARTIAL'].includes(current.debt.status)"
                  size="small" type="warning" plain
                  :disabled="!isMember"
                  @click="openDispute(current.debt)"
                >会员提出异议</el-button>
              </div>
              <el-table v-if="current.payments.length" :data="current.payments" size="small" style="margin-top: 8px">
                <el-table-column label="补款时间" width="150">
                  <template #default="{ row }">{{ shortTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column prop="operator" label="经手人" width="90" />
                <el-table-column label="金额" width="90" align="right">
                  <template #default="{ row }">¥{{ money(row.amount) }}</template>
                </el-table-column>
                <el-table-column label="余额变化" min-width="140">
                  <template #default="{ row }">¥{{ money(row.balanceBefore) }} → ¥{{ money(row.balanceAfter) }}</template>
                </el-table-column>
                <el-table-column label="剩余待缴" width="100" align="right">
                  <template #default="{ row }">¥{{ money(row.debtRemaining) }}</template>
                </el-table-column>
                <el-table-column label="解限" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag v-if="row.liftedRentBan" size="small" type="success">恢复租借</el-tag>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 操作轨迹 -->
            <div class="panel-title" style="margin-top: 12px">操作轨迹</div>
            <el-timeline class="log-line">
              <el-timeline-item
                v-for="l in current.logs"
                :key="l.id"
                :timestamp="`${l.createdAt} · ${l.operator}（${l.roleName}）`"
                placement="top"
                size="normal"
              >
                <b>{{ l.actionName }}</b>
                <div class="muted">{{ l.detail }}</div>
              </el-timeline-item>
            </el-timeline>
          </div>

          <div class="panel work-empty" v-else>
            <el-empty description="请从左侧选择清算单，或到「待检查租借」发起检查" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 3. 补款解限 ============ -->
      <el-tab-pane name="pay">
        <template #label>
          <span>💰 补款解限 <em v-if="openDebts.length" class="tab-cnt">{{ openDebts.length }}</em></span>
        </template>
        <div class="panel">
          <el-table :data="debts">
            <el-table-column prop="debtNo" label="待缴单号" width="160">
              <template #default="{ row }"><span class="mono">{{ row.debtNo }}</span></template>
            </el-table-column>
            <el-table-column prop="settleNo" label="清算单" width="150" />
            <el-table-column prop="memberName" label="会员" width="100" />
            <el-table-column prop="equipCode" label="器材" width="100" />
            <el-table-column label="本金" width="90" align="right">
              <template #default="{ row }">¥{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="已缴" width="90" align="right">
              <template #default="{ row }">¥{{ money(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="剩余" width="90" align="right">
              <template #default="{ row }"><b class="c-danger">¥{{ money(row.remaining) }}</b></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="debtStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button
                  v-if="['UNPAID', 'PARTIAL'].includes(row.status)"
                  link type="primary" :disabled="!isManager" @click="openPay(row)"
                >补款</el-button>
                <el-button
                  v-if="['UNPAID', 'PARTIAL'].includes(row.status)"
                  link type="warning" :disabled="!isMember" @click="openDispute(row)"
                >提出异议</el-button>
                <el-button link type="info" @click="goSettlement(row.settlementId)">查看清算单</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 4. 异议处理 ============ -->
      <el-tab-pane name="dispute">
        <template #label>
          <span>⚖️ 异议处理 <em v-if="openDisputes.length" class="tab-cnt c-danger">{{ openDisputes.length }}</em></span>
        </template>
        <div class="panel">
          <el-table :data="disputeDebts" :row-class-name="disputeRowClass">
            <el-table-column prop="debtNo" label="待缴单号" width="160" />
            <el-table-column prop="memberName" label="会员" width="90" />
            <el-table-column label="剩余待缴" width="100" align="right">
              <template #default="{ row }">¥{{ money(row.remaining) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="debtStatusType(row.status)" effect="dark">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="异议理由 / 处理意见" min-width="260">
              <template #default="{ row }">
                <div v-for="d in disputeMap[row.id] || []" :key="d.id" class="dispute-cell">
                  <el-tag size="small" :type="disputeStatusType(d.status)">{{ d.statusName }}</el-tag>
                  <span style="margin: 0 6px">{{ d.reason }}</span>
                  <span v-if="d.decisionNote" class="muted">→ {{ d.decidedBy }}：{{ d.decisionNote }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260">
              <template #default="{ row }">
                <template v-if="row.status === 'FROZEN'">
                  <el-button link type="success" :disabled="!isManager" @click="openDecide(row, 'UPHOLD')">维持</el-button>
                  <el-button link type="warning" :disabled="!isManager" @click="openDecide(row, 'REDUCE')">减免</el-button>
                  <el-button link type="danger" :disabled="!isManager" @click="openDecide(row, 'VOID')">撤销费用</el-button>
                </template>
                <el-button link type="info" @click="goSettlement(row.settlementId)">查看原检查</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="muted" style="margin-top: 8px; font-size: 12px">
            值班经理只能维持、减免或撤销本次待缴费用；管理员的原损伤检查（部位 / 严重程度 / 现场说明 / 责任会员 / 规则快照）不可改动。
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 5. 历史清算 ============ -->
      <el-tab-pane name="history">
        <template #label><span>🗄️ 历史清算查询</span></template>
        <div class="panel">
          <el-table :data="history" @row-click="(row) => goSettlement(row.id)" highlight-current-row>
            <el-table-column prop="settleNo" label="清算单号" width="160" />
            <el-table-column prop="equipCode" label="器材" width="100" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ row.equipTypeName }}</template>
            </el-table-column>
            <el-table-column prop="renterName" label="租借人" width="90" />
            <el-table-column label="应收" width="90" align="right">
              <template #default="{ row }">¥{{ money(row.totalFee) }}</template>
            </el-table-column>
            <el-table-column label="余额扣减" width="100" align="right">
              <template #default="{ row }">¥{{ money(row.balanceDeducted) }}</template>
            </el-table-column>
            <el-table-column label="待缴" width="90" align="right">
              <template #default="{ row }">¥{{ money(row.debtAmount) }}</template>
            </el-table-column>
            <el-table-column label="器材去向" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.finalEquipStatus" size="small" :type="finalStatusType(row.finalEquipStatus)" effect="dark">
                  {{ row.finalEquipStatusName }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="结清时间" width="150">
              <template #default="{ row }">{{ shortTime(row.settledAt) }}</template>
            </el-table-column>
            <el-table-column prop="settledBy" label="结清人" width="90" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ 6. 计价规则 ============ -->
      <el-tab-pane name="rules">
        <template #label><span>📐 计价规则</span></template>
        <div class="panel">
          <div class="panel-title">
            损伤计价规则（按类型 × 严重程度，带生效日期）
            <el-button type="primary" size="small" style="float: right" :disabled="!isManager" @click="ruleDialog = true">
              发布规则（调价用新生效日期）
            </el-button>
          </div>
          <el-table :data="rules">
            <el-table-column label="器材类型" width="100">
              <template #default="{ row }">
                {{ typeName(row.equipType) }}
              </template>
            </el-table-column>
            <el-table-column label="严重程度" width="100">
              <template #default="{ row }">{{ severityName(row.severity) }}</template>
            </el-table-column>
            <el-table-column prop="effectiveFrom" label="生效日期" width="120" />
            <el-table-column label="费用" width="100" align="right">
              <template #default="{ row }">¥{{ money(row.fee) }}</template>
            </el-table-column>
            <el-table-column label="处置" width="160">
              <template #default="{ row }">
                <el-tag size="small" :type="row.snapScrap || row.scrap ? 'danger' : row.sendRepair ? 'warning' : 'success'" effect="plain">
                  {{ row.scrap ? '报废封存' : row.sendRepair ? '送修' : '可继续使用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
                  {{ row.statusName }}<span v-if="row.effectiveNow"> · 当前生效</span>
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  link type="danger" :disabled="!isManager" @click="retireRule(row)"
                >停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 发起检查确认 -->
    <el-dialog v-model="startDialog" title="发起损伤检查" width="420px">
      <div v-if="startRow" class="dlg-tip">
        {{ startRow.equipCode }} · {{ startRow.equipTypeName }} · {{ startRow.brand }}
        <br />租借人：<b>{{ startRow.renterName }}</b>。发起后器材进入「归还处理中」，
        同时固化租借人、器材状态、计价规则与会员余额基线。
      </div>
      <template #footer>
        <el-button @click="startDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!isKeeper" @click="doStart">确认发起</el-button>
      </template>
    </el-dialog>

    <!-- 减免 -->
    <el-dialog v-model="waiveDialog" title="值班经理减免单个损伤项" width="460px">
      <el-form label-width="96px">
        <el-form-item label="损伤项">
          <span>{{ waiveRow?.part }} · {{ severityName(waiveRow?.severity) }}</span>
        </el-form-item>
        <el-form-item label="减免前金额"><b>¥{{ money(waiveRow?.snapFee) }}</b>（原检查结果保留不变）</el-form-item>
        <el-form-item label="减免后金额">
          <el-input-number v-model="waiveForm.finalFee" :min="0" :step="10" :max="Number(waiveRow?.snapFee || 0) - 0.01" />
        </el-form-item>
        <el-form-item label="减免理由">
          <el-input v-model="waiveForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="waiveDialog = false">取消</el-button>
        <el-button type="warning" :disabled="!isManager" @click="doWaive">确认减免</el-button>
      </template>
    </el-dialog>

    <!-- 补款 -->
    <el-dialog v-model="payDialog" title="待缴费用补款" width="420px">
      <el-form label-width="96px">
        <el-form-item label="剩余待缴"><b class="c-danger">¥{{ money(payRow?.remaining) }}</b></el-form-item>
        <el-form-item label="补款金额">
          <el-input-number v-model="payForm.amount" :min="0.01" :step="50" :max="Number(payRow?.remaining || 0)" />
        </el-form-item>
      </el-form>
      <div class="muted" style="font-size: 12px">
        补款进入会员余额后立即核销待缴；最后一笔还清时自动执行欠款解限、恢复租借资格，并保留补款轨迹。
      </div>
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!isManager" @click="doPay">确认补款</el-button>
      </template>
    </el-dialog>

    <!-- 异议 -->
    <el-dialog v-model="disputeDialog" title="会员提出异议（金额冻结）" width="460px">
      <el-form label-width="80px">
        <el-form-item label="会员姓名">
          <el-input v-model="disputeForm.memberName" :placeholder="payRow?.memberName" />
        </el-form-item>
        <el-form-item label="异议理由">
          <el-input v-model="disputeForm.reason" type="textarea" :rows="4" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disputeDialog = false">取消</el-button>
        <el-button type="warning" :disabled="!isMember" @click="doDispute">提交异议</el-button>
      </template>
    </el-dialog>

    <!-- 异议处理 -->
    <el-dialog v-model="decideDialog" :title="`异议处理：${decideLabel}`" width="500px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
        title="只能维持 / 减免 / 撤销本次待缴费用，不能改动原损伤检查。"
      />
      <el-form v-if="payRow" label-width="96px">
        <el-form-item label="剩余待缴">¥{{ money(payRow.remaining) }}</el-form-item>
        <el-form-item v-if="decideForm.decision === 'REDUCE'" label="核减金额">
          <el-input-number v-model="decideForm.reduceAmount" :min="0.01" :step="50"
            :max="Number(payRow.remaining) - 0.01" />
        </el-form-item>
        <el-form-item label="处理意见">
          <el-input v-model="decideForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideDialog = false">取消</el-button>
        <el-button :type="decideForm.decision === 'VOID' ? 'danger' : 'primary'" :disabled="!isManager" @click="doDecide">
          确认{{ decideLabel }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 发布计价规则 -->
    <el-dialog v-model="ruleDialog" title="发布损伤计价规则" width="520px">
      <el-form label-width="96px">
        <el-form-item label="器材类型">
          <el-select v-model="ruleForm.equipType" style="width: 100%">
            <el-option v-for="t in options.equipTypes || []" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="ruleForm.severity">
            <el-radio-button v-for="s in options.severities || []" :key="s.code" :value="s.code">{{ s.name }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="ruleForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="损伤费用">
          <el-input-number v-model="ruleForm.fee" :min="0" :step="20" />
          <span class="muted" style="margin-left: 8px">元</span>
        </el-form-item>
        <el-form-item label="处置方式">
          <el-checkbox v-model="ruleForm.sendRepair">送修</el-checkbox>
          <el-checkbox v-model="ruleForm.scrap">报废封存</el-checkbox>
        </el-form-item>
        <el-form-item label="规则说明">
          <el-input v-model="ruleForm.note" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!isManager" @click="doCreateRule">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { damageApi, memberApi, money, shortTime } from '../api'

const roles = [
  { code: 'KEEPER', name: '器材管理员' },
  { code: 'MANAGER', name: '值班经理' },
  { code: 'MEMBER', name: '会员' }
]
const role = ref('KEEPER')
const operator = ref('老周')
const tab = ref('rented')

const options = ref({})
const members = ref([])
const rented = ref([])
const settlements = ref([])
const selectedId = ref(null)
const current = ref(null)
const preview = ref(null)
const workFilter = ref('OPEN')
const debts = ref([])
const rules = ref([])

const isKeeper = computed(() => role.value === 'KEEPER')
const isManager = computed(() => role.value === 'MANAGER')
const isMember = computed(() => role.value === 'MEMBER')
const roleName = computed(() => (roles.find((r) => r.code === role.value) || {}).name || '')

const draftItems = ref([])

const openCount = computed(() => settlements.value.filter((s) => ['CHECKING', 'READY', 'RECHECK'].includes(s.status)).length)
const openDebts = computed(() => debts.value.filter((d) => ['UNPAID', 'PARTIAL', 'FROZEN'].includes(d.status)))
const openDisputes = computed(() => debts.value.filter((d) => d.status === 'FROZEN' || d.openDispute))
const history = computed(() => settlements.value.filter((s) => s.status === 'SETTLED'))
// 已载入清算单详情中的异议记录（按待缴单 id 分组，覆盖列表占位信息）
const disputeDetailsMap = reactive({})
const disputeMap = computed(() => {
  const map = {}
  for (const d of debts.value) {
    if (d.openDispute || d.status === 'FROZEN') {
      map[d.id] = disputeDetailsMap[d.id] || [
        { id: `${d.id}-open`, status: 'OPEN', statusName: '处理中', reason: '（点击「查看清算单」查看异议详情）', decisionNote: '', decidedBy: '' }
      ]
    }
  }
  return map
})
const disputeDebts = computed(() => {
  const ids = new Set()
  debts.value.forEach((d) => {
    if (d.status === 'FROZEN' || d.openDispute || disputeDetailsMap[d.id]?.length) ids.add(d.id)
  })
  return debts.value.filter((d) => ids.has(d.id))
})

function typeName(code) {
  return (options.value.equipTypes || []).find((t) => t.code === code)?.name || code || ''
}
function severityName(code) {
  return (options.value.severities || []).find((s) => s.code === code)?.name || code || ''
}
function statusName(code) {
  return (options.value.settleStatuses || []).find((s) => s.code === code)?.name || ''
}
function rentedStatusType(row) {
  if (row.locked) return 'danger'
  if (row.activeSettlementStatus === 'RECHECK') return 'warning'
  if (row.activeSettlementStatus) return 'primary'
  return 'success'
}
function settleStatusType(s) {
  if (s === 'SETTLED') return 'success'
  if (s === 'RECHECK') return 'warning'
  if (s === 'CANCELLED') return 'info'
  if (s === 'CHECKING') return 'danger'
  return 'primary'
}
function debtStatusType(s) {
  if (s === 'PAID') return 'success'
  if (s === 'FROZEN') return 'warning'
  if (s === 'VOID') return 'info'
  return 'danger'
}
function disputeStatusType(s) {
  if (s === 'UPHOLD') return 'danger'
  if (s === 'REDUCE') return 'warning'
  if (s === 'VOID') return 'info'
  return 'primary'
}
function finalStatusType(s) {
  if (s === 'SCRAPPED') return 'danger'
  if (s === 'REPAIR') return 'warning'
  return 'success'
}
function disputeRowClass({ row }) {
  return row.status === 'FROZEN' ? 'row-frozen' : ''
}

async function reloadAll() {
  const [opt, ms] = await Promise.all([damageApi.options(), memberApi.options()])
  options.value = opt
  members.value = ms
  await Promise.all([loadRented(), loadSettlements(), loadDebts(), loadRules()])
}

async function loadRented() {
  rented.value = await damageApi.rented()
}
async function loadRules() {
  rules.value = await damageApi.rules()
}
async function loadDebts() {
  debts.value = await damageApi.debts()
  // 异议页需要完整异议记录：后台聚合每笔冻结 / 有异议待缴的清算单详情
  debts.value
    .filter((d) => d.status === 'FROZEN' || d.openDispute)
    .forEach((d) => {
      damageApi.settlement(d.settlementId).then((s) => {
        if (s.disputes?.length) disputeDetailsMap[d.id] = s.disputes
      })
    })
}
async function loadSettlements() {
  const f = workFilter.value
  let list
  if (f === 'ALL') {
    list = await damageApi.settlements()
  } else if (f === 'OPEN') {
    const [checking, ready, recheck] = await Promise.all([
      damageApi.settlements({ status: 'CHECKING' }),
      damageApi.settlements({ status: 'READY' }),
      damageApi.settlements({ status: 'RECHECK' })
    ])
    list = [...checking, ...ready, ...recheck].sort((a, b) => b.id - a.id)
  } else {
    list = await damageApi.settlements({ status: f })
  }
  settlements.value = list
  if (selectedId.value) {
    const hit = list.find((s) => s.id === selectedId.value)
    current.value = hit || null
  }
}
async function onPickSettlement(row) {
  if (!row) return
  selectedId.value = row.id
  await showSettlement(row.id)
}
async function showSettlement(id) {
  current.value = await damageApi.settlement(id)
  if (current.value.debt && current.value.disputes?.length) {
    disputeDetailsMap[current.value.debt.id] = current.value.disputes
  }
  draftItems.value = []
  preview.value = null
  if (current.value.status === 'CHECKING' || current.value.status === 'RECHECK') {
    draftItems.value = current.value.status === 'RECHECK'
      ? current.value.items.map((i) => ({
          part: i.part,
          severity: i.severity,
          description: i.description,
          responsibleId: i.responsibleId
        }))
      : []
  }
  if (current.value.status === 'READY') {
    await loadPreview()
  }
}
async function goSettlement(id) {
  tab.value = 'work'
  selectedId.value = id
  await loadSettlements()
  await showSettlement(id)
}

function addDraftItem() {
  draftItems.value.push({ part: '', severity: 'LIGHT', description: '', responsibleId: members.value[0]?.id })
}

// ---------- 发起检查 ----------
const startDialog = ref(false)
const startRow = ref(null)
function openStart(row) {
  startRow.value = row
  startDialog.value = true
}
async function doStart() {
  const s = await damageApi.startCheck({ equipmentId: startRow.value.equipmentId, operator: operator.value })
  ElMessage.success('检查已开始，器材进入归还处理中')
  startDialog.value = false
  await reloadAll()
  await goSettlement(s.id)
}

function validateDraftItems(items) {
  items.forEach((it, idx) => {
    if (!it.part?.trim()) throw new Error(`第 ${idx + 1} 项：请填写损伤部位`)
    if (!it.description?.trim()) throw new Error(`第 ${idx + 1} 项：请填写现场说明`)
    if (!it.responsibleId) throw new Error(`第 ${idx + 1} 项：请选择责任会员`)
  })
}

async function submitCheck() {
  try {
    validateDraftItems(draftItems.value)
  } catch (e) {
    ElMessage.warning(e.message)
    return
  }
  await damageApi.submitCheck(current.value.id, { items: draftItems.value, operator: operator.value })
  ElMessage.success('检查结果已提交')
  await reloadAll()
  await showSettlement(current.value.id)
}
async function reconfirm(noDamage) {
  const items = noDamage ? [] : draftItems.value
  try {
    validateDraftItems(items)
  } catch (e) {
    ElMessage.warning(e.message)
    return
  }
  await damageApi.reconfirm(current.value.id, { items, operator: operator.value })
  ElMessage.success('已重新核对')
  await reloadAll()
  await showSettlement(current.value.id)
}
async function cancelSettlement() {
  await ElMessageBox.confirm('取消检查后器材恢复租出、原租借人保留，确认？', '取消检查', { type: 'warning' })
  await damageApi.cancel(current.value.id, { operator: operator.value })
  ElMessage.success('检查已取消，器材恢复租出')
  await reloadAll()
  await showSettlement(current.value.id)
}

// ---------- 减免 ----------
const waiveDialog = ref(false)
const waiveRow = ref(null)
const waiveForm = reactive({ finalFee: 0, reason: '' })
function openWaive(row) {
  waiveRow.value = row
  waiveForm.finalFee = Number(row.snapFee)
  waiveForm.reason = ''
  waiveDialog.value = true
}
async function doWaive() {
  if (!waiveForm.reason.trim()) {
    ElMessage.warning('请填写减免理由')
    return
  }
  await damageApi.waive(current.value.id, waiveRow.value.id, {
    finalFee: waiveForm.finalFee,
    reason: waiveForm.reason,
    operator: operator.value
  })
  ElMessage.success('已减免，原检查结果保留')
  waiveDialog.value = false
  await reloadAll()
  await showSettlement(current.value.id)
}

// ---------- 预览 / 结清 ----------
async function loadPreview() {
  preview.value = await damageApi.preview(current.value.id)
}
async function doSettle() {
  await ElMessageBox.confirm(
    `确认结清？应收 ¥${money(preview.value.totalFee)}，余额扣 ¥${money(preview.value.balanceDeducted)}`
      + (preview.value.debtAmount > 0 ? `，待缴 ¥${money(preview.value.debtAmount)} 并暂停租借` : '')
      + `；器材去向：${preview.value.finalEquipStatusName}`,
    '结清清算单',
    { type: 'warning', confirmButtonText: '确认结清' }
  )
  try {
    await damageApi.settle(current.value.id, { operator: operator.value })
    ElMessage.success('清算已结清')
  } catch (e) {
    // 基线变化等错误已由拦截器提示；刷新以展示 RECHECK 状态
  }
  await reloadAll()
  await showSettlement(current.value.id)
}

// ---------- 补款 ----------
const payDialog = ref(false)
const payRow = ref(null)
const payForm = reactive({ amount: 0 })
function openPay(row) {
  payRow.value = row
  payForm.amount = Number(row.remaining)
  payDialog.value = true
}
async function doPay() {
  const s = await damageApi.pay(payRow.value.id, { amount: payForm.amount, operator: operator.value })
  ElMessage.success('补款已登记')
  payDialog.value = false
  await reloadAll()
  if (s.id) await showSettlement(s.id)
}

// ---------- 异议 ----------
const disputeDialog = ref(false)
const disputeForm = reactive({ memberName: '', reason: '' })
function openDispute(row) {
  payRow.value = row
  disputeForm.memberName = row.memberName
  disputeForm.reason = ''
  disputeDialog.value = true
}
async function doDispute() {
  if (!disputeForm.reason.trim()) {
    ElMessage.warning('请填写异议理由')
    return
  }
  await damageApi.dispute(payRow.value.id, disputeForm)
  ElMessage.success('异议已受理，费用冻结')
  disputeDialog.value = false
  await reloadAll()
}

const decideDialog = ref(false)
const decideForm = reactive({ decision: 'UPHOLD', reduceAmount: 0, note: '' })
const decideLabel = computed(() => ({ UPHOLD: '维持', REDUCE: '减免', VOID: '撤销费用' }[decideForm.decision]))
function openDecide(row, decision) {
  payRow.value = row
  decideForm.decision = decision
  decideForm.reduceAmount = Number(row.remaining)
  decideForm.note = ''
  decideDialog.value = true
}
async function doDecide() {
  if (!decideForm.note.trim()) {
    ElMessage.warning('请填写处理意见')
    return
  }
  const payload = {
    decision: decideForm.decision,
    note: decideForm.note,
    operator: operator.value
  }
  if (decideForm.decision === 'REDUCE') payload.reduceAmount = decideForm.reduceAmount
  const s = await damageApi.disputeDecide(payRow.value.id, payload)
  ElMessage.success('异议已处理')
  decideDialog.value = false
  await reloadAll()
  if (s.id) await showSettlement(s.id)
}

// ---------- 规则 ----------
const ruleDialog = ref(false)
const ruleForm = reactive({
  equipType: 'RECURVE',
  severity: 'LIGHT',
  effectiveFrom: new Date().toISOString().slice(0, 10),
  fee: 50,
  sendRepair: false,
  scrap: false,
  note: ''
})
async function doCreateRule() {
  if (ruleForm.sendRepair && ruleForm.scrap) {
    ElMessage.warning('同一条规则不能既送修又报废')
    return
  }
  await damageApi.createRule({ ...ruleForm, operator: operator.value })
  ElMessage.success('规则已发布')
  ruleDialog.value = false
  await loadRules()
}
async function retireRule(row) {
  await ElMessageBox.confirm(`停用 ${typeName(row.equipType)} · ${severityName(row.severity)} 于 ${row.effectiveFrom} 的规则？历史清算快照不受影响。`, '停用规则', { type: 'warning' })
  await damageApi.retireRule(row.id, operator.value)
  ElMessage.success('规则已停用')
  await loadRules()
}

onMounted(reloadAll)
</script>

<style scoped>
.damage-tabs {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 6px 12px 12px;
}

.tab-cnt {
  font-style: normal;
  font-size: 11px;
  background: var(--el-color-danger-light-7);
  color: var(--el-color-danger);
  border-radius: 999px;
  padding: 0 6px;
  margin-left: 4px;
}

.work-layout {
  display: grid;
  grid-template-columns: 460px 1fr;
  gap: 12px;
  align-items: start;
}

.work-detail {
  padding: 12px 14px;
  min-height: 600px;
}

.work-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 600px;
}

.detail-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--line);
}

.detail-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.snap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.preview-box,
.result-box,
.debt-line {
  margin-top: 10px;
  padding: 10px;
  background: #f6f8f8;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.dispute-cell {
  margin-bottom: 4px;
  font-size: 12px;
}

.log-line {
  margin-top: 8px;
  padding: 8px 12px;
  max-height: 260px;
  overflow: auto;
}

:deep(.row-frozen) {
  background: var(--el-color-warning-light-9) !important;
}

.c-danger {
  color: var(--el-color-danger);
}

.dlg-tip {
  padding: 10px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  font-size: 13px;
  line-height: 1.8;
}
</style>
