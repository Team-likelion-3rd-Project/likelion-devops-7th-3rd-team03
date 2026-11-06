import { describe, expect, it } from 'vitest'
import { hourlyClicks } from './stats'

describe('hourlyClicks', () => {
  it('클릭이 있는 시간만 와도 0~23시를 모두 채우고 없는 시간은 0이다', () => {
    const hours = hourlyClicks([
      { value: '09', clickCount: 5, percentage: 50 },
      { value: '23', clickCount: 5, percentage: 50 }
    ])
    expect(hours).toHaveLength(24)
    expect(hours[0]).toEqual({ hour: 0, clickCount: 0 })
    expect(hours[9]).toEqual({ hour: 9, clickCount: 5 })
    expect(hours[23]).toEqual({ hour: 23, clickCount: 5 })
  })

  it('비어 있거나 시간이 아닌 값이 섞여도 24칸이 모두 0 또는 제 값이다', () => {
    expect(hourlyClicks([]).every((h) => h.clickCount === 0)).toBe(true)
    const withUnknown = hourlyClicks([{ value: 'UNKNOWN', clickCount: 3, percentage: 100 }])
    expect(withUnknown).toHaveLength(24)
    expect(withUnknown.every((h) => h.clickCount === 0)).toBe(true)
  })
})
