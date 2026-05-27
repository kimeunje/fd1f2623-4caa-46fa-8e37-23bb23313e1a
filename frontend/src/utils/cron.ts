// ============================================================================
// v18.9.8 — cron expression → 한국어 자연어 변환 utility
// ============================================================================
//
// v18.9.7 의 CronBuilder 가 입력 친화화. 본 utility 는 표시 친화화.
// 작업 테이블 / 상세 패널 등 모든 cron 노출 지점에서 재사용.
//
// v18.9.8-fix: cronstrue 한국어 locale 이 영어 직역체 ("시간 오전 6:00, 해당 월의
// 1일에") 라 사용자 친화성 부족. 본 프로젝트가 자주 쓰는 매일/매주/매월 패턴은
// 자체 변환으로 자연스러운 한국어 출력. 그 외 패턴은 cronstrue 한국어 fallback.
//
// 사용:
//   import { formatCronToKorean } from '@/utils/cron'
//   formatCronToKorean('0 0 9 * * MON')   // → '매주 월요일 오전 9시'
//   formatCronToKorean('0 0 6 1 * *')     // → '매월 1일 오전 6시'
//   formatCronToKorean(null)               // → '수동 실행'

import cronstrue from 'cronstrue'
import 'cronstrue/locales/ko'

const WEEKDAY_KO: Record<string, string> = {
  MON: '월', TUE: '화', WED: '수', THU: '목', FRI: '금', SAT: '토', SUN: '일',
  '1': '월', '2': '화', '3': '수', '4': '목', '5': '금', '6': '토', '0': '일', '7': '일',
}

/**
 * cron expression 을 한국어 자연어로 변환.
 *
 * @param cron  Spring cron 6 필드 (초 분 시 일 월 요일). null/빈 값 = 수동 실행
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
 * 자체 6 필드 cron 패턴 매칭 — 매일/매주/매월/매시간만 cover.
 * 매칭 안 되면 null 반환 (caller 가 cronstrue fallback).
 */
function tryFormatDirectly(cron: string): string | null {
  const parts = cron.split(/\s+/)
  if (parts.length !== 6) return null

  const [sec, min, hour, day, month, dow] = parts

  // 초가 0 이고 분/시가 단순 숫자가 아니면 cover X (예: "0/15" 같은 step)
  if (sec !== '0') return null
  if (!/^\d+$/.test(min) || !/^\d+$/.test(hour)) return null

  const h = parseInt(hour, 10)
  const m = parseInt(min, 10)
  const timeStr = formatTimeKorean(h, m)

  // daily: 0 M H * * *
  if (day === '*' && month === '*' && dow === '*') {
    return `매일 ${timeStr}`
  }

  // weekly: 0 M H * * MON,FRI (또는 숫자 표현)
  if (day === '*' && month === '*' && dow !== '*') {
    const daysStr = formatWeekdaysKorean(dow)
    if (daysStr == null) return null   // 비표준 dow (range, step 등)
    return `${daysStr} ${timeStr}`
  }

  // monthly: 0 M H N * *
  if (/^\d+$/.test(day) && month === '*' && dow === '*') {
    const d = parseInt(day, 10)
    if (d >= 1 && d <= 31) return `매월 ${d}일 ${timeStr}`
  }

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