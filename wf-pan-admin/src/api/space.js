import request from '../utils/request'

export function getSpaces(params) {
  return request.get('/spaces', { params })
}

export function getSpaceFiles(spaceId, params) {
  return request.get(`/spaces/${spaceId}/files`, { params })
}

export function createSpace(data) {
  return request.post('/spaces', null, { params: data })
}
