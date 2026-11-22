<template>
  <div class="app-container">
  <el-alert :title="`当前站点：${siteLabel} · 仓库绑定站点与履约模式；创建后站点/履约模式不可变`" type="info" :closable="false" show-icon style="margin-bottom: 12px" />

    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="110px">
        <el-form-item label="仓库编码" prop="warehouseCode">
          <el-input v-model="queryParams.warehouseCode" placeholder="请输入仓库编码" clearable style="width:200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="站点">
          <el-tag type="info" effect="plain">{{ siteLabel }}（顶栏切换）</el-tag>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:200px">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['noon:masterdata:warehouse:save']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['noon:masterdata:warehouse:save']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Close" :disabled="single" @click="handleDisable" v-hasPermi="['noon:masterdata:warehouse:status']">停用</el-button></el-col>
      <el-col :span="1.5"><el-button type="primary" plain icon="Check" :disabled="single" @click="handleEnable" v-hasPermi="['noon:masterdata:warehouse:status']">启用</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="warehouseList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="80" />
      <el-table-column label="仓库编码" align="center" prop="warehouseCode" show-overflow-tooltip />
      <el-table-column label="站点" align="center" prop="siteCode">
        <template #default="scope">{{ { AE:'AE', SA:'SA' }[scope.row.siteCode] || scope.row.siteCode }}</template>
      </el-table-column>
      <el-table-column label="仓库名称" align="center" prop="warehouseName" show-overflow-tooltip />
      <el-table-column label="仓库类型" align="center" prop="warehouseType">
        <template #default="scope">{{ { LOCAL:'本地仓', TRANSIT:'中转仓', FBN:'FBN', '3PL':'三方仓' }[scope.row.warehouseType] || scope.row.warehouseType }}</template>
      </el-table-column>
      <el-table-column label="履约模式" align="center" prop="fulfillmentMode">
        <template #default="scope">{{ { FBN:'FBN', GLOBAL:'GLOBAL', FBP:'FBP' }[scope.row.fulfillmentMode] || scope.row.fulfillmentMode }}</template>
      </el-table-column>
      <el-table-column label="是否FBN" align="center" prop="isFbn">
        <template #default="scope">{{ { true:'是', false:'否' }[scope.row.isFbn] || scope.row.isFbn }}</template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="180">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['noon:masterdata:warehouse:save']">修改</el-button>
          <el-button link type="warning" icon="Close" @click="handleStatus(scope.row, 'INACTIVE')" v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['noon:masterdata:warehouse:status']">停用</el-button>
          <el-button link type="primary" icon="Check" @click="handleStatus(scope.row, 'ACTIVE')" v-if="scope.row.status === 'INACTIVE'" v-hasPermi="['noon:masterdata:warehouse:status']">启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.limit" @update:limit="queryParams.pageNum = 1" @pagination="getList" />
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form ref="warehouseRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="仓库编码" prop="warehouseCode">
        <el-input v-model="form.warehouseCode" placeholder="请输入仓库编码" />
      </el-form-item>
      <el-form-item label="站点" prop="siteCode">
        <el-select v-model="form.siteCode" placeholder="请选择授权站点" style="width:100%">
          <el-option v-for="s in siteOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="仓库名称" prop="warehouseName">
        <el-input v-model="form.warehouseName" placeholder="请输入仓库名称" />
      </el-form-item>
      <el-form-item label="仓库类型" prop="warehouseType">
        <el-select v-model="form.warehouseType" placeholder="请选择仓库类型" style="width:100%">
          <el-option label="本地仓" value="LOCAL" />
          <el-option label="中转仓" value="TRANSIT" />
          <el-option label="FBN" value="FBN" />
          <el-option label="三方仓" value="3PL" />
        </el-select>
      </el-form-item>
      <el-form-item label="履约模式" prop="fulfillmentMode">
        <el-select v-model="form.fulfillmentMode" placeholder="请选择履约模式" style="width:100%">
          <el-option label="FBN" value="FBN" />
          <el-option label="GLOBAL" value="GLOBAL" />
          <el-option label="FBP" value="FBP" />
        </el-select>
      </el-form-item>
      <el-form-item label="是否FBN" prop="isFbn">
        <el-select v-model="form.isFbn" placeholder="请选择是否FBN" style="width:100%">
          <el-option label="是" value="true" />
          <el-option label="否" value="false" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="请选择状态" style="width:100%">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
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

<script setup name="Warehouse">
import { useSiteScope } from "@/composables/useSiteScope"
import { listWarehouse, getWarehouse, addWarehouse, updateWarehouse, changeWarehouseStatus } from "@/api/noon/masterdata/warehouse"
const { proxy } = getCurrentInstance()
const warehouseList = ref([])
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
    warehouseCode: null,
    siteCode: null,
    status: null,
  },
  rules: {
    warehouseCode: [{ required: true, message: '仓库编码不能为空', trigger: 'blur' }],
    siteCode: [{ required: true, message: '站点不能为空', trigger: 'blur' }],
    warehouseType: [{ required: true, message: '仓库类型不能为空', trigger: 'blur' }],
    fulfillmentMode: [{ required: true, message: '履约模式不能为空', trigger: 'blur' }],
    status: [{ required: true, message: '状态不能为空', trigger: 'blur' }],
  }
})
const { queryParams, form, rules } = toRefs(data)
const { siteFilter, siteLabel, siteOptions } = useSiteScope(() => {
  queryParams.value.siteCode = siteFilter.value || null
  queryParams.value.pageNum = 1
  getList()
})
queryParams.value.siteCode = siteFilter.value || null
function getList() {
  loading.value = true
  const { pageNum, ...rest } = queryParams.value
  const params = { ...rest, offset: (pageNum - 1) * queryParams.value.limit }
  params.siteCode = siteFilter.value || undefined
  listWarehouse(params).then(res => {
    const payload = res.data || {}
    warehouseList.value = Array.isArray(payload.rows) ? payload.rows : []
    total.value = Number(payload.total || 0)
    loading.value = false
  }).catch(() => { loading.value = false })
}
function cancel() { open.value = false; reset() }
function reset() {
  proxy.resetForm("warehouseRef")
  form.value = {
    warehouseCode: null,
    // 新增默认站点取当前站点；ALL 时留空，必须由用户选择（仓库站点创建后不可变）
    siteCode: siteFilter.value || null,
    warehouseName: null,
    warehouseType: null,
    fulfillmentMode: null,
    isFbn: null,
    status: null,
  }
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function resetQuery() { proxy.resetForm("queryRef"); handleQuery() }
function handleSelectionChange(sel) { ids.value = sel.map(i => i.id); single.value = sel.length !== 1 }
function handleAdd() { reset(); open.value = true; title.value = "添加仓库" }
function handleUpdate(row) {
  reset()
  const _id = row.id || ids.value[0]
  getWarehouse(_id).then(res => { form.value = res.data || {}; open.value = true; title.value = "修改仓库" })
}
function submitForm() {
  proxy.$refs["warehouseRef"].validate(valid => {
    if (!valid) return
    const op = form.value.id != null ? updateWarehouse(form.value.id, form.value) : addWarehouse(form.value)
    op.then(() => { proxy.$modal.msgSuccess("操作成功"); open.value = false; getList() })
  })
}
function handleStatus(row, status) {
  changeWarehouseStatus(row.id, status).then(() => { proxy.$modal.msgSuccess("操作成功"); getList() })
}
function handleDisable() { const id = ids.value[0]; if (id) changeWarehouseStatus(id, 'INACTIVE').then(() => { proxy.$modal.msgSuccess("已停用"); getList() }) }
function handleEnable() { const id = ids.value[0]; if (id) changeWarehouseStatus(id, 'ACTIVE').then(() => { proxy.$modal.msgSuccess("已启用"); getList() }) }
getList()
</script>
