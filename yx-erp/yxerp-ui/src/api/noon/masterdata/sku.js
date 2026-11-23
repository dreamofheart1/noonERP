import request from '@/utils/request'

const BASE = '/noon/masterdata/sku'

// 查询SKU列表（授权站点内）
export function listSku(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询SKU详细
export function getSku(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 新增SKU（仅超管）
export function addSku(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改SKU（仅超管）
export function updateSku(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用SKU（仅超管）
export function changeSkuStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}

// 从 Noon 平台同步 AE 商品到 SKU 管理与 Partner SKU 映射
export function syncNoonSku(siteCode = 'AE') {
  return request({ url: '/noon/sync/sku', method: 'post', params: { siteCode } })
}
