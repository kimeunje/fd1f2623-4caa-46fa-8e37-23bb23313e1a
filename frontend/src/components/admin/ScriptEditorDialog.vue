<!--
  v18.8.2 — Python 스크립트 작성/편집 dialog (UID 기반).
  v18.8.7 — 편집 모드에서 [삭제] 버튼 추가 (사용 중 검사 BE 처리).

  사용자 의도: "스크립트 이름은 의미 없다. 내용만." → filename input 제거.
  시스템이 자동 id 부여 + {uuid}.py 파일 저장 (v18.8.3 UUID).

  사용 패턴:
    [신규 작성]
      <ScriptEditorDialog
        v-if="showScriptEditor"
        mode="create"
        @close="showScriptEditor = false"
        @saved="onScriptSaved"
      />

    [기존 수정 — scriptId 전달]
      <ScriptEditorDialog
        v-if="editingScriptId !== null"
        mode="edit"
        :script-id="editingScriptId"
        @close="editingScriptId = null"
        @saved="onScriptSaved"
        @deleted="onScriptDeleted"
      />

  emit:
    @saved   payload = { scriptId: number } — 신규 작성 or 수정 완료
    @deleted payload = { scriptId: number } — v18.8.7 삭제 완료 (편집 모드 한정)
    @close                                  — dialog 닫기
-->
<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { scriptsApi } from '@/services/evidenceApi'
import PythonCodeEditor from '@/components/admin/PythonCodeEditor.vue'
import ScriptVersionPanel from '@/components/admin/ScriptVersionPanel.vue'

const props = defineProps<{
  mode: 'create' | 'edit'
  scriptId?: number   // edit 모드 시 필수
  jobType?: string    // create 모드 시 starter 템플릿 선택 (web_scraping/excel_extract/log_extract)
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', payload: { scriptId: number }): void
  (e: 'deleted', payload: { scriptId: number }): void   // v18.8.7
}>()

const content = ref('')
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)         // v18.8.7
const error = ref<string | null>(null)

const isEdit = computed(() => props.mode === 'edit')

const canSave = computed(() => {
  if (saving.value || deleting.value) return false
  if (!content.value.trim()) return false
  return true
})

const canDelete = computed(() => {
  // v18.8.7 — 편집 모드 + scriptId 있을 때만 활성
  if (!isEdit.value) return false
  if (props.scriptId === undefined || props.scriptId === null) return false
  if (saving.value || deleting.value || loading.value) return false
  return true
})

// UTF-8 byte 수 — BE 의 1MB 제한 검증과 정합
const contentByteSize = computed(() => new TextEncoder().encode(content.value).length)

// ============================================================================
// v18.9.10 — wrapper template 사용 검증 (FE inline)
// ============================================================================
//
// raw 스크립트 (selenium_wrapper.execute_with_diagnosis 미호출) 는 진단 데이터를
// 산출하지 않아 실패 시 단계별 분석 불가. 작성 시점에 경고로 안내.
//
// 검증 룰 (둘 중 한 프레임워크라도 갖추면 통과):
//   A. 신규 secuhub_task — `from secuhub_task import collect_task` + `@collect_task`
//   B. 옛  selenium_wrapper — `import selenium_wrapper` + `execute_with_diagnosis(...)`
// 둘 다 만족하지 않으면 경고. 저장은 허용 (강제 X).
const hasWrapperImport = computed(() =>
  /(^|\n)\s*(from\s+secuhub_task\s+import|import\s+secuhub_task|from\s+selenium_wrapper\s+import|import\s+selenium_wrapper)/.test(content.value),
)
const hasExecuteCall = computed(() =>
  /@collect_task\b/.test(content.value) || /execute_with_diagnosis\s*\(/.test(content.value),
)
const showWrapperWarning = computed(
  () =>
    !loading.value &&
    content.value.trim().length > 0 &&
    !(hasWrapperImport.value && hasExecuteCall.value),
)

const hasAbsolutePath = computed(() => {
  const code = content.value
  const winDrive = /[A-Za-z]:[\\/]/.test(code)                       // C:\ , D:/
  const posixOpen = /open\s*\(\s*[rbfRBF]*['"]\/[^'"\n]+/.test(code) // open("/tmp/x")
  return winDrive || posixOpen
})

const showAbsolutePathWarning = computed(
  () => !loading.value && content.value.trim().length > 0 && hasAbsolutePath.value,
)

// 신규 작성용 starter 템플릿 — 추출 방법(jobType)별로 다른 골격 제공.
// String.raw — Python 코드의 백슬래시를 그대로 보존(이스케이프 불필요).
const STARTER_TEMPLATES: Record<string, string> = {
  // ── 웹 스크래핑 (use_browser=True) ──
  web_scraping: String.raw`"""
{증빙명} 자동 수집 — 웹 스크래핑

사내 시스템 화면에 접속하여 증빙(캡처/추출)을 저장합니다.
프레임워크(secuhub_task)가 driver 생성·옵션·저장 경로·진단·정리를 자동 처리하므로,
main(ctx) 안에 추출 로직만 작성하면 됩니다.
"""
from secuhub_task import collect_task
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC

SITE_URL = "https://사내시스템주소"


@collect_task(
    use_browser=True,
    headless=True,
    chrome_options=["--window-size=1920,1080"],   # 캡처 잘림 방지
)
def main(ctx):
    driver = ctx.driver
    wait = WebDriverWait(driver, 10)

    with ctx.step("로그인 및 화면 이동"):
        driver.get(SITE_URL)
        # ... 로그인 / 대상 화면으로 이동 ...

    with ctx.step("증빙 화면 캡처"):
        # 화면이 다 그려질 때까지 대기 후 캡처
        wait.until(EC.presence_of_element_located((By.ID, "targetArea")))
        # ctx.output 아래에 저장해야 BE 가 자동 수집합니다.
        driver.save_screenshot(str(ctx.output / "접근통제_증빙.png"))
`,

  // ── 엑셀 추출 (use_browser=False) ──
  excel_extract: String.raw`"""
{증빙명} 자동 수집 — 엑셀 추출

브라우저 없이 사내 시스템이 내보낸 엑셀을 파싱하여 필요한 항목만 정리합니다.
- 원본 경로(SOURCE_XLSX)는 사내 공유/내보내기 경로로 교체하세요.
- 결과는 ctx.output 아래에 저장해야 BE 가 자동 수집합니다.
- openpyxl 필요 (폐쇄망은 오프라인 wheel 사전 설치).
"""
from secuhub_task import collect_task
from pathlib import Path
import csv
import openpyxl

SOURCE_XLSX = Path("/data/exports/접근권한_현황.xlsx")   # 원본 엑셀 경로로 교체


@collect_task(use_browser=False)
def main(ctx):
    with ctx.step("엑셀 원본 로드"):
        wb = openpyxl.load_workbook(SOURCE_XLSX, read_only=True, data_only=True)
        ws = wb.active

    with ctx.step("필요 항목 추출"):
        rows = []
        for row in ws.iter_rows(min_row=2, values_only=True):   # 1행 = 헤더로 가정
            if row and row[0] is not None:
                rows.append(row)

    with ctx.step("증빙 파일 생성"):
        out = ctx.output / "접근권한_현황_정리.csv"
        with out.open("w", encoding="utf-8-sig", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["계정", "권한", "부서", "최종접속일"])   # 출력 헤더 예시
            writer.writerows(rows)
`,

  // ── 로그 추출 (use_browser=False, 표준 라이브러리만) ──
  log_extract: String.raw`"""
{증빙명} 자동 수집 — 로그 추출

브라우저 없이 서버/장비 로그를 파싱하여 보안 관련 이벤트만 추려 보고서를 만듭니다.
- 로그 위치(LOG_DIR)와 추출 패턴(PATTERNS)을 환경에 맞게 교체하세요.
- 결과는 ctx.output 아래에 저장해야 BE 가 자동 수집합니다.
- 표준 라이브러리만 사용 (추가 패키지 불필요).
"""
from secuhub_task import collect_task
from pathlib import Path
from datetime import datetime
import re

LOG_DIR = Path("/var/log/secure")                                   # 대상 로그 디렉토리
PATTERNS = [r"Failed password", r"authentication failure", r"sudo:"]  # 추출 패턴


@collect_task(use_browser=False)
def main(ctx):
    regex = re.compile("|".join(PATTERNS))
    matched = []

    with ctx.step("로그 파일 스캔"):
        for log_file in sorted(LOG_DIR.glob("*.log")):
            text = log_file.read_text(encoding="utf-8", errors="ignore")
            for line in text.splitlines():
                if regex.search(line):
                    matched.append(f"{log_file.name}: {line}")

    with ctx.step("증빙 보고서 생성"):
        report = ctx.output / "보안이벤트_추출.txt"
        header = (
            "보안 이벤트 추출 보고서\n"
            f"생성일시: {datetime.now():%Y-%m-%d %H:%M:%S}\n"
            f"대상: {LOG_DIR}  |  매칭: {len(matched)}건\n"
            + "=" * 48 + "\n\n"
        )
        report.write_text(header + "\n".join(matched) + "\n", encoding="utf-8")
`,
}

// jobType → starter. 미지정/미지원이면 web_scraping 으로 fallback.
function starterFor(jobType?: string): string {
  return STARTER_TEMPLATES[jobType ?? 'web_scraping'] ?? STARTER_TEMPLATES.web_scraping
}

onMounted(async () => {
  if (isEdit.value && props.scriptId !== undefined) {
    loading.value = true
    try {
      const { data } = await scriptsApi.getContent(props.scriptId)
      if (data.success) {
        content.value = data.data.content
      } else {
        error.value = '스크립트 내용을 불러오지 못했습니다'
      }
    } catch (e: any) {
      error.value = e?.response?.data?.message ?? e?.message ?? '조회 실패'
    } finally {
      loading.value = false
    }
  } else {
    // 신규 — 선택된 추출 방법(jobType)에 맞는 starter 템플릿 미리 채움
    content.value = starterFor(props.jobType)
  }
})

async function handleSave() {
  if (!canSave.value) return
  saving.value = true
  error.value = null
  try {
    let scriptId: number

    if (isEdit.value && props.scriptId !== undefined) {
      const { data } = await scriptsApi.update(props.scriptId, { content: content.value })
      if (!data.success) throw new Error('수정 실패')
      scriptId = data.data.id
    } else {
      const { data } = await scriptsApi.create({ content: content.value })
      if (!data.success) throw new Error('신규 작성 실패')
      scriptId = data.data.id
    }

    emit('saved', { scriptId })
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? e?.message ?? '저장 실패'
  } finally {
    saving.value = false
  }
}

// v19.5 — 버전 패널에서 되돌리기 성공 시, 새 현재 내용을 에디터에 반영
function onRolledBack(payload: { content: string }) {
  content.value = payload.content
  error.value = null
}

// v18.8.7 — 스크립트 삭제 흐름
async function handleDelete() {
  if (!canDelete.value || props.scriptId === undefined) return
  if (!confirm(
    '이 스크립트를 삭제하시겠습니까?\n\n' +
    '• 물리 파일과 DB 항목이 모두 삭제됩니다.\n' +
    '• 이 스크립트를 사용 중인 수집 작업이 있으면 거부됩니다.\n' +
    '• 옛 작업들은 자동으로 스크립트 연결이 해제됩니다.'
  )) return

  deleting.value = true
  error.value = null
  try {
    await scriptsApi.delete(props.scriptId)
    emit('deleted', { scriptId: props.scriptId })
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? e?.message ?? '삭제 실패'
  } finally {
    deleting.value = false
  }
}

async function handleFileImport(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  // 1MB 제한 (BE 정합)
  if (file.size > 1024 * 1024) {
    error.value = `파일 크기가 너무 큽니다: ${file.size} bytes (최대 1MB)`
    return
  }

  // .py 확장자 검증
  if (!file.name.toLowerCase().endsWith('.py')) {
    error.value = 'Python 스크립트 (.py) 파일만 허용됩니다.'
    return
  }

  try {
    const text = await file.text()
    content.value = text
    error.value = null
  } catch (e: any) {
    error.value = '파일 읽기 실패: ' + (e?.message ?? e)
  } finally {
    // input 초기화 (같은 파일 재선택 가능하도록)
    target.value = ''
  }
}
</script>

<template>
  <div class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" @click.self="emit('close')">
    <div class="bg-white rounded-xl shadow-xl w-full max-w-3xl flex flex-col max-h-[90vh]">
      <!-- Header -->
      <div class="flex items-center justify-between p-4 border-b border-stone-200">
        <h3 class="text-base font-bold text-gray-900">
          {{ isEdit ? '스크립트 수정' : '스크립트 작성' }}
          <span v-if="isEdit" class="ml-2 text-xs font-normal text-gray-400 font-mono">#{{ scriptId }}</span>
        </h3>
        <button @click="emit('close')" class="text-gray-400 hover:text-gray-600" aria-label="닫기">
          <i class="pi pi-times text-base"></i>
        </button>
      </div>

      <!-- Body -->
      <div class="p-4 overflow-y-auto flex-1">
        <!-- 파일 업로드 (신규 작성 모드만) -->
        <div v-if="!isEdit" class="mb-3">
          <label class="block text-sm font-medium text-gray-700 mb-1">
            파일에서 가져오기 <span class="font-normal text-xs text-gray-400">(.py, 최대 1MB, 선택)</span>
          </label>
          <input
            type="file"
            accept=".py"
            @change="handleFileImport"
            class="text-xs file:mr-3 file:px-3 file:py-1.5 file:rounded file:border-0 file:bg-blue-50 file:text-blue-700 file:cursor-pointer hover:file:bg-blue-100"
          />
        </div>

        <!-- 본문 — Python 코드 에디터 (v18.9.9 — CodeMirror 6) -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">
            스크립트 내용 *
            <span class="font-normal text-xs text-gray-400">
              (Python · secuhub_task 의 @collect_task 활용)
            </span>
          </label>

          <!-- v18.9.10 — wrapper template 미사용 inline 경고 -->
          <div
            v-if="showWrapperWarning"
            class="mb-2 px-3 py-2 rounded-lg bg-amber-50 border border-amber-200 flex items-start gap-2">
            <i class="pi pi-exclamation-triangle text-amber-700 text-sm mt-0.5 flex-shrink-0"></i>
            <div class="flex-1 text-xs text-amber-800 leading-relaxed">
              <p>
                <span class="font-medium">수집 프레임워크 미사용</span> — 실패 시 단계별 진단이 기록되지 않습니다.
              </p>
              <p class="mt-1 text-amber-700">
                권장:
                <code class="bg-amber-100 px-1 py-0.5 rounded text-[10px] font-mono">from secuhub_task import collect_task</code>
                후 함수에
                <code class="bg-amber-100 px-1 py-0.5 rounded text-[10px] font-mono">@collect_task(...)</code>
                적용.
              </p>
            </div>
          </div>
          
          <!-- 절대경로 사용 경고 (수집 누락 사전 방지) -->
          <div
            v-if="showAbsolutePathWarning"
            class="mb-2 px-3 py-2 rounded-lg bg-orange-50 border border-orange-200 flex items-start gap-2">
            <i class="pi pi-exclamation-circle text-orange-700 text-sm mt-0.5 flex-shrink-0"></i>
            <div class="flex-1 text-xs text-orange-800 leading-relaxed">
              <p>
                <span class="font-medium">절대경로 감지</span> —
                <code class="bg-orange-100 px-1 py-0.5 rounded text-[10px] font-mono">/tmp/...</code> 나
                <code class="bg-orange-100 px-1 py-0.5 rounded text-[10px] font-mono">C:\...</code> 같은 절대경로는
                수집 폴더 밖이라 증빙이 수집되지 않을 수 있습니다.
              </p>
              <p class="mt-1 text-orange-700">
                상대경로 (<code class="bg-orange-100 px-1 py-0.5 rounded text-[10px] font-mono">"result.csv"</code>) 나
                <code class="bg-orange-100 px-1 py-0.5 rounded text-[10px] font-mono">ctx.output</code> 을 사용하세요.
              </p>
            </div>
          </div>

          <PythonCodeEditor
            v-model="content"
            :disabled="loading"
            :height="480"
          />
          <p v-if="loading" class="mt-1 text-xs text-gray-400">
            <i class="pi pi-spin pi-spinner mr-1"></i>스크립트 내용 로딩 중...
          </p>
          <p v-else class="mt-1 text-xs text-gray-400">
            크기: {{ contentByteSize.toLocaleString() }} bytes (최대 1,048,576)
          </p>
        </div>

        <!-- v19.5 — 버전 이력 패널 (편집 모드 한정) -->
        <ScriptVersionPanel
          v-if="isEdit && scriptId !== undefined"
          :script-id="scriptId"
          class="mt-3"
          @rolledback="onRolledBack"
        />

        <!-- 에러 표시 -->
        <p v-if="error" class="mt-3 text-xs text-red-600 bg-red-50 p-2 rounded whitespace-pre-wrap">
          {{ error }}
        </p>
      </div>

      <!--
        Footer
        v18.8.7 — 편집 모드 한정 좌측에 [삭제] 버튼.
        flex 의 좌-우 분리 : justify-between + 좌측 그룹 + 우측 그룹.
      -->
      <div class="flex items-center justify-between gap-2 p-4 border-t border-stone-200">
        <!-- 좌측: 편집 모드만 [삭제] -->
        <div>
          <button v-if="isEdit" @click="handleDelete" :disabled="!canDelete"
            class="px-4 py-2 text-sm bg-white border border-red-300 text-red-700 rounded-lg hover:bg-red-50 disabled:opacity-40 inline-flex items-center gap-1.5">
            <i v-if="deleting" class="pi pi-spin pi-spinner text-xs"></i>
            <i v-else class="pi pi-trash text-xs"></i>
            {{ deleting ? '삭제 중...' : '삭제' }}
          </button>
        </div>

        <!-- 우측: 취소 + 저장 -->
        <div class="flex gap-2">
          <button @click="emit('close')"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">
            취소
          </button>
          <button @click="handleSave" :disabled="!canSave"
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-40 inline-flex items-center gap-1.5">
            <i v-if="saving" class="pi pi-spin pi-spinner text-xs"></i>
            <i v-else class="pi pi-save text-xs"></i>
            {{ isEdit ? '수정 저장' : '신규 등록' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>