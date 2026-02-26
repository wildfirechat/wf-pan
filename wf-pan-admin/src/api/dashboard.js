import request from '../utils/request'

export function getStats() {
  return request.get('/dashboard/stats')
}
