import { describe, expect, it } from 'vitest'
import { countryName } from './statsLabels'

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
