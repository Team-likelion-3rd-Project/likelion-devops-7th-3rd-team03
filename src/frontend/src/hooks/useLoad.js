import { useCallback, useEffect, useRef, useState } from 'react'

// 화면 영역 하나의 불러오기 상태다. 영역마다 따로 쓰면 한 영역이 실패해도 다른 영역은 그대로 보인다.
// loader는 useCallback으로 안정시킨 함수를 넘긴다(바뀌면 다시 불러온다). reloadKey가 바뀌어도 다시 불러온다.
// 다시 불러오는 동안은 이전 data를 그대로 두고, 마지막 요청의 결과만 반영한다. state: loading | ready | error
export function useLoad(loader, reloadKey) {
  const [state, setState] = useState('loading')
  const [data, setData] = useState(null)
  const latest = useRef(0)

  const reload = useCallback(() => {
    const request = ++latest.current
    setState('loading')
    loader()
      .then((res) => {
        if (request !== latest.current) return
        setData(res)
        setState('ready')
      })
      .catch(() => {
        if (request === latest.current) setState('error')
      })
  }, [loader])

  useEffect(() => {
    reload()
  }, [reload, reloadKey])

  return { state, data, reload }
}
