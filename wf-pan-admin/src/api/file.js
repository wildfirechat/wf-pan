import request from '../utils/request'

export function getAllFiles(params) {
  return request.get('/files', { params })
}

export function searchFiles(params) {
  return request.get('/files', { params })
}

export function deleteFile(id) {
  return request.delete(`/files/${id}`)
}

export function getFileDownloadUrl(id) {
  return request.get(`/files/${id}/url`)
}
