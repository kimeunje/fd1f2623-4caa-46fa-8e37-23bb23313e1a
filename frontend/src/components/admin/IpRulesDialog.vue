<script setup lang="ts">
/**
 * v19.2 (FE-1) — 계정별 IP 접근 규칙 편집 모달.
 *
 * AccountsView 의 사용자 행에서 열어 해당 계정의 IP 규칙을 목록·추가·활성토글·삭제한다.
 * cidr 형식의 최종 검증은 BE(IpCidr.isValid)가 담당하고, 본 컴포넌트는 BE 의 에러 메시지
 * (400 형식 오류 / 409 자기 잠금)를 인라인으로 표시한다.
 */
import { ref, watch, computed } from 'vue'
import { ipRulesApi } from '@/services/api'
import type { IpAccessRule } from '@/types'

const props = defineProps<{
  modelValue: boolean
  userId: number | null
  userName?: string
  isSelf?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const rules = ref<IpAccessRule[]>([])
const loading = ref(false)
const submitting = ref(false)
const error = ref<string | null>(null)

const newCidr = ref('')
const newDescription = ref('')
const newEnabled = ref(true)

const hasRules = computed(() => rules.value.length > 0)
const restricted = computed(() => rules.value.some((r) => r.enabled))

function close() {
  // v19.7 — 작성 중(CIDR/메모 입력)인데 닫으면 confirm. 빈 상태면 바로 닫힘.
  const dirty = newCidr.value.trim() !== '' || newDescription.value.trim() !== ''
  if (dirty && !window.confirm('입력 중인 내용이 있습니다. 닫으면 사라집니다. 닫을까요?')) {
    return
  }
  emit('update:modelValue', false)
}

function resetForm() {
  newCidr.value = ''
  newDescription.value = ''
  newEnabled.value = true
  error.value = null
}

async function load() {
  if (props.userId == null) return
  loading.value = true
  error.value = null
  try {
    const res = await ipRulesApi.list(props.userId)
    rules.value = res.data.data
  } catch (e: any) {
    error.value = e.response?.data?.message || '규칙을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.userId] as const,
  ([open]) => {
    if (open && props.userId != null) {
      resetForm()
      rules.value = []
      load()
    }
  },
)

async function add() {
  if (props.userId == null) return
  const cidr = newCidr.value.trim()
  if (!cidr) {
    error.value = 'IP 또는 CIDR 을 입력하세요.'
    return
  }
  submitting.value = true
  error.value = null
  try {
    await ipRulesApi.create(props.userId, {
      cidr,
      description: newDescription.value.trim() || undefined,
      enabled: newEnabled.value,
    })
    resetForm()
    await load()
  } catch (e: any) {
    error.value = e.response?.data?.message || '규칙 추가에 실패했습니다.'
  } finally {
    submitting.value = false
  }
}

async function toggle(rule: IpAccessRule) {
  if (props.userId == null) return
  error.value = null
  try {
    await ipRulesApi.update(props.userId, rule.id, { enabled: !rule.enabled })
    await load()
  } catch (e: any) {
    error.value = e.response?.data?.message || '변경에 실패했습니다.'
  }
}

async function remove(rule: IpAccessRule) {
  if (props.userId == null) return
  if (!window.confirm(`규칙 "${rule.cidr}" 을(를) 삭제할까요?`)) return
  error.value = null
  try {
    await ipRulesApi.delete(props.userId, rule.id)
    await load()
  } catch (e: any) {
    error.value = e.response?.data?.message || '삭제에 실패했습니다.'
  }
}
</script>

<template>
  <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/40" @click="close"></div>

    <div
      class="relative bg-white rounded-xl border border-gray-200 shadow-xl w-full max-w-lg mx-4 max-h-[85vh] flex flex-col"
    >
      <!-- header -->
      <div class="flex items-center justify-between px-5 py-4 border-b border-gray-200">
        <div>
          <h3 class="text-base font-semibold text-gray-900">IP 접근 규칙</h3>
          <p class="text-xs text-gray-500 mt-0.5">
            {{ userName || '사용자' }} ·
            {{ restricted ? '제한 적용 중' : '제한 없음 (모든 IP 허용)' }}
          </p>
        </div>
        <button class="text-gray-400 hover:text-gray-600" @click="close">
          <i class="pi pi-times"></i>
        </button>
      </div>

      <!-- body -->
      <div class="px-5 py-4 overflow-y-auto space-y-4">
        <div
          v-if="isSelf"
          class="flex items-start gap-2 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2"
        >
          <i class="pi pi-exclamation-triangle mt-0.5"></i>
          <span>본인 계정입니다. 현재 접속 IP 를 배제하는 규칙은 적용이 거부됩니다(잠금 방지).</span>
        </div>

        <div
          v-if="error"
          class="flex items-start gap-2 text-xs text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2"
        >
          <i class="pi pi-times-circle mt-0.5"></i>
          <span>{{ error }}</span>
        </div>

        <!-- list -->
        <div v-if="loading" class="py-8 text-center text-gray-500">
          <i class="pi pi-spin pi-spinner text-xl"></i>
        </div>
        <div v-else-if="!hasRules" class="py-6 text-center text-sm text-gray-400">
          등록된 규칙이 없습니다. 규칙이 없으면 모든 IP 에서 접근할 수 있습니다.
        </div>
        <ul v-else class="space-y-2">
          <li
            v-for="rule in rules"
            :key="rule.id"
            class="flex items-center gap-3 px-3 py-2 border border-gray-200 rounded-lg"
            :class="rule.enabled ? 'bg-white' : 'bg-gray-50'"
          >
            <div class="flex-1 min-w-0">
              <p class="text-sm font-mono text-gray-900 truncate">{{ rule.cidr }}</p>
              <p v-if="rule.description" class="text-xs text-gray-500 truncate">
                {{ rule.description }}
              </p>
            </div>
            <button
              class="text-xs px-2 py-1 rounded-md border"
              :class="
                rule.enabled
                  ? 'border-green-300 text-green-700 bg-green-50'
                  : 'border-gray-300 text-gray-500 bg-white'
              "
              @click="toggle(rule)"
            >
              {{ rule.enabled ? '활성' : '비활성' }}
            </button>
            <button class="text-gray-400 hover:text-red-600" @click="remove(rule)">
              <i class="pi pi-trash text-sm"></i>
            </button>
          </li>
        </ul>

        <!-- add form -->
        <div class="border-t border-gray-200 pt-4 space-y-2">
          <p class="text-xs font-semibold text-gray-600">규칙 추가</p>
          <input
            v-model="newCidr"
            type="text"
            placeholder="IP 또는 CIDR (예: 203.0.113.0/24, 192.168.1.10)"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono"
            @keyup.enter="add"
          />
          <input
            v-model="newDescription"
            type="text"
            placeholder="메모 (선택)"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            @keyup.enter="add"
          />
          <div class="flex items-center justify-between">
            <label class="flex items-center gap-2 text-sm text-gray-600">
              <input v-model="newEnabled" type="checkbox" class="rounded" />
              활성 상태로 추가
            </label>
            <button
              class="flex items-center gap-2 px-4 py-2 bg-blue-500 text-white rounded-lg text-sm font-medium hover:bg-blue-600 disabled:opacity-50"
              :disabled="submitting"
              @click="add"
            >
              <i class="pi" :class="submitting ? 'pi-spin pi-spinner' : 'pi-plus'"></i>
              추가
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>