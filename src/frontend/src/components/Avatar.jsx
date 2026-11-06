import { useState } from 'react'

// 프로필 이미지를 보여주는 공용 컴포넌트. 가입 폼, 대시보드 등 여러 화면에서 같이 쓴다.
// 서버는 이미지 URL을 문자열로만 저장하므로, 값이 없거나(null) 불러오지 못하면(onError) 기본 아이콘으로 바꾼다.
export const DEFAULT_AVATAR_SRC = '/default-avatar.svg'

export function Avatar({ src, size = 40, alt = '프로필 이미지' }) {
  const [failedSrc, setFailedSrc] = useState('')
  const useDefault = !src || failedSrc === src

  return (
    <img
      className="avatar"
      src={useDefault ? DEFAULT_AVATAR_SRC : src}
      alt={alt}
      width={size}
      height={size}
      // 구글 등 외부 사진 서버가 Referer를 보고 막는 경우가 있어 보내지 않는다.
      referrerPolicy="no-referrer"
      onError={() => {
        if (!useDefault) setFailedSrc(src)
      }}
    />
  )
}
