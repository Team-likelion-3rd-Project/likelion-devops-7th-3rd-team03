import { useEffect, useState } from 'react'

let idCounter = 0
const listeners = new Set()

// 어디서든 부를 수 있는 알림. 화면 위쪽 Flashbar(AppShell, GuestShell)가 useFlashes로 받아 보여 준다.
export function toast(message, type = 'info') {
  const item = { id: ++idCounter, message, type }
  listeners.forEach((fn) => fn(item))
}

// Flashbar의 items 모양으로 돌려준다. 3.2초 뒤에 저절로 사라지고, 직접 닫을 수도 있다.
export function useFlashes() {
  const [items, setItems] = useState([])

  useEffect(() => {
    const handler = (item) => {
      setItems((prev) => [...prev, item])
      setTimeout(() => {
        setItems((prev) => prev.filter((i) => i.id !== item.id))
      }, 3200)
    }
    listeners.add(handler)
    return () => listeners.delete(handler)
  }, [])

  return items.map((item) => ({
    id: String(item.id),
    type: item.type,
    content: item.message,
    dismissible: true,
    onDismiss: () => setItems((prev) => prev.filter((i) => i.id !== item.id))
  }))
}
