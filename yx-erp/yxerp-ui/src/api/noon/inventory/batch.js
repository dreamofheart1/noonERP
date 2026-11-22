import request from '@/utils/request'

const BASE = '/noon/inventory/batch'

// FIFO 批次只读台账（按站点授权过滤）
export function listBatch(query) {
  return request({ url: BASE + '/list', method: 'get', params: query })
}
