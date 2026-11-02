// 로그인 후 화면의 뼈대. 시안(snipy-dashboard)의 TopNavigation + SideNavigation + 이동 경로를 그대로 따른다.
import { useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Mode, applyMode } from '@cloudscape-design/global-styles'
import AppLayout from '@cloudscape-design/components/app-layout'
import Box from '@cloudscape-design/components/box'
import BreadcrumbGroup from '@cloudscape-design/components/breadcrumb-group'
import Button from '@cloudscape-design/components/button'
import FormField from '@cloudscape-design/components/form-field'
import Input from '@cloudscape-design/components/input'
import Modal from '@cloudscape-design/components/modal'
import SideNavigation from '@cloudscape-design/components/side-navigation'
import SpaceBetween from '@cloudscape-design/components/space-between'
import TopNavigation from '@cloudscape-design/components/top-navigation'
import { useAuth } from '../context/AuthContext'
import { useWorkspace } from '../context/WorkspaceContext'
import { createWorkspace, joinWorkspace } from '../api/workspaces'
import { ROLE_LABEL } from '../permissions'
import { toast, useFlashes } from './Toast'
import { displayName } from '../auth/devLogin'
import Flashbar from '@cloudscape-design/components/flashbar'

const LOGO =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='32' height='32' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%23006ce0'/%3E%3Cpath d='M11 18.5l-1.8 1.8a3.2 3.2 0 01-4.5-4.5l3.6-3.6a3.2 3.2 0 014.5 0M21 13.5l1.8-1.8a3.2 3.2 0 014.5 4.5l-3.6 3.6a3.2 3.2 0 01-4.5 0M13 19l6-6' stroke='white' stroke-width='2.2' fill='none' stroke-linecap='round'/%3E%3C/svg%3E"

const CONTENT_WIDTH = 1400

// 지금 있는 화면만 메뉴에 둔다. 프로젝트·팀 활동은 2차 릴리스에서 추가한다.
const PAGES = [
  { href: '/', text: '단축하기' },
  { href: '/dashboard', text: '링크' },
  { href: '/stats', text: '분석' }
]

function sectionOf(path) {
  if (path.startsWith('/links/')) return '/dashboard'
  return path
}

export function AppShell() {
  const { current, can } = useWorkspace()
  const flashes = useFlashes()
  const location = useLocation()
  const navigate = useNavigate()
  const [navOpen, setNavOpen] = useState(true)
  const [modal, setModal] = useState(null) // 'create' | 'join' | null
  const active = sectionOf(location.pathname)

  const manage = [
    can('member:manage') && { type: 'link', text: '멤버', href: '/members' },
    can('workspace:update') && { type: 'link', text: '설정', href: '/settings' }
  ].filter(Boolean)

  const crumbs = [{ text: current?.name ?? 'Snipy', href: '/' }]
  const page = [
    ...PAGES,
    { href: '/members', text: '멤버' },
    { href: '/settings', text: '설정' }
  ].find((p) => p.href === active)
  if (page && page.href !== '/') crumbs.push({ text: page.text, href: page.href })
  if (location.pathname.startsWith('/links/'))
    crumbs.push({ text: '통계', href: location.pathname })

  const follow = (e) => {
    if (e.detail.external) return
    e.preventDefault()
    navigate(e.detail.href)
  }

  return (
    <>
      <div id="top-nav" style={{ position: 'sticky', top: 0, zIndex: 1002 }}>
        <TopBar onCreate={() => setModal('create')} onJoin={() => setModal('join')} />
      </div>
      <AppLayout
        headerSelector="#top-nav"
        navigationOpen={navOpen}
        onNavigationChange={({ detail }) => setNavOpen(detail.open)}
        navigation={
          <SideNavigation
            header={{ href: '/', text: current?.name ?? 'Snipy' }}
            activeHref={active}
            onFollow={follow}
            items={[
              ...PAGES.map((p) => ({ type: 'link', text: p.text, href: p.href })),
              ...(manage.length
                ? [
                    { type: 'divider' },
                    { type: 'section', text: '워크스페이스 관리', items: manage }
                  ]
                : [])
            ]}
          />
        }
        breadcrumbs={
          <BreadcrumbGroup
            items={crumbs}
            ariaLabel="현재 위치"
            expandAriaLabel="경로 더 보기"
            onFollow={follow}
          />
        }
        notifications={<Flashbar items={flashes} />}
        toolsHide
        maxContentWidth={CONTENT_WIDTH}
        ariaLabels={{
          navigation: '메뉴',
          navigationClose: '메뉴 닫기',
          navigationToggle: '메뉴 열기',
          notifications: '알림'
        }}
        content={<Outlet />}
      />
      {modal === 'create' && <CreateWorkspaceModal onDismiss={() => setModal(null)} />}
      {modal === 'join' && <JoinWorkspaceModal onDismiss={() => setModal(null)} />}
    </>
  )
}

function useColorMode() {
  const [dark, setDark] = useState(false)
  useEffect(() => {
    applyMode(dark ? Mode.Dark : Mode.Light)
  }, [dark])
  return [dark, setDark]
}

const colorModeButton = (dark, setDark) => ({
  type: 'button',
  iconName: 'light-dark',
  title: dark ? '라이트 모드' : '다크 모드',
  ariaLabel: dark ? '라이트 모드로 전환' : '다크 모드로 전환',
  onClick: () => setDark(!dark)
})

const i18nStrings = {
  overflowMenuTriggerText: '더보기',
  overflowMenuTitleText: '전체 메뉴',
  overflowMenuBackIconAriaLabel: '뒤로',
  overflowMenuDismissIconAriaLabel: '닫기'
}

// 로그인 전 화면(단축하기, 로그인 등)의 뼈대. 로그인 후와 같은 상단바 모양을 쓰고 사이드 메뉴만 없다.
export function GuestShell() {
  const navigate = useNavigate()
  const flashes = useFlashes()
  const [dark, setDark] = useColorMode()

  return (
    <>
      <div id="top-nav" style={{ position: 'sticky', top: 0, zIndex: 1002 }}>
        <TopNavigation
          identity={{
            href: '/',
            title: 'Snipy',
            logo: { src: LOGO, alt: 'Snipy' },
            onFollow: (e) => {
              e.preventDefault()
              navigate('/')
            }
          }}
          i18nStrings={i18nStrings}
          utilities={[
            colorModeButton(dark, setDark),
            {
              type: 'button',
              text: '로그인',
              variant: 'primary-button',
              onClick: () => navigate('/login')
            }
          ]}
        />
      </div>
      <AppLayout
        headerSelector="#top-nav"
        navigationHide
        notifications={<Flashbar items={flashes} />}
        toolsHide
        maxContentWidth={CONTENT_WIDTH}
        content={<Outlet />}
      />
    </>
  )
}

function TopBar({ onCreate, onJoin }) {
  const { user, logout } = useAuth()
  const { workspaces, current, status, select, refresh } = useWorkspace()
  const navigate = useNavigate()
  const [dark, setDark] = useColorMode()

  const workspaceMenu =
    status === 'error'
      ? {
          type: 'button',
          iconName: 'status-warning',
          text: '팀 목록 다시 불러오기',
          onClick: () => refresh()
        }
      : {
          type: 'menu-dropdown',
          text: current?.name ?? '불러오는 중…',
          description: '워크스페이스 전환',
          iconName: 'group',
          items: [
            {
              id: 'workspaces',
              text: '내 워크스페이스',
              items: workspaces.map((w) => ({
                id: `ws:${w.workspaceId}`,
                text: w.name,
                secondaryText: `${ROLE_LABEL[w.role]}${w.personal ? ' · 개인' : ''}`,
                iconName: w.workspaceId === current?.workspaceId ? 'check' : undefined
              }))
            },
            { id: 'new', text: '새 워크스페이스', iconName: 'add-plus' },
            { id: 'join', text: '초대 코드로 합류', iconName: 'key' }
          ],
          onItemClick: ({ detail }) => {
            if (detail.id === 'new') onCreate()
            else if (detail.id === 'join') onJoin()
            else select(detail.id.slice(3))
          }
        }

  return (
    <TopNavigation
      identity={{
        href: '/',
        title: 'Snipy',
        logo: { src: LOGO, alt: 'Snipy' },
        onFollow: (e) => {
          e.preventDefault()
          navigate('/')
        }
      }}
      i18nStrings={i18nStrings}
      utilities={[
        workspaceMenu,
        colorModeButton(dark, setDark),
        {
          type: 'menu-dropdown',
          text: displayName(user.userId),
          iconName: 'user-profile',
          items: [{ id: 'signout', text: '로그아웃' }],
          onItemClick: async () => {
            // 보호된 페이지에서 logout()이 먼저 user를 null로 바꾸면 ProtectedRoute가
            // 이 navigate보다 먼저 /login으로 보내므로, 홈으로 먼저 나간 뒤 정리한다.
            navigate('/')
            await logout()
          }
        }
      ]}
    />
  )
}

// 이름(또는 코드) 하나를 받아 서버에 보내고, 성공하면 그 팀으로 전환한다
function NameModal({ header, label, description, placeholder, submitText, run, done, onDismiss }) {
  const { refresh } = useWorkspace()
  const [value, setValue] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit() {
    if (!value.trim()) return setError(`${label}을(를) 입력해 주세요.`)
    setBusy(true)
    try {
      const w = await run(value)
      toast(done(w), 'success')
      await refresh(w.workspaceId)
      onDismiss()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <Modal
      visible
      onDismiss={onDismiss}
      header={header}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onDismiss}>
              취소
            </Button>
            <Button variant="primary" loading={busy} onClick={submit}>
              {submitText}
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        <FormField label={label} description={description} errorText={error}>
          <Input
            value={value}
            onChange={({ detail }) => {
              setValue(detail.value)
              setError(null)
            }}
            placeholder={placeholder}
            autoFocus
          />
        </FormField>
      </form>
    </Modal>
  )
}

const CreateWorkspaceModal = ({ onDismiss }) => (
  <NameModal
    header="새 워크스페이스"
    label="이름"
    description="링크를 함께 관리할 팀이나 프로젝트 이름이면 좋아요. 만든 사람은 관리자가 돼요."
    placeholder="예: 브랜드전략팀"
    submitText="만들기"
    run={createWorkspace}
    done={(w) => `'${w.name}' 워크스페이스를 만들었어요. 이제 팀원을 초대해 보세요.`}
    onDismiss={onDismiss}
  />
)

// 이미 가입한 회원이 관리자에게 받은 초대 코드를 입력해 합류한다
const JoinWorkspaceModal = ({ onDismiss }) => (
  <NameModal
    header="초대 코드로 합류"
    label="초대 코드"
    description="팀 관리자에게 받은 코드를 입력해 주세요. 합류하면 멤버 역할로 시작해요."
    placeholder="예: Mk7Qx2pRt9"
    submitText="합류"
    run={joinWorkspace}
    done={(w) => `'${w.name}'에 멤버로 합류했어요.`}
    onDismiss={onDismiss}
  />
)
