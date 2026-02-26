import request from '../utils/request'

export function getUserInfo(userId) {
  return request.get(`/users/${userId}`)
}
