<script setup lang="ts">
/**
 * v18.6a — 중복 감지 confirm 다이얼로그 (Q1=b).
 *
 * <p>POST /evidence-files/upload 의 응답이 {@code status="duplicate_detected"} 일 때
 * 부모 (EvidenceTypeDetailView) 가 본 다이얼로그 노출. 사용자 선택 3가지:</p>
 * <ul>
 *   <li>[기존 사용 (권장)] — emit('useExisting') → 부모가 POST /evidence-files/link 호출</li>
 *   <li>[새로 등록] — emit('forceNew') → 부모가 POST /upload?forceUpload=true 재호출 (Q9)</li>
 *   <li>[취소] — emit('cancel') → 다이얼로그 닫고 상태 reset</li>
 * </ul>
 */
import type { EvidenceAsset } from '@/types/evidence'

defineProps<{
  open: boolean
  existingAsset: EvidenceAsset | null
}>()

const emit = defineEmits<{
  (e: 'useExisting'): void
  (e: 'forceNew'): void
  (e: 'cancel'): void
}>()

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatDateTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}`
}
</script>

<template>
  <Transition name="fade">
    <div v-if="open && existingAsset" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
      <div
        @click.self="emit('cancel')"
        class="absolute inset-0">
      </div>

      <!-- 다이얼로그 본체 -->
      <div class="relative bg-white border border-gray-200 rounded-xl w-full max-w-md shadow-xl">

        <!-- 헤더 -->
        <div class="px-5 py-4 border-b border-gray-200">
          <div class="flex items-center gap-2">
            <i class="pi pi-info-circle text-blue-500 text-base"></i>
            <h3 class="text-base font-medium text-gray-900">이미 등록된 파일입니다</h3>
          </div>
          <p class="text-[11px] text-gray-500 mt-1.5">
            업로드한 파일과 동일한 내용이 시스템에 이미 등록되어 있습니다. 어떻게 처리할까요?
          </p>
        </div>

        <!-- 기존 asset 정보 -->
        <div class="px-5 py-4 bg-gray-50 border-b border-gray-200">
          <div class="text-[11px] text-gray-500 mb-2">기존 등록 파일</div>
          <div class="space-y-1.5 text-xs">
            <div class="flex items-start gap-2">
              <i class="pi pi-file text-gray-400 mt-0.5 text-[11px]"></i>
              <span class="font-mono text-[11px] text-gray-900 break-all">
                {{ existingAsset.originalFileName }}
              </span>
            </div>
            <div class="text-[11px] text-gray-600 ml-5">
              {{ formatFileSize(existingAsset.fileSize) }} · {{ formatDateTime(existingAsset.createdAt) }}
              <span v-if="existingAsset.uploadedByName">
                · {{ existingAsset.uploadedByName }}
              </span>
            </div>
            <div class="ml-5 mt-1">
              <span class="inline-flex items-center gap-1 px-1.5 py-0.5 bg-blue-50 text-blue-700 rounded text-[10px]">
                <i class="pi pi-link text-[9px]"></i>
                현재 {{ existingAsset.usedInCount }} 개 증빙에서 사용 중
              </span>
            </div>
          </div>
        </div>

        <!-- 액션 버튼 -->
        <div class="px-5 py-4 space-y-2">
          <!-- 기존 사용 (권장, primary) -->
          <button
            @click="emit('useExisting')"
            class="w-full h-10 px-4 text-sm bg-gray-900 text-white rounded-md hover:bg-gray-800 inline-flex items-center justify-center gap-2">
            <i class="pi pi-link text-xs"></i>
            기존 파일 사용 <span class="text-[11px] text-gray-300">(권장)</span>
          </button>

          <!-- 새로 등록 -->
          <button
            @click="emit('forceNew')"
            class="w-full h-10 px-4 text-sm bg-white text-gray-900 border border-gray-300 rounded-md hover:bg-gray-50 inline-flex items-center justify-center gap-2">
            <i class="pi pi-plus text-xs"></i>
            별도 파일로 새로 등록
          </button>

          <!-- 취소 -->
          <button
            @click="emit('cancel')"
            class="w-full h-9 px-4 text-[11px] text-gray-500 hover:text-gray-700">
            취소
          </button>
        </div>

        <!-- 안내 푸터 -->
        <div class="px-5 py-3 bg-gray-50 border-t border-gray-200 text-[11px] text-gray-500 rounded-b-xl">
          <i class="pi pi-info-circle text-[10px] mr-1"></i>
          기존 파일 사용 시 저장 공간을 절약하고, 한 곳에서 파일을 갱신하면 모든 연결 증빙에 반영됩니다.
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>