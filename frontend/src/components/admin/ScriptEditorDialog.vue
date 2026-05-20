<!--
  v18.8.2 — Python 스크립트 작성/편집 dialog (UID 기반).

  사용자 의도: "스크립트 이름은 의미 없다. 내용만." → filename input 제거.
  시스템이 자동 id 부여 + {id}.py 파일 저장.

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
      />

  @saved event payload = { scriptId: number }
-->
<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { scriptsApi } from '@/services/evidenceApi'

const props = defineProps<{
  mode: 'create' | 'edit'
  scriptId?: number   // edit 모드 시 필수
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', payload: { scriptId: number }): void
}>()

const content = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)

const isEdit = computed(() => props.mode === 'edit')

const canSave = computed(() => {
  if (saving.value) return false
  if (!content.value.trim()) return false
  return true
})

// UTF-8 byte 수 — BE 의 1MB 제한 검증과 정합
const contentByteSize = computed(() => new TextEncoder().encode(content.value).length)

// 신규 작성용 placeholder content (사용자 작성 가이드)
const PLACEHOLDER_CONTENT = `"""
{시나리오 이름}

SecuHub 자동 수집 — selenium_wrapper.py 활용.
"""
import sys
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "templates"))

from selenium_wrapper import execute_with_diagnosis, step
from selenium.webdriver.common.by import By


def scenario(driver, output_dir):
    with step("open https://example.com"):
        driver.get("https://example.com")

    with step("extract h1"):
        h1 = driver.find_element(By.TAG_NAME, "h1").text

    with step("write output"):
        (output_dir / "result.txt").write_text(f"h1: {h1}\\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(execute_with_diagnosis(scenario))
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

        <!-- 본문 — textarea -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">
            스크립트 내용 *
            <span class="font-normal text-xs text-gray-400">
              (Python · selenium_wrapper.py 의 execute_with_diagnosis 활용)
            </span>
          </label>
          <textarea
            v-model="content"
            :disabled="loading"
            class="w-full px-3 py-2 border rounded-lg text-xs font-mono leading-relaxed"
            :class="{ 'bg-gray-50 text-gray-400': loading }"
            :rows="20"
            spellcheck="false"
          ></textarea>
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

      <!-- Footer -->
      <div class="flex justify-end gap-2 p-4 border-t border-stone-200">
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
</template>