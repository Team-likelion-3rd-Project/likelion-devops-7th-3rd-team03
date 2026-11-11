import { describe, expect, it } from 'vitest'
import { countryName, dimensionName, mergeByName, osName } from './statsLabels'

describe('countryName', () => {
  it('국가 코드를 한글 국가명으로 바꾼다', () => {
    expect(countryName('KR')).toBe('대한민국')
    expect(countryName('JP')).toBe('일본')
  })

  it('값이 없거나 UNKNOWN이면 알 수 없음이다', () => {
    expect(countryName('UNKNOWN')).toBe('알 수 없음')
    expect(countryName('')).toBe('알 수 없음')
    expect(countryName(null)).toBe('알 수 없음')
  })

  it('국가 코드 형식이 아니면 예외 없이 그대로 보여 준다', () => {
    expect(countryName('KR-11')).toBe('KR-11')
  })
})

describe('dimensionName', () => {
  it('값이 없거나 UNKNOWN이면 알 수 없음, 아니면 그대로다', () => {
    expect(dimensionName('UNKNOWN')).toBe('알 수 없음')
    expect(dimensionName(null)).toBe('알 수 없음')
    expect(dimensionName('Chrome')).toBe('Chrome')
  })
})

describe('osName', () => {
  it('Yauaa OS 이름을 익숙한 이름으로 바꾼다', () => {
    expect(osName('Windows NT')).toBe('Windows')
    expect(osName('Mac OS X')).toBe('macOS')
    expect(osName('Mac OS')).toBe('macOS')
  })

  it('모르는 값은 그대로, 값이 없거나 UNKNOWN이면 알 수 없음이다', () => {
    expect(osName('Android')).toBe('Android')
    expect(osName('Linux')).toBe('Linux')
    expect(osName('UNKNOWN')).toBe('알 수 없음')
    expect(osName(null)).toBe('알 수 없음')
  })
})

describe('mergeByName', () => {
  it('같은 이름이 된 행은 클릭 수와 비율을 합치고 클릭이 많은 순으로 정렬한다', () => {
    const rows = [
      { value: 'Mac OS', clickCount: 10, percentage: 20 },
      { value: 'Windows NT', clickCount: 30, percentage: 60 },
      { value: 'Mac OS X', clickCount: 10, percentage: 20 }
    ]
    expect(mergeByName(rows, osName)).toEqual(
      [
        { value: 'macOS', clickCount: 20, percentage: 40 },
        { value: 'Windows', clickCount: 30, percentage: 60 }
      ].sort((a, b) => b.clickCount - a.clickCount)
    )
  })

  it('합치지 않아도 되는 행은 그대로 둔다', () => {
    const rows = [{ value: 'Chrome', clickCount: 5, percentage: 100 }]
    expect(mergeByName(rows, dimensionName)).toEqual(rows)
  })
})
