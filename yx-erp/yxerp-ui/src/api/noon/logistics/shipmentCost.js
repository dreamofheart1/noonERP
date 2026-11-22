import request from '@/utils/request'

// 头程费用登记列表（按站点授权过滤，真实 total）
export function listShipmentCost(query) {
  return request({
    url: '/noon/inventory/shipment-cost/list',
    method: 'get',
    params: query
  })
}

// 头程费用详情
export function getShipmentCost(id) {
  return request({
    url: '/noon/inventory/shipment-cost/get',
    method: 'get',
    params: { id }
  })
}

// 登记请求结果（幂等重开用）
export function getShipmentCostResult(requestId) {
  return request({
    url: '/noon/inventory/shipment-cost/result',
    method: 'get',
    params: { requestId }
  })
}

// 新增头程费用登记
export function createShipmentCost(data) {
  return request({
    url: '/noon/inventory/shipment-cost/create',
    method: 'post',
    data
  })
}
