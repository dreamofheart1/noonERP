import request from '@/utils/request'

const BASE = '/noon/inventory/stocktake'

// 盘点单列表（按站点授权过滤）
export function listStocktake(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}

// 盘点单详情
export function getStocktake(id) {
  return request({ url: BASE + '/get', method: 'get', params: { id } })
}

// 创建盘点单（DRAFT，不改库存）
export function createStocktake(data) {
  return request({ url: BASE + '/create', method: 'post', data })
}

// 应用盘点（过账：盘盈/盘亏回写四态台账）
export function applyStocktake(data) {
  return request({ url: BASE + '/apply', method: 'post', data })
}

// 查询盘点请求结果（幂等重开）
export function getStocktakeResult(requestId) {
  return request({ url: BASE + '/result', method: 'get', params: { requestId } })
}
