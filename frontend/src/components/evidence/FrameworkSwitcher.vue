<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'
import InheritFrameworkDialog from './InheritFrameworkDialog.vue'

/**
 * Framework 전환 드롭다운 트리거 (v11 Phase 5-6).
 *
 * ControlsView (Framework 상세) 상단 헤더에 위치해 Framework 이름 자체가
 * 드롭다운 트리거 역할을 한다. 기획서 §3.1.2 "Framework 이름 자체가 드롭다운" 원칙 구현.
 *
 * 메뉴 구성:
 *  1. 다른 Framework 전환 (active 목록, 현재 제외)
 *  2. 종료된 Framework 포함 토글
 *  3. 구분선
 *  4. 전체 Framework 목록으로 복귀
 *  5. 기존 Framework 상속하여 새로 만들기 → InheritFrameworkDialog
 *
 * 사용 예시 (ControlsView.vue):
 *   <FrameworkSwitcher
 *     :current-framework-id="selectedFrameworkId"
 *     @switched="onFrameworkSwitched"
 *     @inherited="onFrameworkInherited" />
 */
const props = defineProps<{
  currentFrameworkId: number | null
}>()

const emit = defineEmits<{
  'switched': [frameworkId: number]
  'inherited': [framework: Framework]
  'error': [message: string]
}>()

const router = useRouter()

const frameworks = ref<Framework[]>([])
const loading = ref(false)
const showDropdown = ref(false)
const showArchived = ref(false)
const showInheritDialog = ref(false)

const rootRef = ref<HTMLElement | null>(null)

const currentFramework = computed<Framework | null>(() => {
  if (props.currentFrameworkId == null) return null
  return frameworks.value.find(f => f.id === props.currentFrameworkId) ?? null
})

const switchableFrameworks = computed<Framework[]>(() => {
  const list = showArchived.value
    ? frameworks.value
    : frameworks.value.filter(f => f.status !== 'archived')
  return list.filter(f => f.id !== props.currentFrameworkId)
})

onMounted(async () => {
  await loadFrameworks()
  document.addEventListener('click', handleDocumentClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
})

watch(() => props.currentFrameworkId, () => {
  showDropdown.value = false
})

async function loadFrameworks() {
  loading.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
    }
  } catch (e) {
    console.error('Framework 목록 조회 실패:', e)
    emit('error', 'Framework 목록을 불러오지 못했습니다.')
  } finally {
    loading.value = false
  }
}

function toggleDropdown() {
  showDropdown.value = !showDropdown.value
}

function handleDocumentClick(e: MouseEvent) {
  if (!rootRef.value) return
  if (!rootRef.value.contains(e.target as Node)) {
    showDropdown.value = false
  }
}

function switchTo(fw: Framework) {
  showDropdown.value = false
  emit('switched', fw.id)
}

function goToList() {
  showDropdown.value = false
  router.push({ name: 'framework-list' })
}

function openInheritDialog() {
  showDropdown.value = false
  showInheritDialog.value = true
}

function onInheritCreated(fw: Framework) {
  frameworks.value.push(fw)
  emit('inherited', fw)
}

function statusLabel(fw: Framework): string {
  return fw.status === 'archived' ? ' [종료]' : ''
}
</script>

<template>
  <div ref="rootRef" class="relative inline-block">
    <!-- 트리거: 현재 Framework 이름 -->
    <button
      @click="toggleDropdown"
      :disabled="loading || !currentFramework"
      class="inline-flex items-center gap-1.5 text-left group">
      <h1 class="text-xl font-bold text-gray-900 group-hover:text-blue-600 transition-colors">
        {{ currentFramework?.name ?? (loading ? '불러오는 중...' : 'Framework 없음') }}
      </h1>
      <span
        v-if="currentFramework && currentFramework.pendingReviewCount != null && currentFramework.pendingReviewCount > 0"
        class="px-1.5 py-0.5 bg-blue-100 text-blue-700 text-[10px] font-medium rounded">
        검토 {{ currentFramework.pendingReviewCount }}
      </span>
      <i
        v-if="currentFramework"
        :class="['pi text-xs text-gray-400 transition-transform',
          showDropdown ? 'pi-chevron-up' : 'pi-chevron-down']"></i>
    </button>

    <!-- 드롭다운 -->
    <div
      v-if="showDropdown"
      class="absolute left-0 top-full mt-2 w-80 bg-white border border-gray-200 rounded-xl shadow-lg z-40 overflow-hidden">
      <!-- 헤더 -->
      <div class="px-4 py-2.5 border-b border-gray-100">
        <div class="flex items-center justify-between">
          <span class="text-[11px] font-semibold text-gray-500 uppercase tracking-wider">
            Framework 전환
          </span>
          <label class="flex items-center gap-1 text-[11px] text-gray-400 cursor-pointer">
            <input
              type="checkbox"
              v-model="showArchived"
              class="rounded border-gray-300 text-blue-600 focus:ring-blue-500 scale-90" />
            종료 포함
          </label>
        </div>
      </div>

      <!-- 전환 가능 Framework 목록 -->
      <div class="max-h-64 overflow-y-auto">
        <button
          v-for="fw in switchableFrameworks"
          :key="fw.id"
          @click="switchTo(fw)"
          class="w-full px-4 py-2.5 text-left hover:bg-gray-50 flex items-center justify-between gap-2 border-b border-gray-50 last:border-b-0">
          <div class="flex-1 min-w-0">
            <div class="text-sm text-gray-900 truncate">
              {{ fw.name }}<span class="text-gray-400 text-[11px]">{{ statusLabel(fw) }}</span>
            </div>
            <div class="text-[11px] text-gray-500 mt-0.5">
              통제 {{ fw.controlCount }}
              · 증빙 {{ fw.evidenceTypeCount ?? 0 }}
              · 작업 {{ fw.jobCount ?? 0 }}
            </div>
          </div>
          <span
            v-if="fw.pendingReviewCount != null && fw.pendingReviewCount > 0"
            class="shrink-0 px-1.5 py-0.5 bg-blue-100 text-blue-700 text-[10px] font-medium rounded">
            검토 {{ fw.pendingReviewCount }}
          </span>
        </button>
        <div
          v-if="switchableFrameworks.length === 0"
          class="px-4 py-5 text-center text-xs text-gray-400">
          다른 Framework 가 없습니다.
        </div>
      </div>

      <!-- 구분선 + 공용 액션 -->
      <div class="border-t border-gray-100 bg-gray-50/50 py-1">
        <button
          @click="goToList"
          class="w-full px-4 py-2 text-left text-sm text-gray-600 hover:bg-gray-100 flex items-center gap-2">
          <i class="pi pi-list text-xs text-gray-400"></i>
          전체 Framework 목록
        </button>
        <button
          @click="openInheritDialog"
          class="w-full px-4 py-2 text-left text-sm text-gray-600 hover:bg-gray-100 flex items-center gap-2">
          <i class="pi pi-sitemap text-xs text-gray-400"></i>
          기존 Framework 상속하여 만들기
        </button>
      </div>
    </div>

    <!-- 상속 다이얼로그 -->
    <InheritFrameworkDialog
      :open="showInheritDialog"
      :initial-source-id="props.currentFrameworkId"
      @update:open="showInheritDialog = $event"
      @created="onInheritCreated"
      @error="(msg) => emit('error', msg)" />
  </div>
</template>