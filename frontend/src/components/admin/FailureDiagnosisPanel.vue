<!--
  v18.7 — 자동 수집 실패 진단 패널 (mockup 정합 in-page 풀폭 패널)

  사용자 mockup (v18_7_failure_diagnosis_layout.html) 의 화면 2 정합.

  사용 패턴 (JobsView 안에서 풀폭 swap):

      <FailureDiagnosisPanel
        v-if="selectedExecution !== null"
        :execution="selectedExecution"
        :job-name="selectedJob?.name"
        @close="selectedExecution = null"
      />

  스타일: mockup 의 CSS 변수 색상값을 TailwindCSS 색상으로 매핑
  (#fef2f2 → red-50, #b91c1c → red-700 등). 시각 결과 정합.
-->
<script setup lang="ts">
import { computed } from 'vue'
import { jobsApi, parseDiagnosis } from '@/services/evidenceApi'
import type { ExecutionSummary, DiagnosisJson, DiagnosisStep } from '@/types/evidence'
import ActionGuideStrip from './ActionGuideStrip.vue'

const props = defineProps<{
  execution: ExecutionSummary
  jobName?: string   // mockup 의 부제 — "정보자산 목록 수집 · 1.4초 만에 중단 · 5/7 단계"
  scriptId?: number  // v18.8.2 — 진단 패널의 "수정 스크립트 업로드" 버튼 활성화 (UID 기반)
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'upload-script', scriptId: number): void   // v18.8.2 — JobsView 가 ScriptEditorDialog 열기
}>()

const diagnosis = computed<DiagnosisJson | null>(() =>
  parseDiagnosis(props.execution.errorDiagnosis),
)

const failedStep = computed<DiagnosisStep | null>(() =>
  diagnosis.value?.steps.find((s) => s.status === 'failed') ?? null,
)

const stepsProgress = computed(() => {
  if (!diagnosis.value) return ''
  const total = diagnosis.value.scenario.total_steps
  const ran = diagnosis.value.steps.filter((s) => s.status !== 'not_run').length
  return `${ran}/${total} 단계`
})

const durationLabel = computed(() => {
  if (!diagnosis.value) return ''
  const sec = diagnosis.value.execution.duration_sec.toFixed(1)
  // v18.8 — 성공/실패 분기. 성공도 진단 패널 진입 가능.
  return diagnosis.value.execution.status === 'success'
    ? `${sec}초 만에 완료`
    : `${sec}초 만에 중단`
})

const headerTitle = computed(() => {
  // mockup 정합 — "2026-05-12 06:00 실행 진단" / "실행 상세" (성공)
  if (!props.execution.startedAt) return '실행'
  const d = new Date(props.execution.startedAt)
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  // v18.8 — 성공 = "실행 상세", 실패 = "실행 진단"
  const suffix = props.execution.status === 'success' ? '실행 상세' : '실행 진단'
  return `${yyyy}-${mm}-${dd} ${hh}:${mi} ${suffix}`
})

const screenshotUrl = computed(() => jobsApi.getDiagnosisScreenshotUrl(props.execution.id))
const pageSourceUrl = computed(() => jobsApi.getDiagnosisPageSourceUrl(props.execution.id))

function onDownloadPageSource() { window.open(pageSourceUrl.value, '_blank') }
function onNotifyOwner() { alert('담당자 알림 기능은 v19.0+ EmailService 통합 후 활성화됩니다.') }
function onRerun() { alert('재실행은 작업 목록 화면의 "실행" 버튼을 활용해주세요.') }

// v18.8.2 — 진단 패널 안에서 스크립트 수정 진입. scriptId 가 있어야 동작.
function onUploadScript() {
  if (props.scriptId === undefined || props.scriptId === null) {
    alert('이 작업에는 등록된 스크립트가 없습니다 (legacy scriptPath 작업). 작업 등록 화면에서 신규 스크립트로 전환해주세요.')
    return
  }
  emit('upload-script', props.scriptId)
}
</script>

<template>
  <div class="bg-white border border-stone-300 rounded-lg p-4 max-w-[880px] mx-auto">
    <!-- ────── Header ────── -->
    <div class="flex items-center justify-between pb-2.5 border-b border-stone-300 mb-3">
      <div>
        <div class="flex items-center gap-2">
          <span class="text-sm font-medium">{{ headerTitle }}</span>
          <span v-if="execution.status === 'failed'"
            class="bg-red-50 text-red-700 px-1.5 py-0.5 rounded text-[10px] font-medium">실패</span>
          <span v-else-if="execution.status === 'success'"
            class="bg-green-50 text-green-700 px-1.5 py-0.5 rounded text-[10px] font-medium">성공</span>
        </div>
        <div v-if="diagnosis" class="text-[10px] text-stone-400 mt-1">
          {{ jobName || diagnosis.scenario.name }} · {{ durationLabel }} · {{ stepsProgress }}
        </div>
        <div v-else class="text-[10px] text-stone-400 mt-1">
          {{ jobName || '(작업명 없음)' }}
        </div>
      </div>
      <button @click="emit('close')" class="text-stone-500 hover:text-stone-900" aria-label="닫기">
        <i class="pi pi-times text-base"></i>
      </button>
    </div>

    <!-- ────── Body — 진단 정보 없음 ────── -->
    <div v-if="!diagnosis" class="py-8 text-center text-sm text-stone-500">
      <p>이 실행의 진단 정보가 없습니다.</p>
      <p class="text-xs text-stone-400 mt-2">
        v18.7 이전에 실행되었거나 selenium wrapper 가 _diagnosis.json 을 산출하지 않은 시나리오입니다.
      </p>
      <p v-if="execution.errorMessage"
        class="mt-4 text-xs text-red-600 bg-red-50 p-3 rounded text-left whitespace-pre-wrap break-all">
        {{ execution.errorMessage }}
      </p>
    </div>

    <!-- ────── Body — 진단 정보 표시 (2 컬럼 grid 1.4fr / 1fr) ────── -->
    <div v-else class="grid grid-cols-1 md:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] gap-3.5">

      <!-- ── 좌측: 단계별 진행 ── -->
      <div>
        <div class="text-[10px] text-stone-400 uppercase tracking-wide font-medium mb-1.5">단계별 진행</div>

        <div v-for="step in diagnosis.steps" :key="step.order" class="mb-1">

          <!-- 성공 row -->
          <div v-if="step.status === 'success'"
            class="flex items-center gap-2 px-2.5 py-1.5 bg-stone-100 rounded">
            <i class="pi pi-check text-[13px] text-green-700"></i>
            <span class="text-[10px] text-stone-400 min-w-[12px]">{{ step.order }}</span>
            <div class="flex-1 min-w-0">
              <div class="text-[11px]">
                {{ step.selenium_cmd }}
                <span class="font-mono text-stone-600">{{ step.target }}</span>
              </div>
            </div>
            <span v-if="step.duration_sec !== undefined" class="text-[10px] text-stone-400">
              {{ step.duration_sec.toFixed(1) }}s
            </span>
          </div>

          <!-- 실패 row -->
          <div v-else-if="step.status === 'failed'"
            class="p-2.5 bg-red-50 border border-red-300 rounded">
            <div class="flex items-center gap-2 mb-1.5">
              <i class="pi pi-times-circle text-[15px] text-red-700"></i>
              <span class="text-[10px] text-red-700 font-medium min-w-[12px]">{{ step.order }}</span>
              <div class="flex-1 min-w-0">
                <div class="text-[11px] font-medium text-red-700">
                  {{ step.selenium_cmd }}
                  <span class="font-normal font-mono">{{ step.target }}</span>
                </div>
                <div class="text-[10px] text-red-700 mt-0.5">
                  {{ step.error?.korean_message }}
                  <span v-if="step.duration_sec !== undefined">
                    — {{ step.duration_sec.toFixed(1) }}초 시도 후 실패
                  </span>
                </div>
              </div>
              <span v-if="step.duration_sec !== undefined" class="text-[10px] text-red-700">
                {{ step.duration_sec.toFixed(1) }}s
              </span>
            </div>
            <div v-if="step.error" class="pl-[23px]">
              <span class="inline-block bg-white text-red-700 px-1.5 py-0.5 rounded text-[9px] border border-red-300">
                {{ step.error.exception_class }}
              </span>
            </div>
          </div>

          <!-- 미실행 row -->
          <div v-else
            class="flex items-center gap-2 px-2.5 py-1.5 bg-stone-100 rounded opacity-40">
            <i class="pi pi-minus text-[13px] text-stone-400"></i>
            <span class="text-[10px] text-stone-400 min-w-[12px]">{{ step.order }}</span>
            <div class="flex-1 min-w-0">
              <div class="text-[11px] text-stone-400">
                {{ step.selenium_cmd }}
                <span class="font-mono">{{ step.target }}</span>
                <span class="italic"> — 실행 안 됨</span>
              </div>
            </div>
          </div>

        </div>
      </div>

      <!-- ── 우측: 에러 정보 + 스크린샷 + 추정 원인 (실패) / 실행 결과 (성공) ── -->
      <div>
        <!-- v18.8 — 성공 시 우측 = 단순 안내 -->
        <template v-if="diagnosis.execution.status === 'success'">
          <div class="text-[10px] text-stone-400 uppercase tracking-wide font-medium mb-1.5">
            실행 결과
          </div>
          <div class="bg-green-50 border border-green-200 p-3 rounded">
            <div class="flex items-center gap-1.5 text-green-700 font-medium text-[11px]">
              <i class="pi pi-check-circle text-[14px]"></i>
              모든 단계가 정상 완료되었습니다.
            </div>
            <p class="text-[10px] text-green-700 mt-2 leading-snug">
              총 {{ diagnosis.scenario.total_steps }} 단계, {{ diagnosis.execution.duration_sec.toFixed(1) }}초 소요.
            </p>
            <p class="text-[10px] text-stone-500 mt-2 leading-snug">
              수집된 산출 파일은 작업의 증빙 자료 화면에서 확인할 수 있습니다.
            </p>
          </div>
        </template>

        <!-- 실패 시 — 기존 정보 영역 -->
        <template v-else>
          <!-- 에러 정보 -->
          <template v-if="failedStep?.error">
            <div class="text-[10px] text-stone-400 uppercase tracking-wide font-medium mb-1.5">에러 정보</div>
            <div class="bg-red-50 p-2.5 rounded mb-2.5">
              <div class="text-[10px] text-red-700 mb-1">한국어 해석</div>
              <div class="text-[11px] text-red-700 font-medium leading-snug">
                {{ failedStep.error.korean_message }}
              </div>
              <div class="text-[10px] text-red-700 mt-1.5 font-mono break-all">
                {{ failedStep.error.selector }}
              </div>
            </div>
          </template>

          <!-- 스크린샷 — 실패 시점만 -->
          <div class="text-[10px] text-stone-400 uppercase tracking-wide font-medium mb-1.5">
            실패 시점 스크린샷
          </div>
          <div class="bg-stone-100 rounded p-2 mb-2.5">
            <a :href="screenshotUrl" target="_blank" rel="noopener" class="block">
              <img :src="screenshotUrl" alt="실패 시점 스크린샷"
                class="w-full border border-stone-300 rounded"
                @error="(e: any) => { e.target.style.display = 'none' }" />
            </a>
            <div class="text-[10px] text-stone-400 mt-1 text-center">클릭하여 원본 확대</div>
          </div>

          <!-- 추정 원인 -->
          <template v-if="diagnosis.diagnosis.primary_cause">
            <div class="text-[10px] text-stone-400 uppercase tracking-wide font-medium mb-1.5">추정 원인</div>
            <div class="bg-yellow-50 p-2.5 rounded text-[11px] text-yellow-800 leading-relaxed">
              <i class="pi pi-lightbulb text-[13px] align-text-bottom mr-1"></i>
              {{ diagnosis.diagnosis.primary_cause }}
            </div>
          </template>
        </template>
      </div>
    </div>

    <!-- ────── 조치 가이드 strip (실패 시점만) ────── -->
    <div v-if="diagnosis?.execution.status === 'failed'" class="mt-4">
      <ActionGuideStrip />
    </div>

    <!-- ────── 액션 버튼 4개 (실패 시점만) ────── -->
    <div v-if="diagnosis?.execution.status === 'failed'"
      class="mt-3.5 pt-2.5 border-t border-stone-300 flex justify-end gap-1.5 flex-wrap">
      <button @click="onDownloadPageSource"
        class="px-2.5 py-1.5 text-xs bg-white border border-stone-300 rounded hover:bg-stone-50 inline-flex items-center gap-1">
        <i class="pi pi-download text-[11px]"></i> 페이지 소스 (HTML)
      </button>
      <button @click="onNotifyOwner"
        class="px-2.5 py-1.5 text-xs bg-white border border-stone-300 rounded hover:bg-stone-50 inline-flex items-center gap-1">
        <i class="pi pi-envelope text-[11px]"></i> 담당자에게 알림
      </button>
      <button @click="onRerun"
        class="px-2.5 py-1.5 text-xs bg-white border border-stone-300 rounded hover:bg-stone-50 inline-flex items-center gap-1">
        <i class="pi pi-refresh text-[11px]"></i> 재실행
      </button>
      <button @click="onUploadScript"
        class="px-2.5 py-1.5 text-xs bg-blue-50 text-blue-700 border border-blue-300 rounded hover:bg-blue-100 inline-flex items-center gap-1">
        <i class="pi pi-upload text-[11px]"></i> 수정 스크립트 업로드
      </button>
    </div>
  </div>
</template>