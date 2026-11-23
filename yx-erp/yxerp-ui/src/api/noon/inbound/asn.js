import request from '@/utils/request'

// ASN 入仓列表（按站点授权过滤，真实 total）
export function listAsn(query) {
  return request({ url: '/noon/inventory/asn/list', method: 'get', params: query })
}

// ASN 详情（头+行+子批次血缘）
export function getAsn(asnId) {
  return request({ url: '/noon/inventory/asn/get', method: 'get', params: { asnId } })
}

// 幂等结果查询
export function getAsnResult(requestId) {
  return request({ url: '/noon/inventory/asn/result', method: 'get', params: { requestId } })
}

// 直发可选采购明细（后端权威可发量：已收 − 已建直发 ASN − 已本地入库）
export function listAvailablePoItems(siteCode) {
  return request({ url: '/noon/inventory/asn/available-items', method: 'get', params: { siteCode } })
}

// 新建 ASN
export function createAsn(data) {
  return request({ url: '/noon/inventory/asn/create', method: 'post', data })
}

// 封单发运
export function sealAsn(data) {
  return request({ url: '/noon/inventory/asn/seal', method: 'post', data })
}

// 收货登记
export function receiveAsn(data) {
  return request({ url: '/noon/inventory/asn/receive', method: 'post', data })
}
