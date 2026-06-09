<script setup lang="ts">
/**
 * v19.5 (FE-1) — 스크립트 버전 이력 패널.
 *
 * ScriptEditorDialog 의 편집 모드에서 사용. 주어진 scriptId 의 버전 이력을 목록·미리보기·
 * 되돌리기 한다. 되돌리기(전진형 롤백)는 BE 가 옛 내용으로 새 버전을 만들고 실행본을
 * 교체하므로, 성공 시 새 현재 내용을 부모(에디터)로 emit 하여 편집창을 갱신한다.
 *
 * 자족적 — scriptsApi 만 사용하고 부모 상태에 의존하지 않는다.
 */
import { ref, computed, onMounted } from 'vue'
import { scriptsApi } from '@/services/evidenceApi'
import type { ScriptVersionResponse } from '@/services/evidenceApi'

const props = defineProps<{
  scriptId: number
  /** true 면 토글 없이 바로 펼쳐 렌더 (모달 안에서 사용). */
  flat?: boolean
}>()

const emit = defineEmits<{
  (e: 'rolledback', payload: { content: string }): void
}>()

const expanded = ref(false)
const loaded = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const versions = ref<ScriptVersionResponse[]>([])

const previewVersionNo = ref<number | null>(null)
const previewContent = ref('')
const previewLoading = ref(false)
const rollbackBusy = ref(false)

const latestVersionNo = computed(() =>
  versions.value.reduce((m, v) => Math.max(m, v.versionNo), 0),
)

function fmt(iso?: string): string {
  return iso ? new Date(iso).toLocaleString('ko') : '-'
}

async function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !loaded.value) {
    await load()
  }
}

onMounted(() => {
  if (props.flat) {
    expanded.value = true
    load()
  }
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const { data } = await scriptsApi.listVersions(props.scriptId)
    versions.value = (data.data ?? []).slice().sort((a, b) => b.versionNo - a.versionNo)
    loaded.value = true
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? e?.message ?? '버전 이력을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function preview(versionNo: number) {
  if (previewVersionNo.value === versionNo) {
    // 토글로 닫기
    previewVersionNo.value = null
    previewContent.value = ''
    return
  }
  previewLoading.value = true
  error.value = null
  try {
    const { data } = await scriptsApi.getVersion(props.scriptId, versionNo)
    previewContent.value = data.data.content
    previewVersionNo.value = versionNo
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? e?.message ?? '버전 내용을 불러오지 못했습니다.'
  } finally {
    previewLoading.value = false
  }
}

async function rollback(versionNo: number) {
  if (!window.confirm(
    `v${versionNo} 내용으로 되돌릴까요?\n\n` +
    '현재 내용을 덮어쓰지 않고, v' + versionNo + ' 내용을 복사한 새 버전이 만들어집니다. ' +
    '(이력은 그대로 보존됩니다.)'
  )) return

  rollbackBusy.value = true
  error.value = null
  try {
    const { data } = await scriptsApi.rollback(props.scriptId, versionNo)
    // 부모 에디터에 새 현재 내용 반영
    emit('rolledback', { content: data.data.content })
    previewVersionNo.value = null
    previewContent.value = ''
    await load() // 새 버전 반영된 목록 갱신
  } catch (e: any) {
    error.value = e?.response?.data?.message ?? e?.message ?? '되돌리기에 실패했습니다.'
  } finally {
    rollbackBusy.value = false
  }
}
</script>

<template>
  <div :class="flat ? '' : 'border border-gray-200 rounded-lg'">
    <!-- header (인라인 모드에서만 토글) -->
    <button
      v-if="!flat"
      type="button"
      class="w-full flex items-center justify-between px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
      @click="toggle"
    >
      <span class="flex items-center gap-2">
        <i class="pi" :class="expanded ? 'pi-chevron-down' : 'pi-chevron-right'"></i>
        버전 이력
        <span v-if="loaded" class="text-xs text-gray-400">({{ versions.length }})</span>
      </span>
      <i v-if="loading" class="pi pi-spin pi-spinner text-gray-400"></i>
    </button>

    <div
      v-if="flat || expanded"
      :class="flat ? 'space-y-3' : 'border-t border-gray-200 px-3 py-3 space-y-3'"
    >
      <div v-if="flat && loading" class="text-center text-gray-400 py-4">
        <i class="pi pi-spin pi-spinner"></i>
      </div>
      <div
        v-if="error"
        class="flex items-start gap-2 text-xs text-red-700 bg-red-50 border border-red-200 rounded-md px-2 py-1.5"
      >
        <i class="pi pi-times-circle mt-0.5"></i>
        <span>{{ error }}</span>
      </div>

      <div v-if="loaded && versions.length === 0" class="text-xs text-gray-400 py-2">
        버전 이력이 없습니다.
      </div>

      <ul v-else class="space-y-1.5">
        <li
          v-for="v in versions"
          :key="v.versionNo"
          class="border border-gray-100 rounded-md"
        >
          <div class="flex items-center gap-2 px-2 py-1.5">
            <span class="text-sm font-medium text-gray-800">v{{ v.versionNo }}</span>
            <span
              v-if="v.versionNo === latestVersionNo"
              class="text-[10px] px-1.5 py-0.5 bg-green-50 text-green-700 rounded"
            >현재</span>
            <span class="text-xs text-gray-400">{{ fmt(v.createdAt) }}</span>
            <span v-if="v.note" class="text-xs text-gray-500 truncate">· {{ v.note }}</span>
            <span class="flex-1"></span>
            <button
              type="button"
              class="text-xs px-2 py-0.5 rounded border border-gray-200 text-gray-600 hover:bg-gray-50"
              @click="preview(v.versionNo)"
            >
              {{ previewVersionNo === v.versionNo ? '닫기' : '미리보기' }}
            </button>
            <button
              type="button"
              class="text-xs px-2 py-0.5 rounded border border-blue-200 text-blue-600 hover:bg-blue-50 disabled:opacity-40"
              :disabled="rollbackBusy || v.versionNo === latestVersionNo"
              :title="v.versionNo === latestVersionNo ? '이미 현재 버전입니다' : ''"
              @click="rollback(v.versionNo)"
            >
              되돌리기
            </button>
          </div>

          <!-- preview -->
          <div v-if="previewVersionNo === v.versionNo" class="px-2 pb-2">
            <div v-if="previewLoading" class="text-xs text-gray-400 py-2">
              <i class="pi pi-spin pi-spinner"></i> 불러오는 중…
            </div>
            <pre v-else class="text-[11px] bg-gray-900 text-gray-100 rounded-md p-2 overflow-auto max-h-60 whitespace-pre">{{ previewContent }}</pre>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
