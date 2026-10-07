// LinkController(/api/links) 연동. userId는 JWT에서 서버가 추출하므로 요청에 담지 않는다.
import { apiFetch } from './http'

export async function createLink({ originalUrl, title, expiresAt }) {
  const res = await apiFetch('/links', { method: 'POST', body: { originalUrl, title, expiresAt } })
  return res.data
}

export async function listLinks(page = 0, size = 20) {
  const params = new URLSearchParams({ page, size })
  const res = await apiFetch(`/links?${params}`)
  return res.data
}

export async function updateLink(linkId, { originalUrl, title, expiresAt }) {
  const res = await apiFetch(`/links/${linkId}`, { method: 'PATCH', body: { originalUrl, title, expiresAt } })
  return res.data
}

export async function deleteLink(linkId) {
  await apiFetch(`/links/${linkId}`, { method: 'DELETE' })
}
