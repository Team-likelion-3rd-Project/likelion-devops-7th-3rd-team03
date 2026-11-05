// 통계 분포(breakdown)의 값을 화면에 보여 줄 이름으로 바꾼다.
const UNKNOWN_LABEL = '알 수 없음'
const countryNames = new Intl.DisplayNames(['ko'], { type: 'region' })

// 국가 코드(ISO 3166-1, 예: KR)를 국가명(대한민국)으로 바꾼다.
// 백엔드는 값을 모르면 "UNKNOWN"을 준다. 코드 형식이 아닌 값은 예외 없이 그대로 보여 준다.
export function countryName(code) {
  if (!code || code === 'UNKNOWN') return UNKNOWN_LABEL
  try {
    return countryNames.of(code)
  } catch {
    return code
  }
}
