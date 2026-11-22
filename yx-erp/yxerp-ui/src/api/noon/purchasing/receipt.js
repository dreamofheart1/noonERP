import request from '@/utils/request'

const BASE = '/noon/purchasing/receipt'

// 部分收货（按订单站点授权，超管全站）
export function receiveGoods(data) {
  return request({ url: BASE + '/receive', method: 'post', data })
}

// 查询收货明细列表（授权站点内）
export function listReceipt(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 查询上次收货请求结果（契约 v2.1 §9.6）：重开弹窗先查，避免响应不明重复入账
export function getReceiptResult(requestId) {
  return request({ url: BASE + '/result', method: 'get', params: { requestId } })
}
