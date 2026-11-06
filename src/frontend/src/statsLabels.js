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

// OS·브라우저처럼 값을 모르면 백엔드가 "UNKNOWN"을 주는 분포의 이름이다.
export function dimensionName(value) {
  return !value || value === 'UNKNOWN' ? UNKNOWN_LABEL : value
}

// 백엔드(Yauaa의 OperatingSystemName)가 주는 OS 이름을 사용자에게 익숙한 이름으로 바꾼다.
// Windows는 버전(10, 11)과 상관없이 모두 "Windows NT"로 와서 버전은 알 수 없다. 모르는 값은 그대로 보여 준다.
const OS_NAMES = { 'Windows NT': 'Windows', 'Mac OS': 'macOS', 'Mac OS X': 'macOS' }
export function osName(value) {
  return OS_NAMES[value] ?? dimensionName(value)
}

// 이름을 바꾸다 보면 서로 다른 값이 같은 이름이 된다(예: Mac OS, Mac OS X → macOS). 같은 이름은 합친다.
export function mergeByName(rows, nameOf) {
  const merged = new Map()
  for (const r of rows) {
    const name = nameOf(r.value)
    const prev = merged.get(name)
    merged.set(name, {
      ...r,
      value: name,
      clickCount: (prev?.clickCount ?? 0) + r.clickCount,
      percentage: Math.round(((prev?.percentage ?? 0) + r.percentage) * 10) / 10
    })
  }
  return [...merged.values()].sort((a, b) => b.clickCount - a.clickCount)
}
