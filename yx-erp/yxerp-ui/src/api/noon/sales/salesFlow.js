import request from '@/utils/request'

// 订单导入
export function importOrder(data) {
  return request({ url: '/noon/sales/order/import', method: 'post', data })
}
// 妥投出库（FIFO）
export function deliverOrder(data) {
  return request({ url: '/noon/sales/order/deliver', method: 'post', data })
}
// 退货入账
export function importReturn(data) {
  return request({ url: '/noon/sales/return/import', method: 'post', data })
}
// 退货红冲
export function reverseReturn(data) {
  return request({ url: '/noon/sales/return/reverse', method: 'post', data })
}
// A/B/C 分级处置
export function disposeReturn(data) {
  return request({ url: '/noon/sales/return/dispose', method: 'post', data })
}
// 幂等结果回读
export function salesResult(requestId, action) {
  return request({ url: '/noon/sales/result', method: 'get', params: { requestId, action } })
}
// 订单列表/详情
export function listOrders(params) {
  return request({ url: '/noon/sales/order/list', method: 'get', params })
}
export function getOrder(id) {
  return request({ url: '/noon/sales/order/get', method: 'get', params: { id } })
}
// 退货列表/详情
export function listReturns(params) {
  return request({ url: '/noon/sales/return/list', method: 'get', params })
}
export function getReturn(id) {
  return request({ url: '/noon/sales/return/get', method: 'get', params: { id } })
}
