import request from '@/utils/request'

const BASE = '/noon/sellerlab'

// 手动同步 FBN 库存快照
export function syncFbnInventory(siteCode) {
  return request({ url: BASE + '/fbn-inventory/sync', method: 'post', params: { siteCode } })
}

// 官方 vs 系统 库存简单对比
export function listFbnCompare(query) {
  return request({ url: BASE + '/fbn-inventory/compare', method: 'get', params: query })
}

// 手动同步 Sales 销售明细
export function syncSalesItems(query) {
  return request({ url: BASE + '/sales/sync', method: 'post', params: query })
}

// Sales 明细列表（item_nr 编号，站点去重）
export function listSalesItems(query) {
  return request({ url: BASE + '/sales/list', method: 'get', params: query })
}

// ASN 平台快照同步与列表（平台编号与 ERP 内部编号分开）
export function syncPlatformAsn(query) {
  return request({ url: BASE + '/asn/sync', method: 'post', params: query })
}

export function listPlatformAsn(query) {
  return request({ url: BASE + '/asn/list', method: 'get', params: query })
}

// 期初建议（只读）与确认建账（需显式成本）
export function genOpeningProposal(query) {
  return request({ url: BASE + '/opening/proposal', method: 'post', params: query })
}
export function confirmOpening(query) {
  return request({ url: BASE + '/opening/confirm', method: 'post', params: query })
}
export function refreshSalesLink(siteCode) {
  return request({ url: BASE + '/sales-link/refresh', method: 'post', params: { siteCode } })
}
export function listReturnEvidence(siteCode) {
  return request({ url: BASE + '/return-evidence/list', method: 'get', params: { siteCode } })
}
