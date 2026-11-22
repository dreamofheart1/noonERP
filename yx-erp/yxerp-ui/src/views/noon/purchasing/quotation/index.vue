<template>
  <div class="app-container">
  <el-alert title="全局供应商报价：本页不随顶栏站点切换过滤（报价为全局主数据）；站点级数据权限仍由服务端 SiteAuthGuard 控制。" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-alert
      title="M3 隔离演示数据，成本口径只含采购价不含头程费用；收货不生成库存批次。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="96px">
      <el-form-item label="SKU ID" prop="skuId">
        <el-input-number v-model="queryParams.skuId" :min="1" :precision="0" controls-position="right" style="width:160px" placeholder="请输入SKU ID" />
      </el-form-item>
      <el-form-item label="供应商ID" prop="supplierId">
        <el-input-number v-model="queryParams.supplierId" :min="1" :precision="0" controls-position="right" style="width:160px" placeholder="请输入供应商ID" />
      </el-form-item>
      <el-form-item label="生效起" prop="effectiveFrom">
        <el-date-picker v-model="queryParams.effectiveFrom" type="date" value-format="yyyy-MM-dd" placeholder="生效开始日期" style="width:170px" />
      </el-form-item>
      <el-form-item label="生效止" prop="effectiveTo">
        <el-date-picker v-model="queryParams.effectiveTo" type="date" value-format="yyyy-MM-dd" placeholder="生效结束日期" style="width:170px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:purchasing:quotation:add']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:purchasing:quotation:edit']">修改</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="quotationList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="SKU ID" align="center" prop="skuId" width="100" />
      <el-table-column label="供应商ID" align="center" prop="supplierId" width="100" />
      <el-table-column label="单价" align="center" prop="unitPrice" width="110" />
      <el-table-column label="币种" align="center" prop="currency" width="80" />
      <el-table-column label="MOQ" align="center" prop="moq" width="80" />
      <el-table-column label="交期(天)" align="center" prop="leadTimeDays" width="90" />
      <el-table-column label="生效开始" align="center" prop="effectiveFrom" width="120" />
      <el-table-column label="生效结束" align="center" prop="effectiveTo" width="120" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="100">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:purchasing:quotation:edit']">修改</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="640px" append-to-body>
      <el-alert
        title="历史价：改价请用新的生效开始日期新增一行，旧价快照保持不变。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <el-form ref="quotationRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="SKU ID" prop="skuId">
          <el-input-number v-model="form.skuId" :min="1" :precision="0" controls-position="right" style="width:100%" placeholder="请输入SKU ID" />
        </el-form-item>
        <el-form-item label="供应商ID" prop="supplierId">
          <el-input-number v-model="form.supplierId" :min="1" :precision="0" controls-position="right" style="width:100%" placeholder="请输入供应商ID" />
        </el-form-item>
        <el-form-item label="单价" prop="unitPrice">
          <el-input-number v-model="form.unitPrice" :min="0.0001" :precision="4" :step="0.01" controls-position="right" style="width:100%" placeholder="请输入单价" />
        </el-form-item>
        <el-form-item label="币种" prop="currency">
          <el-select v-model="form.currency" filterable allow-create default-first-option placeholder="请选择或输入币种" style="width:100%">
            <el-option label="CNY" value="CNY" />
            <el-option label="AED" value="AED" />
            <el-option label="SAR" value="SAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="MOQ" prop="moq">
          <el-input-number v-model="form.moq" :min="0" :precision="0" controls-position="right" style="width:100%" placeholder="请输入最小起订量" />
        </el-form-item>
        <el-form-item label="交期(天)" prop="leadTimeDays">
          <el-input-number v-model="form.leadTimeDays" :min="0" :precision="0" controls-position="right" style="width:100%" placeholder="请输入交期天数" />
        </el-form-item>
        <el-form-item label="生效开始" prop="effectiveFrom">
          <el-date-picker v-model="form.effectiveFrom" type="date" value-format="yyyy-MM-dd" placeholder="请选择生效开始日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="生效结束" prop="effectiveTo">
          <el-date-picker v-model="form.effectiveTo" type="date" value-format="yyyy-MM-dd" placeholder="请选择生效结束日期（可留空）" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="QuotationPurchasing">
import { listQuotation, getQuotation, addQuotation, updateQuotation } from "@/api/noon/purchasing/quotation"
const { proxy } = getCurrentInstance()
const quotationList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const total = ref(0)
const title = ref("")
const data = reactive({
  form: {},
  queryParams: { pageNum: 1, limit: 10,
    skuId: null,
    supplierId: null,
    effectiveFrom: null,
    effectiveTo: null,
  },
  rules: {
    skuId: [{ required: true, message: 'SKU ID不能为空', trigger: 'change' }],
    supplierId: [{ required: true, message: '供应商ID不能为空', trigger: 'change' }],
    unitPrice: [{ required: true, message: '单价不能为空', trigger: 'change' }],
    currency: [{ required: true, message: '币种不能为空', trigger: 'change' }],
    moq: [{ required: true, message: 'MOQ不能为空', trigger: 'change' }],
    leadTimeDays: [{ required: true, message: '交期不能为空', trigger: 'change' }],
    effectiveFrom: [{ required: true, message: '生效开始日期不能为空', trigger: 'change' }],
    effectiveTo: [{ validator: checkEffectiveTo, trigger: 'change' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
function checkEffectiveTo(rule, value, callback) {
  if (value && form.value.effectiveFrom && value <= form.value.effectiveFrom) {
    callback(new Error('生效结束日期必须晚于生效开始日期'))
  } else {
    callback()
  }
}
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  listQuotation(params).then(res => {
    const payload = res.data || {}
    quotationList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  form.value = {
    id: null,
    skuId: null,
    supplierId: null,
    unitPrice: null,
    currency: 'CNY',
    moq: 0,
    leadTimeDays: 0,
    effectiveFrom: null,
    effectiveTo: null,
  }
  proxy.resetForm("quotationRef")
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加报价" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getQuotation(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改报价" })
}
function submitForm() {
  proxy.$refs["quotationRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateQuotation(form.value.id, form.value) : addQuotation(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
getList()
</script>
