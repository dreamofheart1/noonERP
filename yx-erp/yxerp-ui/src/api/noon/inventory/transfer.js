import request from '@/utils/request'

const BASE = '/noon/inventory/transfer'

// 调拨单列表（按站点授权过滤）
export function listTransfer(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 调拨单详情
export function getTransfer(id) {
  return request({ url: BASE + '/get', method: 'get', params: { id } })
}

// 新建调拨单（从授权仓库+可用批次创建 DRAFT 单）
export function createTransfer(data) {
  return request({ url: BASE + '/create', method: 'post', data })
}

// 调拨发运（在途拆批）
export function shipTransfer(data) {
  return request({ url: BASE + '/ship', method: 'post', data })
}

// 调拨收货（在途转可用 + 短少冲销）
export function receiveTransfer(data) {
  return request({ url: BASE + '/receive', method: 'post', data })
}

// 查询调拨请求结果（幂等重开）
export function getTransferResult(requestId) {
  return request({ url: BASE + '/result', method: 'get', params: { requestId } })
}
