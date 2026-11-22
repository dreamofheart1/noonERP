<template>
  <div class="app-container noon-home">
    <!-- 模拟边界 / 数据口径提示 -->
    <el-alert class="scope-alert" type="info" :closable="false" show-icon>
      <template #title>隔离演示 · 数据口径说明</template>
      <template #default>
        本站为隔离演示环境，不连接生产、不改动平台数据。下方「关键指标」中的单据数量为
        真实 list 接口返回的 total（随站点上下文收敛）；销售额、毛利、AI 复核、账单争议等
        统计类指标因统计/财务/AI 接口尚未接入，统一以「—」显示并标注状态，不使用原型模拟值。
      </template>
    </el-alert>

    <!-- 欢迎 / 站点上下文 -->
    <el-card class="welcome-card" shadow="never">
      <div class="welcome-main">
        <div>
          <div class="welcome-title">你好，{{ displayName }}，欢迎回到 Noon ERP 工作台</div>
          <div class="welcome-sub">
            一个店铺 · UAE / KSA 双站点 · FBN 单履约模式。跨站点统计口径需在统计接口接入后统一归一。
          </div>
        </div>
        <div class="welcome-meta">
          <el-tag type="primary" effect="plain" size="small">当前站点：{{ siteLabel }}</el-tag>
          <span class="welcome-date">{{ today }}</span>
        </div>
      </div>
    </el-card>

    <!-- 关键指标：真实计数 -->
    <div class="section-head">
      <div>
        <span class="section-title">关键指标</span>
        <span class="section-sub">单据数量来自真实接口 · 站点：{{ siteLabel }}</span>
      </div>
      <el-button link type="primary" :loading="loading" @click="loadCounts">刷新数据</el-button>
    </div>

    <el-row :gutter="16" class="kpi-row">
      <el-col v-for="k in countKpis" :key="k.key" :xs="12" :sm="12" :md="6">
        <el-card class="kpi-card" shadow="hover" @click="go(k.path)">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">
            {{ kpiDisplay(k) }}<span v-if="k.value !== null" class="kpi-unit">{{ k.unit }}</span>
          </div>
          <div class="kpi-source">{{ k.source }}</div>
          <div class="kpi-foot">
            <el-tag v-if="k.noPerm" size="small" type="warning" effect="plain">无接口权限</el-tag>
            <el-tag v-else-if="k.failed" size="small" type="danger" effect="plain">接口暂不可用</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">真实数据</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 关键指标：未实现/待接入 -->
    <el-row :gutter="16" class="kpi-row kpi-pending-row">
      <el-col v-for="k in pendingKpis" :key="k.label" :xs="12" :sm="12" :md="6">
        <el-card class="kpi-card kpi-pending" shadow="never">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value kpi-value-muted">—</div>
          <div class="kpi-source">{{ k.note }}</div>
          <div class="kpi-foot">
            <el-tag size="small" type="info" effect="plain">{{ k.tag }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 七环节业务闭环 -->
    <el-card class="flow-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="section-title">七环节业务闭环</span>
          <span class="section-sub">仅已实现环节可点击进入 · 灰态为规划中</span>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col v-for="(step, index) in flowSteps" :key="step.name" :xs="24" :sm="12" :md="8" :lg="3">
          <div
            class="flow-node"
            :class="{ 'flow-disabled': !step.path }"
            @click="go(step.path)"
          >
            <div class="flow-index">0{{ index + 1 }}</div>
            <div class="flow-name">{{ step.name }}</div>
            <div class="flow-desc">{{ step.desc }}</div>
            <div class="flow-status">
              <el-tag v-if="step.path" size="small" type="success" effect="plain">已实现</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">{{ step.tag || '规划中' }}</el-tag>
            </div>
          </div>
        </el-col>
      </el-row>
      <div class="flow-note">
        闭环说明：补货建议人工确认后转采购单并回写 source_ref_no，闭环回到采购环节；
        当前利润计算、补货建议及财务/报表/集成模块尚未实现，故对应入口保持灰态，不提供可点击的模拟流程。
      </div>
      <div class="planned-modules">
        <span>后续规划模块：</span>
        <el-tag v-for="m in plannedModules" :key="m" size="small" type="info" effect="plain">{{ m }}</el-tag>
      </div>
    </el-card>

    <!-- 待办/告警 与 最近操作提示 -->
    <el-row :gutter="16" class="bottom-row">
      <el-col :xs="24" :md="12">
        <el-card class="info-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span class="section-title">待办与告警</span>
              <span class="section-sub">实时告警源未接入</span>
            </div>
          </template>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="暂无真实告警数据"
            description="告警需对接同步任务、账单、AI 复核等模块；为避免把原型模拟值当作真实业务，此处不展示示例数字。"
          />
          <ul class="plain-list">
            <li v-for="item in todoItems" :key="item">
              <span>{{ item }}</span>
              <el-tag size="small" type="info" effect="plain">未实现</el-tag>
            </li>
          </ul>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card class="info-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span class="section-title">最近操作提示</span>
              <span class="section-sub">审计 / 同步留痕未接入</span>
            </div>
          </template>
          <el-empty description="操作与同步留痕接口尚未接入" :image-size="64" />
          <div class="empty-tip">
            导入批次、同步任务等操作记录请到对应业务模块查看；本工作台不展示原型的模拟导入记录。
          </div>
        </el-card>
      </el-col>
    </el-row>

    <div class="footer-note">
      Noon ERP 隔离演示 · 仅使用真实业务接口数据，未实现功能已明确标注 · 不连接生产环境
    </div>
  </div>
</template>

<script setup name="Index">
import { listOrder } from '@/api/noon/purchasing/order'
import { listSupplier } from '@/api/noon/purchasing/supplier'
import { listOrders, listReturns } from '@/api/noon/sales/salesFlow'
import useSiteStore from '@/store/modules/site'
import useUserStore from '@/store/modules/user'

const router = useRouter()
const siteStore = useSiteStore()
const userStore = useUserStore()

const loading = ref(false)
const displayName = computed(() => userStore.nickName || '用户')
const siteLabel = computed(() => {
  const code = siteStore.siteCode
  if (code === 'ALL') return '全部授权站点'
  const found = siteStore.siteOptions.find(item => item.value === code)
  return found ? found.label : code
})
const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric', month: 'long', day: 'numeric', weekday: 'long'
})

// 单据计数：仅展示真实 list 接口返回的 total；无权限/失败时显示占位而非模拟数字
const countKpis = reactive([
  { key: 'purchase', label: '采购单', unit: '单', perm: 'noon:purchasing:order:list', source: '来源：采购单 list 接口 total', path: '/noon-purchasing/order', value: null, noPerm: false, failed: false },
  { key: 'supplier', label: '供应商', unit: '家', perm: 'noon:purchasing:supplier:list', source: '来源：供应商 list 接口 total', path: '/noon-purchasing/supplier', value: null, noPerm: false, failed: false },
  { key: 'saleOrder', label: '销售订单', unit: '单', perm: 'noon:sales:order:list', source: '来源：销售订单 list 接口 total', path: '/noon-sales/order', value: null, noPerm: false, failed: false },
  { key: 'returnOrder', label: '退货单', unit: '单', perm: 'noon:sales:return:list', source: '来源：退货单 list 接口 total', path: '/noon-sales/return', value: null, noPerm: false, failed: false }
])

const pendingKpis = [
  { label: '本周销售额（CNY 归一）', note: '需汇率归一与统计汇总接口', tag: '待接入统计接口' },
  { label: 'L1 贡献毛利', note: '需利润引擎与费用池汇总接口', tag: '待接入统计接口' },
  { label: 'AI 待复核建议', note: 'AI 复核模块尚未实现', tag: '未实现' },
  { label: '账单争议 ≤3 天', note: '财务账单模块尚未实现', tag: '未实现' }
]

const listApis = {
  purchase: listOrder,
  supplier: listSupplier,
  saleOrder: listOrders,
  returnOrder: listReturns
}

function hasPerm(perm) {
  const perms = userStore.permissions || []
  return perms.includes('*:*:*') || perms.includes(perm)
}

function kpiDisplay(k) {
  if (k.value === null) return loading.value && !k.failed && !k.noPerm ? '…' : '—'
  return String(k.value)
}

async function loadCounts() {
  loading.value = true
  const siteQuery = siteStore.siteCode === 'ALL' ? {} : { siteCode: siteStore.siteCode }
  await Promise.all(countKpis.map(async (k) => {
    if (!hasPerm(k.perm)) {
      k.noPerm = true
      k.failed = false
      k.value = null
      return
    }
    k.noPerm = false
    try {
      const res = await listApis[k.key]({ ...siteQuery, offset: 0, limit: 1 })
      const payload = res && res.data ? res.data : {}
      k.value = Number(payload.total || 0)
      k.failed = false
    } catch (e) {
      k.value = null
      k.failed = true
    }
  }))
  loading.value = false
}

function go(path) {
  if (path) router.push(path)
}

// 业务闭环：仅指向已注册的、真实存在的路由；未实现环节不跳转
const flowSteps = [
  { name: '主数据 / 映射', desc: 'SKU · 供应商 · 站点映射', path: '/noon-masterdata/partner-sku' },
  { name: '采购', desc: '采购单与报价', path: '/noon-purchasing/order' },
  { name: 'FIFO 批次', desc: '批次成本与台账', path: '/noon-inventory/batch' },
  { name: 'ASN 入 FBN', desc: '入仓与收货', path: '/noon-inbound/asn' },
  { name: '订单 / 退货', desc: '销售订单与退货处置', path: '/noon-sales/order' },
  { name: 'SKU 利润 L1+L2', desc: '利润计算与重算', path: '', tag: '未实现' },
  { name: '补货建议', desc: '销量驱动补货', path: '', tag: '规划中' }
]
const plannedModules = ['财务 / 账单', '报表中心', '平台集成']
const todoItems = ['账单争议倒计时与差异清单', '同步任务异常（DEAD）', '待补妥投时间订单', 'FBN 库龄 / 批次不足预警']

onMounted(loadCounts)
watch(() => siteStore.siteCode, loadCounts)
</script>

<style scoped lang="scss">
.noon-home {
  padding: 16px;

  .scope-alert {
    margin-bottom: 16px;
    :deep(.el-alert__title) {
      font-weight: 600;
      font-size: 13px;
    }
    :deep(.el-alert__description) {
      font-size: 12px;
      line-height: 1.7;
    }
  }

  .welcome-card {
    margin-bottom: 16px;
    background: linear-gradient(100deg, var(--el-color-primary-light-9), var(--el-bg-color) 60%);
    border-color: var(--el-border-color-lighter);

    .welcome-main {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;

      .welcome-title {
        font-size: 18px;
        font-weight: 600;
        color: var(--el-text-color-primary);
      }
      .welcome-sub {
        margin-top: 6px;
        font-size: 12px;
        color: var(--el-text-color-regular);
      }
      .welcome-meta {
        display: flex;
        align-items: center;
        gap: 12px;
        .welcome-date {
          font-size: 12px;
          color: var(--el-text-color-secondary);
        }
      }
    }
  }

  .section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 4px 0 12px;
  }
  .section-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }
  .section-sub {
    margin-left: 10px;
    font-size: 12px;
    font-weight: 400;
    color: var(--el-text-color-secondary);
  }
  .card-header {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
  }

  .kpi-row {
    margin-bottom: 16px;
  }
  .kpi-card {
    min-height: 124px;
    cursor: pointer;
    border-color: var(--el-border-color-lighter);
    &:hover {
      border-color: var(--el-color-primary-light-5);
    }
    .kpi-label {
      font-size: 13px;
      color: var(--el-text-color-regular);
    }
    .kpi-value {
      margin: 8px 0 4px;
      font-size: 26px;
      font-weight: 600;
      line-height: 1.2;
      color: var(--el-color-primary);
      .kpi-unit {
        margin-left: 4px;
        font-size: 12px;
        font-weight: 400;
        color: var(--el-text-color-secondary);
      }
    }
    .kpi-value-muted {
      color: var(--el-text-color-placeholder);
    }
    .kpi-source {
      font-size: 11px;
      color: var(--el-text-color-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .kpi-foot {
      margin-top: 10px;
    }
    &.kpi-pending {
      cursor: default;
      background: var(--el-fill-color-lighter);
      &:hover {
        border-color: var(--el-border-color-lighter);
      }
    }
  }

  .flow-card {
    margin-bottom: 16px;
    .flow-node {
      height: 132px;
      padding: 12px;
      box-sizing: border-box;
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 6px;
      background: var(--el-bg-color);
      cursor: pointer;
      transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
      &:hover {
        border-color: var(--el-color-primary-light-5);
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
        transform: translateY(-2px);
      }
      .flow-index {
        font-size: 12px;
        font-weight: 700;
        color: var(--el-color-primary);
      }
      .flow-name {
        margin-top: 8px;
        font-size: 14px;
        font-weight: 600;
        color: var(--el-text-color-primary);
      }
      .flow-desc {
        margin-top: 4px;
        font-size: 11px;
        color: var(--el-text-color-secondary);
        min-height: 16px;
      }
      .flow-status {
        margin-top: 10px;
      }
      &.flow-disabled {
        cursor: not-allowed;
        background: var(--el-fill-color-lighter);
        border-color: var(--el-border-color-lighter);
        opacity: 0.72;
        &:hover {
          border-color: var(--el-border-color-lighter);
          box-shadow: none;
          transform: none;
        }
        .flow-index,
        .flow-name {
          color: var(--el-text-color-secondary);
        }
      }
    }
    .flow-note {
      margin-top: 14px;
      padding: 10px 12px;
      font-size: 12px;
      line-height: 1.7;
      color: var(--el-text-color-regular);
      background: var(--el-fill-color-lighter);
      border-radius: 4px;
    }
    .planned-modules {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 12px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .bottom-row {
    margin-bottom: 16px;
    .info-card {
      height: 100%;
      .plain-list {
        list-style: none;
        margin: 12px 0 0;
        padding: 0;
        li {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 6px 0;
          font-size: 12px;
          color: var(--el-text-color-regular);
          border-bottom: 1px solid var(--el-border-color-lighter);
          &:last-child {
            border-bottom: none;
          }
        }
      }
      .empty-tip {
        font-size: 12px;
        line-height: 1.6;
        color: var(--el-text-color-secondary);
        text-align: center;
        padding: 0 8px 12px;
      }
    }
  }

  .footer-note {
    text-align: center;
    font-size: 12px;
    color: var(--el-text-color-placeholder);
    padding: 8px 0 16px;
  }
}
</style>
