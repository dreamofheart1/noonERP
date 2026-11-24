<template>
  <div class="app-container">
    <el-alert type="warning" :closable="false" show-icon>
      期初与联动：生成建议只读取平台快照、不建账；只有你查看后显式确认并填写真实成本才建期初批次。
      成本未知不得填 0，未匹配/重叠项只标注不自动抵扣。送达扣库存需正式订单与系统事件证据；退货缺平台重新入仓且可售证据时保持待核、不加库存。
    </el-alert>

    <el-tabs v-model="active" style="margin-top:12px">
      <el-tab-pane label="期初建议" name="opening">
        <el-form :inline="true">
          <el-form-item label="站点">
            <el-select v-model="form.siteCode" style="width:100px"><el-option label="AE" value="AE" /><el-option label="SA" value="SA" /></el-select>
          </el-form-item>
          <el-form-item label="建议批次号"><el-input v-model="form.proposalNo" placeholder="如 OPEN-20261124-AE" style="width:200px" /></el-form-item>
          <el-form-item label="截止日期"><el-date-picker v-model="form.asOfDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="gen">生成建议(只读)</el-button></el-form-item>
        </el-form>
        <el-table :data="proposals" border v-loading="loading">
          <el-table-column label="Partner SKU" prop="partnerSku" />
          <el-table-column label="可售" prop="qtySaleable" align="center" />
          <el-table-column label="不可售" prop="qtyNonSaleable" align="center" />
          <el-table-column label="快照时间" prop="snapshotAt" width="170" />
          <el-table-column label="重叠/待核项" prop="overlapNote" show-overflow-tooltip />
          <el-table-column label="状态" prop="status" width="100" align="center" />
        </el-table>
        <div style="margin-top:16px;padding:12px;background:#fdf6ec;border-radius:4px">
          <span>确认建账（将按建议批次写入期初批次与 FBN 仓可用库存，重复确认不会重复计入）：</span>
          <el-input-number v-model="unitCost" :min="0.0001" :precision="4" style="width:160px;margin:0 8px" />
          <el-button type="danger" :loading="confirming" @click="confirmOpeningStock"
                     v-hasPermi="['noon:masterdata:sku:save']">确认建账</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="销售送达待核" name="salesLink">
        <el-button type="primary" icon="Refresh" @click="doRefreshLink" style="margin-bottom:10px">重新扫描待核项</el-button>
        <el-table :data="links" border>
          <el-table-column label="item_nr" prop="itemNr" width="200" />
          <el-table-column label="判定" prop="decision" width="170" />
          <el-table-column label="说明" prop="note" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="退货证据" name="returns">
        <el-table :data="returns" border>
          <el-table-column label="item_nr" prop="itemNr" width="180" />
          <el-table-column label="退货来源号" prop="sourceReturnNo" width="180" />
          <el-table-column label="状态" prop="status" width="180" />
          <el-table-column label="证据说明" prop="evidenceNote" show-overflow-tooltip />
          <el-table-column label="同步时间" prop="syncedAt" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="NoonOpening">
import { genOpeningProposal, confirmOpening, refreshSalesLink, listReturnEvidence } from '@/api/noon/sellerlab'
const { proxy } = getCurrentInstance()
const active = ref('opening')
const loading = ref(false)
const confirming = ref(false)
const proposals = ref([])
const links = ref([])
const returns = ref([])
const unitCost = ref(0)
const form = ref({ siteCode: 'AE', proposalNo: '', asOfDate: null })

function gen() {
  if (!form.value.proposalNo) { proxy.$modal.msgWarning('请填写建议批次号'); return }
  loading.value = true
  genOpeningProposal({ ...form.value }).then(res => {
    proposals.value = res.data || []
    loading.value = false
    proxy.$modal.msgSuccess('建议已生成（未建账），请核对重叠项')
  }).catch(() => { loading.value = false })
}
function confirmOpeningStock() {
  if (!form.value.proposalNo || !(unitCost.value > 0)) { proxy.$modal.msgWarning('请填写真实成本（不得为 0）'); return }
  proxy.$modal.confirm('确认按建议批次建期初库存？此操作会写入 ERP 库存账，请确认已核对重叠项与成本。').then(() => {
    confirming.value = true
    confirmOpening({ siteCode: form.value.siteCode, proposalNo: form.value.proposalNo, unitCost: unitCost.value })
      .then(res => { confirming.value = false; proxy.$modal.msgSuccess(`建账 ${res.data || 0} 行`) })
      .catch(() => { confirming.value = false })
  }).catch(() => {})
}
function doRefreshLink() {
  refreshSalesLink(form.value.siteCode).then(res => { links.value = res.data || []; active.value = 'salesLink' })
}
function loadReturns() { listReturnEvidence(form.value.siteCode).then(res => { returns.value = res.data || [] }) }
watch(active, v => { if (v === 'returns') loadReturns() })
</script>
