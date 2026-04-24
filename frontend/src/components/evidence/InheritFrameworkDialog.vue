<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

/**
 * Framework 상속 생성 다이얼로그 (v11 Phase 5-6).
 *
 * 사용 위치:
 *  - FrameworkListView — 우측 상단 [상속하여 생성] 버튼
 *  - ControlsView (Framework 상세) — 상단 드롭다운 "상속하여 새로 만들기"
 */
const props = defineProps<{
  open: boolean
  /** 초기 선택할 원본 Framework ID. ControlsView 에서 현재 Framework 를 기본값으로 넘길 때 사용. */
  initialSourceId?: number | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'created': [framework: Framework]
  'error': [message: string]
}>()

const loadingFrameworks = ref(false)
const frameworks = ref<Framework[]>([])
const showArchived = ref(false)

const form = ref({
  sourceFrameworkId: null as number | null,
  name: '',
  description: '',
})

const submitting = ref(false)

const availableFrameworks = computed(() => {
  if (showArchived.value) return frameworks.value
  return frameworks.value.filter(f => f.status !== 'archived')
})

const selectedSource = computed<Framework | null>(() => {
  if (form.value.sourceFrameworkId == null) return null
  return frameworks.value.find(f => f.id === form.value.sourceFrameworkId) ?? null
})

const canSubmit = computed(() => {
  return !submitting.value
    && form.value.sourceFrameworkId != null
    && form.value.name.trim().length > 0
})

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    await loadFrameworks()
    if (props.initialSourceId != null) {
      form.value.sourceFrameworkId = props.initialSourceId
    }
  } else {
    form.value = { sourceFrameworkId: null, name: '', description: '' }
    showArchived.value = false
  }
})

async function loadFrameworks() {
  loadingFrameworks.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
    }
  } catch (e) {
    console.error('Framework 목록 조회 실패:', e)
    emit('error', 'Framework 목록을 불러오지 못했습니다.')
  } finally {
    loadingFrameworks.value = false
  }
}

function close() {
  emit('update:open', false)
}

async function handleSubmit() {
  if (!canSubmit.value || form.value.sourceFrameworkId == null) return
  submitting.value = true
  try {
    const { data } = await frameworksApi.inherit({
      sourceFrameworkId: form.value.sourceFrameworkId,
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
    })
    if (data.success) {
      emit('created', data.data)
      close()
    } else {
      emit('error', data.message ?? '상속 생성에 실패했습니다.')
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message ?? '상속 생성에 실패했습니다. 잠시 후 다시 시도해주세요.'
    emit('error', msg)
  } finally {
    submitting.value = false
  }
}

function onSourceChange() {
  if (selectedSource.value && !form.value.name.trim()) {
    form.value.name = `${selectedSource.value.name} (복제)`
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open"
      class="fixed inset-0 bg-black/40 flex items-center justify-center z-[60]"
      @click.self="close">
      <div class="bg-white rounded-xl shadow-xl w-full max-w-lg p-6">
        <!-- 헤더 -->
        <div class="flex items-center justify-between mb-4">
          <div>
            <h3 class="text-lg font-bold text-gray-900 flex items-center gap-2">
              <i class="pi pi-sitemap text-blue-600 text-base"></i>
              기존 Framework 상속하여 생성
            </h3>
            <p class="text-xs text-gray-500 mt-1">
              원본의 통제 항목·증빙 유형·수집 작업을 그대로 복제합니다.
            </p>
          </div>
          <button @click="close" class="p-1 text-gray-400 hover:text-gray-600">
            <i class="pi pi-times"></i>
          </button>
        </div>

        <!-- 폼 -->
        <div class="space-y-4">
          <!-- 원본 선택 -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              원본 Framework <span class="text-red-500">*</span>
            </label>
            <select
              v-model="form.sourceFrameworkId"
              @change="onSourceChange"
              :disabled="loadingFrameworks"
              class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none disabled:bg-gray-50">
              <option :value="null">
                {{ loadingFrameworks ? '불러오는 중...' : '-- 상속받을 Framework 선택 --' }}
              </option>
              <option
                v-for="fw in availableFrameworks"
                :key="fw.id"
                :value="fw.id">
                {{ fw.name }}
                <template v-if="fw.status === 'archived'"> [종료]</template>
                · 통제 {{ fw.controlCount }} · 증빙 {{ fw.evidenceTypeCount ?? 0 }} · 작업 {{ fw.jobCount ?? 0 }}
              </option>
            </select>
            <label class="flex items-center gap-1.5 mt-2 text-xs text-gray-500 cursor-pointer">
              <input
                type="checkbox"
                v-model="showArchived"
                class="rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
              종료된 Framework 도 표시
            </label>
          </div>

          <!-- 새 Framework 이름 -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              새 Framework 이름 <span class="text-red-500">*</span>
            </label>
            <input
              v-model="form.name"
              type="text"
              placeholder="예: ISMS-P 2027"
              class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
          </div>

          <!-- 설명 (선택) -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              설명 <span class="text-gray-400 font-normal">(선택)</span>
            </label>
            <textarea
              v-model="form.description"
              rows="2"
              placeholder="간단한 설명"
              class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none resize-none"></textarea>
          </div>

          <!-- 복제 대상 안내 -->
          <div class="bg-blue-50 border border-blue-100 rounded-lg p-3 space-y-1.5 text-xs">
            <div class="font-medium text-blue-900 flex items-center gap-1.5 mb-1">
              <i class="pi pi-info-circle text-blue-600 text-[11px]"></i>
              복제 대상
            </div>
            <div class="flex items-center gap-1.5 text-blue-800">
              <i class="pi pi-check text-green-600 text-[10px]"></i>
              통제 항목 전체
            </div>
            <div class="flex items-center gap-1.5 text-blue-800">
              <i class="pi pi-check text-green-600 text-[10px]"></i>
              증빙 유형 전체 (담당자 · 마감일 포함)
            </div>
            <div class="flex items-center gap-1.5 text-blue-800">
              <i class="pi pi-check text-green-600 text-[10px]"></i>
              수집 작업 (새 증빙 유형에 재연결, 활성 상태 유지)
            </div>
            <div class="flex items-center gap-1.5 text-gray-500">
              <i class="pi pi-times text-gray-400 text-[10px]"></i>
              파일 / 실행 이력은 복제 안 함 (빈 상태로 시작)
            </div>
            <p class="text-[11px] text-blue-700 mt-2 pt-2 border-t border-blue-100">
              상속 시점의 스냅샷으로 복제됩니다. 이후 원본/복제본은 독립적으로 관리됩니다.
            </p>
          </div>
        </div>

        <!-- 액션 -->
        <div class="flex justify-end gap-2 mt-5">
          <button
            @click="close"
            class="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-lg">
            취소
          </button>
          <button
            @click="handleSubmit"
            :disabled="!canSubmit"
            class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-1.5">
            <i v-if="submitting" class="pi pi-spin pi-spinner text-xs"></i>
            <i v-else class="pi pi-sitemap text-xs"></i>
            {{ submitting ? '생성 중...' : '상속하여 생성' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>