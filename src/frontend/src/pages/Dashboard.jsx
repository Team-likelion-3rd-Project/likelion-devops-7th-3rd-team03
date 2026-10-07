import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { listLinks, updateLink, deleteLink } from '../api/links'
import { ApiError } from '../api/http'
import { toast } from '../components/Toast'

const PAGE_SIZE = 20

export function Dashboard() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [loadError, setLoadError] = useState(false)
  const [editingLink, setEditingLink] = useState(null)

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      const res = await listLinks(page, PAGE_SIZE)
      setData(res)
    } catch (err) {
      setLoadError(true)
      toast(err instanceof ApiError ? err.message : '링크 목록을 불러오지 못했습니다.', 'error')
    }
  }, [page])

  useEffect(() => {
    load()
  }, [load])

  async function handleDelete(link) {
    if (!confirm(`'${link.title || link.shortUrl}' 링크를 삭제할까요?`)) return
    try {
      await deleteLink(link.linkId)
      toast('링크를 삭제했습니다.', 'success')
      load()
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '삭제에 실패했습니다.', 'error')
    }
  }

  async function handleSaveEdit(link, form) {
    try {
      await updateLink(link.linkId, {
        originalUrl: form.originalUrl.trim() || undefined,
        title: form.title.trim() || undefined,
        expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : undefined,
      })
      toast('링크를 수정했습니다.', 'success')
      setEditingLink(null)
      load()
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '수정에 실패했습니다.', 'error')
    }
  }

  if (loadError) {
    return (
      <div className="page">
        <div className="card empty-state">
          링크 목록을 불러오지 못했습니다.{' '}
          <button className="btn btn--ghost btn--sm" onClick={load}>
            다시 시도
          </button>
        </div>
      </div>
    )
  }

  if (data === null) return <div className="page">불러오는 중...</div>

  const { links, pagination } = data

  return (
    <div className="page">
      <div className="page__header">
        <h1>내 링크</h1>
        <Link to="/" className="btn btn--primary">
          + 새 링크
        </Link>
      </div>

      {links.length === 0 ? (
        <div className="card empty-state">
          아직 만든 링크가 없습니다.{' '}
          <Link to="/">지금 첫 링크를 만들어보세요.</Link>
        </div>
      ) : (
        <div className="card table-card">
          <table className="table">
            <thead>
              <tr>
                <th>단축 URL</th>
                <th>원본 URL</th>
                <th>제목</th>
                <th>상태</th>
                <th>만료일</th>
                <th>생성일</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {links.map((link) => (
                <tr key={link.linkId}>
                  <td className="table__shorturl" title={link.shortUrl}>
                    <a href={link.shortUrl} target="_blank" rel="noreferrer">
                      {link.shortUrl}
                    </a>
                  </td>
                  <td className="table__url" title={link.originalUrl}>
                    {link.originalUrl}
                  </td>
                  <td>{link.title || '-'}</td>
                  <td>
                    <span className={`badge ${isExpired(link.expiresAt) ? 'badge--off' : 'badge--good'}`}>
                      {isExpired(link.expiresAt) ? '만료' : '활성'}
                    </span>
                  </td>
                  <td className="table__date">{formatDate(link.expiresAt)}</td>
                  <td className="table__date">{formatDate(link.createdAt)}</td>
                  <td className="table__actions">
                    <Link className="btn btn--ghost btn--sm" to={`/links/${link.linkId}/stats`}>
                      통계
                    </Link>
                    <button className="btn btn--ghost btn--sm" onClick={() => setEditingLink(link)}>
                      수정
                    </button>
                    <button className="btn btn--danger btn--sm" onClick={() => handleDelete(link)}>
                      삭제
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {pagination.totalPages > 1 && (
            <div className="table-card__pager">
              <button className="btn btn--ghost btn--sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                이전
              </button>
              <span>
                {pagination.page + 1} / {pagination.totalPages}
              </span>
              <button
                className="btn btn--ghost btn--sm"
                disabled={page + 1 >= pagination.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </button>
            </div>
          )}
        </div>
      )}

      {editingLink && (
        <EditLinkModal
          link={editingLink}
          onClose={() => setEditingLink(null)}
          onSave={(form) => handleSaveEdit(editingLink, form)}
        />
      )}
    </div>
  )
}

function EditLinkModal({ link, onClose, onSave }) {
  const [originalUrl, setOriginalUrl] = useState(link.originalUrl)
  const [title, setTitle] = useState(link.title || '')
  const [expiresAt, setExpiresAt] = useState(link.expiresAt ? link.expiresAt.slice(0, 16) : '')

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="card modal-card" onClick={(e) => e.stopPropagation()}>
        <h2>링크 수정</h2>
        <label className="field">
          <span>원본 URL</span>
          <input value={originalUrl} onChange={(e) => setOriginalUrl(e.target.value)} />
        </label>
        <label className="field">
          <span>제목</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} />
        </label>
        <label className="field">
          <span>만료일</span>
          <input type="datetime-local" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button className="btn btn--ghost" onClick={onClose}>
            취소
          </button>
          <button className="btn btn--primary" onClick={() => onSave({ originalUrl, title, expiresAt })}>
            저장
          </button>
        </div>
      </div>
    </div>
  )
}

// 백엔드 Link.isExpired()와 동일한 경계값(now >= expiresAt이면 만료)을 사용한다.
function isExpired(expiresAt) {
  return Boolean(expiresAt) && Date.now() >= new Date(expiresAt).getTime()
}

function formatDate(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' })
}
