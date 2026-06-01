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

const props = defineProps<{
  mode: 'create' | 'edit'
  scriptId?: number   // edit 모드 시 필수
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
// raw 스크립트 (SecuHub 프레임워크 미호출) 는 진단 데이터를 산출하지 않아
// 실패 시 단계별 분석 불가. 작성 시점에 경고로 안내.
//
// v18.9.12 — 검증 룰 확장:
//   1. import 룰 — `from secuhub_task import ...` (신규, 권장) 또는
//                  `from selenium_wrapper import ...` (옛, 호환)
//   2. entry 룰 — `@collect_task(...)` (신규) 또는 `execute_with_diagnosis(...)` (옛)
//
// 한쪽 패턴 (옛/새) 만 완성되면 통과. 저장은 허용 (강제 X).
const hasSecuhubImport = computed(() =>
  /(^|\n)\s*(from\s+(secuhub_task|selenium_wrapper)\s+import|import\s+(secuhub_task|selenium_wrapper))/.test(content.value),
)
const hasSecuhubEntry = computed(() =>
  /@collect_task\s*\(|execute_with_diagnosis\s*\(/.test(content.value),
)
const showWrapperWarning = computed(
  () =>
    !loading.value &&
    content.value.trim().length > 0 &&
    !(hasSecuhubImport.value && hasSecuhubEntry.value),
)

// ============================================================================
// 신규 작성용 starter (v18.9.12 — @collect_task 패턴)
// ============================================================================
// 프레임워크 (secuhub_task) 가 driver 생성·옵션·저장 경로·진단·cleanup 흡수.
// 사용자는 옵션 선언 (선택) + main(ctx) 본문 (필수) 만 작성.
const PLACEHOLDER_CONTENT = `"""
{시나리오 이름} — SecuHub Collection Task

driver 생성·옵션·저장 경로·진단·cleanup 은 프레임워크가 처리합니다.
사용자는 @collect_task 옵션 선언 (선택) 과 main(ctx) 본문만 작성하면 됩니다.
"""
from secuhub_task import collect_task


@collect_task(
    use_browser=True,         # 웹 스크래핑 = True. 엑셀/로그 추출 = False
    headless=True,            # 운영 = Linux 서버 (화면 없음). 로컬 디버깅은 False
    # chrome_options=["--window-size=1920,1080"],
    # chrome_prefs={"profile.default_content_settings.popups": 0},
)
def main(ctx):
    """
    ctx.driver  : 준비된 webdriver (옵션·다운로드 경로 자동 적용)
    ctx.output  : 결과 저장 디렉토리 (여기 저장하면 자동 수집)
    ctx.step    : 단계별 진단 (시작·종료·예외 자동 기록)
    """
    with ctx.step("페이지 접속"):
        ctx.driver.get("https://example.com")  # TODO: 대상 URL

    with ctx.step("로그인"):
        # TODO: 자격증명 입력
        pass

    with ctx.step("데이터 추출 및 저장"):
        # TODO: 추출 로직
        # 저장 예: (ctx.output / "result.csv").write_text("...", encoding="utf-8")
        pass
`

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
    // 신규 — placeholder content 미리 채움 (관리자가 부분만 수정)
    content.value = PLACEHOLDER_CONTENT
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
              (Python · selenium_wrapper.py 의 execute_with_diagnosis 활용)
            </span>
          </label>

          <!-- v18.9.10 — wrapper template 미사용 inline 경고
               v18.9.12 — 권장 패턴을 @collect_task 로 변경 (옛 execute_with_diagnosis 도 호환) -->
          <div
            v-if="showWrapperWarning"
            class="mb-2 px-3 py-2 rounded-lg bg-amber-50 border border-amber-200 flex items-start gap-2">
            <i class="pi pi-exclamation-triangle text-amber-700 text-sm mt-0.5 flex-shrink-0"></i>
            <div class="flex-1 text-xs text-amber-800 leading-relaxed">
              <p>
                <span class="font-medium">SecuHub 프레임워크 미사용</span> — 실패 시 단계별 진단이 기록되지 않습니다.
              </p>
              <p class="mt-1 text-amber-700">
                권장:
                <code class="bg-amber-100 px-1 py-0.5 rounded text-[10px] font-mono">from secuhub_task import collect_task</code>
                사용 후
                <code class="bg-amber-100 px-1 py-0.5 rounded text-[10px] font-mono">@collect_task(...)</code>
                데코레이터.
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