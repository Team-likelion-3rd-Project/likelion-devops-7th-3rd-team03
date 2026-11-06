import { useNavigate } from 'react-router-dom'
import Button from '@cloudscape-design/components/button'
import ContentLayout from '@cloudscape-design/components/content-layout'
import Header from '@cloudscape-design/components/header'

// 정의되지 않은 경로. 단축 링크(/:code) 리다이렉트는 백엔드가 처리하므로 SPA가 받을 일이 없다.
export function NotFound() {
  const navigate = useNavigate()

  return (
    <ContentLayout
      header={
        <Header variant="h1" description="주소가 잘못되었거나 이동된 페이지입니다.">
          페이지를 찾을 수 없습니다
        </Header>
      }
    >
      <Button variant="primary" onClick={() => navigate('/')}>
        홈으로 돌아가기
      </Button>
    </ContentLayout>
  )
}
