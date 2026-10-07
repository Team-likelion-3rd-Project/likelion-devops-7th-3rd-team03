import { useEffect, useState } from 'react'

let idCounter = 0
const listeners = new Set()

export function toast(message, type = 'info') {
  const item = { id: ++idCounter, message, type }
  listeners.forEach((fn) => fn(item))
}

export function ToastHost() {
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

  return (
    <div className="toast-host">
      {items.map((item) => (
        <div key={item.id} className={`toast toast--${item.type}`}>
          {item.message}
        </div>
      ))}
    </div>
  )
}
