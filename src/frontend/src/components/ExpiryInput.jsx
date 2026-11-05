import DatePicker from '@cloudscape-design/components/date-picker'
import SpaceBetween from '@cloudscape-design/components/space-between'
import TimeInput from '@cloudscape-design/components/time-input'

const DEFAULT_TIME = '23:59'

// 만료일 입력. 값은 'YYYY-MM-DDTHH:mm'(현지 시각) 문자열이고, 비어 있으면 ''이다.
// 날짜를 고르면 시각은 23:59로 시작한다.
export function ExpiryInput({ value, onChange, ariaLabel = '만료일' }) {
  const [date = '', time = DEFAULT_TIME] = value ? value.split('T') : []
  const set = (d, t) => onChange(d ? `${d}T${t || DEFAULT_TIME}` : '')

  return (
    <SpaceBetween direction="horizontal" size="xs">
      <DatePicker
        value={date}
        onChange={({ detail }) => set(detail.value, time)}
        locale="ko"
        placeholder="YYYY/MM/DD"
        todayAriaLabel="오늘"
        nextMonthAriaLabel="다음 달"
        previousMonthAriaLabel="이전 달"
        ariaLabel={`${ariaLabel} 날짜`}
      />
      <TimeInput
        value={time}
        onChange={({ detail }) => set(date, detail.value)}
        format="hh:mm"
        use24Hour
        placeholder="hh:mm"
        disabled={!date}
        ariaLabel={`${ariaLabel} 시각`}
      />
    </SpaceBetween>
  )
}
