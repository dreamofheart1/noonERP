import { computed, watch } from 'vue'
import { useSiteStore } from '@/store/modules/site'

/**
 * 全局站点上下文：前端只用于收敛查询与展示，服务端 SiteAuthGuard 仍是唯一授权边界。
 * siteFilter 为 null 表示「全部授权站点」，请求时不传 siteCode。
 * siteOptions 为「当前用户真实授权站点」，用于弹窗选择，避免前端写死 AE/SA。
 */
export function useSiteScope(onChange) {
  const siteStore = useSiteStore()
  const siteCode = computed(() => siteStore.siteCode)
  const siteFilter = computed(() => (siteStore.siteCode === 'ALL' ? null : siteStore.siteCode))
  const siteLabel = computed(() => (siteStore.siteCode === 'ALL' ? '全部授权站点' : siteStore.siteCode))
  const siteOptions = computed(() => siteStore.siteOptions)
  if (typeof onChange === 'function') watch(() => siteStore.siteCode, onChange)
  return { siteStore, siteCode, siteFilter, siteLabel, siteOptions }
}

export default useSiteScope
