// node에는 localStorage/sessionStorage가 없으므로 메모리 저장소로 대신한다. 테스트마다 비운다.
import { afterEach, beforeEach } from 'vitest'

class MemoryStorage {
  #data = new Map()
  getItem(key) {
    return this.#data.has(key) ? this.#data.get(key) : null
  }
  setItem(key, value) {
    this.#data.set(key, String(value))
  }
  removeItem(key) {
    this.#data.delete(key)
  }
  clear() {
    this.#data.clear()
  }
}

beforeEach(() => {
  globalThis.localStorage = new MemoryStorage()
  globalThis.sessionStorage = new MemoryStorage()
})

afterEach(() => {
  globalThis.localStorage.clear()
  globalThis.sessionStorage.clear()
})
