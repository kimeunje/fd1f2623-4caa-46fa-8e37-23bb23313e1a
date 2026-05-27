<!--
  v18.9.7 — Cron 친화 UI 빌더

  본질: cron expression (0 0 9 * * MON) 직접 입력은 일반 관리자 해독 불가.
  Hybrid 패턴 = dropdown 빌더 (매일/매주/매월) + 직접 입력 + cronstrue 한국어
  자연어 미리보기.

  ## 사용 패턴
  ```vue
  <CronBuilder v-model="job.scheduleCron" />
  ```

  ## Spring cron 6 필드 정합
  본 프로젝트 SchedulerService 가 Spring @Scheduled cron 사용 (6 필드: 초 분 시 일 월 요일).
  빌더 출력은 표준 cron (? 안 씀, * 사용):
   - daily   → `0 MM HH * * *`
   - weekly  → `0 MM HH * * MON,FRI`
   - monthly → `0 MM HH N * *`
   - custom  → 사용자 직접 입력 (? 포함 가능)

  ## 양방향 parse
  외부 modelValue (cron) 변경 시 parse → mode + state 복원. 매칭 안 되면
  custom 모드 자동 fallback.
-->
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { formatCronToKorean } from '@/utils/cron'

const props = defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', cron: string): void
}>()

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────
type Mode = 'daily' | 'weekly' | 'monthly' | 'custom'

const mode = ref<Mode>('daily')
const hour = ref<number>(9)
const minute = ref<number>(0)
const weekdays = ref<string[]>(['MON'])
const monthDay = ref<number>(1)
const customCron = ref<string>('')

const WEEKDAY_OPTIONS = [
  { key: 'MON', label: '월' },
  { key: 'TUE', label: '화' },
  { key: 'WED', label: '수' },
  { key: 'THU', label: '목' },
  { key: 'FRI', label: '금' },
  { key: 'SAT', label: '토' },
  { key: 'SUN', label: '일' },
] as const

// ─────────────────────────────────────────────
// cron 생성 (mode → cron string)
// ─────────────────────────────────────────────
function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function buildCron(): string {
  const mm = minute.value
  const hh = hour.value
  switch (mode.value) {
    case 'daily':
      return `0 ${mm} ${hh} * * *`
    case 'weekly': {
      const days = weekdays.value.length > 0 ? weekdays.value.join(',') : 'MON'
      return `0 ${mm} ${hh} * * ${days}`
    }
    case 'monthly':
      return `0 ${mm} ${hh} ${monthDay.value} * *`
    case 'custom':
      return customCron.value
  }
}

// ─────────────────────────────────────────────
// cron parse (cron string → mode + state)
//
// 매칭 우선순위: daily / weekly / monthly / custom
// 매칭 안 되거나 비표준 패턴은 custom 모드로 fallback.
// ─────────────────────────────────────────────
const DAILY_RE = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\*$/
const WEEKLY_RE = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+([A-Z,]+)$/
const MONTHLY_RE = /^0\s+(\d{1,2})\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*$/
const VALID_DAYS = new Set(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'])

function parseCron(cron: string): void {
  if (!cron || !cron.trim()) {
    // 빈 값 → daily 기본값 유지
    mode.value = 'daily'
    return
  }

  const trimmed = cron.trim()

  // daily
  const dailyMatch = DAILY_RE.exec(trimmed)
  if (dailyMatch) {
    mode.value = 'daily'
    minute.value = Number(dailyMatch[1])
    hour.value = Number(dailyMatch[2])
    return
  }

  // weekly
  const weeklyMatch = WEEKLY_RE.exec(trimmed)
  if (weeklyMatch) {
    const days = weeklyMatch[3].split(',').filter((d) => VALID_DAYS.has(d))
    if (days.length > 0) {
      mode.value = 'weekly'
      minute.value = Number(weeklyMatch[1])
      hour.value = Number(weeklyMatch[2])
      weekdays.value = days
      return
    }
  }

  // monthly
  const monthlyMatch = MONTHLY_RE.exec(trimmed)
  if (monthlyMatch) {
    const day = Number(monthlyMatch[3])
    if (day >= 1 && day <= 31) {
      mode.value = 'monthly'
      minute.value = Number(monthlyMatch[1])
      hour.value = Number(monthlyMatch[2])
      monthDay.value = day
      return
    }
  }

  // custom fallback
  mode.value = 'custom'
  customCron.value = trimmed
}

// ─────────────────────────────────────────────
// 자연어 미리보기 (cronstrue 한국어)
// ─────────────────────────────────────────────
const currentCron = computed(() => buildCron())

const previewText = computed(() => {
  const cron = currentCron.value
  if (!cron || !cron.trim()) return ''
  const result = formatCronToKorean(cron)
  // cron 원본과 같으면 parse 실패한 케이스 (formatCronToKorean 의 마지막 fallback)
  if (result === cron) return '⚠ cron expression 을 해석할 수 없습니다'
  return result
})

const previewValid = computed(() => !previewText.value.startsWith('⚠'))

// ─────────────────────────────────────────────
// emit — state 변경 시 부모로 cron 전달
// ─────────────────────────────────────────────
watch(
  [mode, hour, minute, weekdays, monthDay, customCron],
  () => {
    emit('update:modelValue', buildCron())
  },
  { deep: true },
)

// ─────────────────────────────────────────────
// 외부 modelValue 변경 시 parse (initial mount + 외부 set)
// ─────────────────────────────────────────────
watch(
  () => props.modelValue,
  (newVal) => {
    // 본 컴포넌트가 emit 한 값이면 parse 안 함 (무한 루프 회피)
    if (newVal === buildCron()) return
    parseCron(newVal)
  },
  { immediate: true },
)

// ─────────────────────────────────────────────
// 요일 toggle
// ─────────────────────────────────────────────
function toggleWeekday(key: string) {
  const idx = weekdays.value.indexOf(key)
  if (idx >= 0) {
    if (weekdays.value.length > 1) {
      weekdays.value.splice(idx, 1)
    }
    // 최소 1개 요일 유지 (전부 해제 방지)
  } else {
    // 요일 정렬 (월~일 순)
    weekdays.value = WEEKDAY_OPTIONS
      .filter((opt) => opt.key === key || weekdays.value.includes(opt.key))
      .map((opt) => opt.key)
  }
}
</script>

<template>
  <div class="space-y-2.5">
    <!-- 1행: 빈도 + 시간 -->
    <div class="grid gap-2"
      :class="mode === 'monthly' ? 'grid-cols-3' : (mode === 'custom' ? 'grid-cols-1' : 'grid-cols-2')">
      <!-- 빈도 -->
      <select v-model="mode" class="px-3 py-2 border rounded-lg text-sm bg-white">
        <option value="daily">매일</option>
        <option value="weekly">매주</option>
        <option value="monthly">매월</option>
        <option value="custom">직접 입력 (cron)</option>
      </select>

      <!-- 매월 — 일자 -->
      <select v-if="mode === 'monthly'" v-model.number="monthDay"
        class="px-3 py-2 border rounded-lg text-sm bg-white">
        <option v-for="d in 31" :key="d" :value="d">{{ d }}일</option>
      </select>

      <!-- 시간 (HH:MM) — daily/weekly/monthly 공통 -->
      <div v-if="mode !== 'custom'" class="flex items-center gap-1 px-3 py-2 border rounded-lg text-sm bg-white">
        <select v-model.number="hour" class="bg-transparent outline-none border-0 font-mono">
          <option v-for="h in 24" :key="h" :value="h - 1">{{ pad(h - 1) }}</option>
        </select>
        <span class="text-gray-400">:</span>
        <select v-model.number="minute" class="bg-transparent outline-none border-0 font-mono">
          <option v-for="m in 60" :key="m" :value="m - 1">{{ pad(m - 1) }}</option>
        </select>
      </div>
    </div>

    <!-- 매주 — 요일 picker -->
    <div v-if="mode === 'weekly'" class="flex gap-1.5">
      <button
        v-for="opt in WEEKDAY_OPTIONS"
        :key="opt.key"
        type="button"
        @click="toggleWeekday(opt.key)"
        class="px-3 py-1 text-xs rounded-md border transition-colors"
        :class="weekdays.includes(opt.key)
          ? 'bg-blue-600 text-white border-blue-600'
          : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'">
        {{ opt.label }}
      </button>
    </div>

    <!-- 직접 입력 — cron text input -->
    <input
      v-if="mode === 'custom'"
      v-model="customCron"
      class="w-full px-3 py-2 border rounded-lg text-sm font-mono"
      placeholder="0 0 9 * * MON,FRI" />

    <!-- 미리보기 -->
    <div v-if="previewText"
      class="px-3 py-2 rounded-lg border text-xs flex items-start gap-2"
      :class="previewValid
        ? 'bg-blue-50 border-blue-200 text-blue-800'
        : 'bg-amber-50 border-amber-200 text-amber-800'">
      <i class="pi text-[13px] mt-0.5" :class="previewValid ? 'pi-info-circle' : 'pi-exclamation-triangle'"></i>
      <div class="flex-1">
        <div class="font-medium">{{ previewText }}</div>
        <div v-if="previewValid" class="text-[10px] mt-0.5 font-mono opacity-70">
          {{ currentCron }}
        </div>
      </div>
    </div>
  </div>
</template>