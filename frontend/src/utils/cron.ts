// ============================================================================
// v18.9.8 — cron expression → 한국어 자연어 변환 utility
// ============================================================================
//
// v18.9.7 의 CronBuilder 가 입력 친화화. 본 utility 는 표시 친화화.
// 작업 테이블 / 상세 패널 등 모든 cron 노출 지점에서 재사용.
//
// v18.9.8-fix: cronstrue 한국어 locale 이 영어 직역체 ("시간 오전 06:00, 해당 월의
// 1일에") 라 사용자 친화성 부족. 본 프로젝트가 자주 쓰는 매일/매주/매월 패턴은
// 자체 변환으로 자연스러운 한국어 출력. 그 외 패턴은 cronstrue 한국어 fallback.
//
// v18.9.8-fix2: 5 필드 cron + Spring quartz `?` 표현 cover 확대.
//   - 5 필드: 분 시 일 월 요일 (`0 7 1 * *`)
//   - 6 필드: 초 분 시 일 월 요일 (`0 0 7 1 * *`)
//   - quartz: 일 또는 요일 필드의 `?` 처리 (`0 0 7 1 * ?`)

import cronstrue from 'cronstrue'
import 'cronstrue/locales/ko'

const WEEKDAY_KO: Record<string, string> = {
  MON: '월', TUE: '화', WED: '수', THU: '목', FRI: '금', SAT: '토', SUN: '일',
  '1': '월', '2': '화', '3': '수', '4': '목', '5': '금', '6': '토', '0': '일', '7': '일',
}

/**
 * cron expression 을 한국어 자연어로 변환.
 *
 * @param cron  Spring cron 5/6 필드 (quartz `?` 포함). null/빈 값 = 수동 실행
 * @returns     한국어 자연어. parse 실패 시 cronstrue fallback → 원본 cron
 */
export function formatCronToKorean(cron: string | null | undefined): string {
  if (!cron || !cron.trim()) return '수동 실행'

  // 자체 패턴 매칭 우선 (자연스러운 한국어)
  const direct = tryFormatDirectly(cron.trim())
  if (direct != null) return direct

  // fallback — cronstrue 한국어 (어색해도 표현 됨)
  try {
    return cronstrue.toString(cron, {
      locale: 'ko',
      use24HourTimeFormat: false,
      throwExceptionOnParseError: true,
    })
  } catch {
    return cron  // parse 실패 → 원본 cron
  }
}

/**
 * 자체 cron 패턴 매칭 — 매일/매주/매월 cover.
 * 5/6 필드 + quartz `?` 모두 처리. 매칭 안 되면 null 반환 (caller 가 cronstrue fallback).
 */
function tryFormatDirectly(cron: string): string | null {
  const parts = cron.split(/\s+/)

  // 필드 추출 — 5 필드 (분 시 일 월 요일) 또는 6 필드 (초 분 시 일 월 요일)
  let min: string, hour: string, day: string, month: string, dow: string
  if (parts.length === 6) {
    const [sec, ...rest] = parts
    // 초 = 0 (leading zero "00" 도 cover)
    if (!/^\d+$/.test(sec) || parseInt(sec, 10) !== 0) return null
    ;[min, hour, day, month, dow] = rest
  } else if (parts.length === 5) {
    ;[min, hour, day, month, dow] = parts
  } else {
    return null
  }

  // 분/시 단순 숫자 (06 같은 leading zero 도 OK)
  if (!/^\d+$/.test(min) || !/^\d+$/.test(hour)) return null

  // 월 필드 = '*' (특정 월만 지정은 cover X)
  if (month !== '*') return null

  const h = parseInt(hour, 10)
  const m = parseInt(min, 10)
  if (h > 23 || m > 59) return null
  const timeStr = formatTimeKorean(h, m)

  // 일/요일 quartz `?` 정규화 — `?` 는 `*` 와 의미 동일 (해당 필드 지정 안 함)
  const dayIsAny = day === '*' || day === '?'
  const dowIsAny = dow === '*' || dow === '?'

  // daily: 일 = any AND 요일 = any
  if (dayIsAny && dowIsAny) {
    return `매일 ${timeStr}`
  }

  // weekly: 일 = any AND 요일 = 특정
  if (dayIsAny && !dowIsAny) {
    const daysStr = formatWeekdaysKorean(dow)
    if (daysStr == null) return null   // 비표준 dow (range, step 등)
    return `${daysStr} ${timeStr}`
  }

  // monthly: 일 = 특정 AND 요일 = any
  if (!dayIsAny && dowIsAny && /^\d+$/.test(day)) {
    const d = parseInt(day, 10)
    if (d >= 1 && d <= 31) return `매월 ${d}일 ${timeStr}`
  }

  // 일/요일 모두 지정 = 비표준 (보통 quartz 가 막음). cover X
  return null
}

/**
 * 시간을 한국어 12시간 표현으로 변환.
 *   9, 0  → '오전 9시'
 *   9, 30 → '오전 9:30'
 *  14, 0  → '오후 2시'
 *   0, 0  → '오전 12시' (자정)
 */
function formatTimeKorean(h: number, m: number): string {
  const period = h < 12 ? '오전' : '오후'
  const h12 = h === 0 ? 12 : (h <= 12 ? h : h - 12)
  return m === 0
    ? `${period} ${h12}시`
    : `${period} ${h12}:${String(m).padStart(2, '0')}`
}

/**
 * dow 필드를 한국어 요일 표현으로 변환.
 *   MON         → '매주 월요일'
 *   MON,WED,FRI → '매주 월,수,금요일'
 *   MON-FRI     → null (range 비표준 cover X — cronstrue fallback)
 */
function formatWeekdaysKorean(dow: string): string | null {
  if (dow.includes('-') || dow.includes('/')) return null  // range, step 비표준

  const tokens = dow.split(',')
  const days = tokens.map((t) => WEEKDAY_KO[t.trim().toUpperCase()])
  if (days.some((d) => d == null)) return null

  if (days.length === 7) return '매일'
  if (days.length === 1) return `매주 ${days[0]}요일`
  return `매주 ${days.join(',')}요일`
}