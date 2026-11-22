import request from '@/utils/request'

const BASE = '/noon/inventory/inbound'

// 入库单列表（按站点授权过滤，后端真实 total）
export function listInbound(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 入库单详情
export function getInbound(id) {
  return request({ url: BASE + '/get', method: 'get', params: { id } })
}

// 入库确认（授权经仓库归属 + 防伪造 + 幂等）。lines 只提交 purOrderItemId + qtyQualified。
export function confirmInbound(data) {
  return request({ url: BASE + '/confirm', method: 'post', data })
}

// 查询入库请求结果：重开弹窗先查上次 requestId 是否已入账，避免响应不明重复提交
export function getInboundResult(requestId) {
  return request({ url: BASE + '/result', method: 'get', params: { requestId } })
}
