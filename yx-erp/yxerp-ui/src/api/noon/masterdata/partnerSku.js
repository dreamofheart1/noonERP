import request from '@/utils/request'

const BASE = '/noon/masterdata/partner-sku'

// 查询Partner SKU 映射列表（授权站点内）
export function listPartnerSku(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询Partner SKU 映射详细
export function getPartnerSku(id) {
  return request({ url: BASE + '/' + id, method: 'get' })
}

// 展开行：该映射对应商品在站点的数量分布（站点+系统SKU汇总口径）
export function getPartnerSkuQuantity(id) {
  return request({ url: BASE + '/' + id + '/quantity-breakdown', method: 'get' })
}

// 新增Partner SKU 映射（仅超管）
export function addPartnerSku(data) {
  return request({ url: BASE + '/save', method: 'post', data })
}

// 修改Partner SKU 映射（仅超管）
export function updatePartnerSku(id, data) {
  return request({ url: BASE + '/' + id + '/update', method: 'post', data })
}

// 启用/停用Partner SKU 映射（仅超管）
export function changePartnerSkuStatus(id, status) {
  return request({ url: BASE + '/' + id + '/status', method: 'post', params: { status } })
}
