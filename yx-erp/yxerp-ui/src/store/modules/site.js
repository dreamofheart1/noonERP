import { listSite } from '@/api/noon/masterdata/site'

/**
 * 全局站点上下文（仅用于展示与查询范围收敛，不构成任何授权）。
 * 可选项完全来自后端 listSite 返回的「当前用户授权站点」，前端不写死 AE/SA。
 */
export const useSiteStore = defineStore('site', {
  state: () => ({
    // ALL = 全部授权站点
    siteCode: 'ALL',
    siteOptions: []
  }),
  actions: {
    async initSites() {
      try {
        const res = await listSite({ offset: 0, limit: 200 })
        const payload = (res && res.data) ? res.data : {}
        const rows = Array.isArray(payload.rows) ? payload.rows : []
        this.siteOptions = rows
          .map(row => {
            const code = row && (row.siteCode || row.site_code)
            return code ? { value: String(code), label: String(code) } : null
          })
          .filter((item, index, arr) => item && arr.findIndex(x => x.value === item.value) === index)
        // 当前选中站点已不在授权列表时回退 ALL，避免保留越界上下文
        if (this.siteCode !== 'ALL' && !this.siteOptions.some(item => item.value === this.siteCode)) {
          this.siteCode = 'ALL'
        }
      } catch (e) {
        // 接口失败/无权限：保持空选项，仅显示 ALL，不阻塞页面
        this.siteOptions = []
        this.siteCode = 'ALL'
      }
    },
    setSite(code) {
      // 只接受 ALL 或真实授权站点，防止伪造前端上下文被误当作授权依据
      if (code === 'ALL' || this.siteOptions.some(item => item.value === code)) {
        this.siteCode = code
      }
    }
  }
})

export default useSiteStore
