<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { usersApi } from '@/services/api'
import type { User, UserRole, UserStatus, UserCreatePayload, UserUpdatePayload } from '@/types'

/**
 * 계정 생성/수정 다이얼로그 (admin 전용).
 *
 * - user prop 이 null 이면 생성 모드, 있으면 수정 모드.
 * - 생성: 이메일·이름·비밀번호·소속·역할·증빙 권한.
 * - 수정: 이름·소속·역할·증빙 권한·상태. 이메일은 변경 불가(읽기 전용),
 *   비밀번호는 본 endpoint 로 변경 불가라 노출하지 않음(본인 비번 변경은 별도).
 * - 저장 성공 시 'saved' emit → 부모가 목록 갱신.
 */
const props = defineProps<{
  modelValue: boolean
  user: User | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const isEdit = computed(() => props.user !== null)

const roleOptions: { value: UserRole; label: string }[] = [
  { value: 'admin', label: '관리자' },
  { value: 'approver', label: '결재자' },
  { value: 'developer', label: '개발자' },
]

// ── 폼 상태 ──
const email = ref('')
const name = ref('')
const password = ref('')
const team = ref('')
const role = ref<UserRole>('developer')
const permissionEvidence = ref(false)
const status = ref<UserStatus>('active')

const saving = ref(false)
const errorMsg = ref('')

// 다이얼로그가 열릴 때마다 폼을 모드에 맞게 초기화
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    errorMsg.value = ''
    saving.value = false
    if (props.user) {
      email.value = props.user.email
      name.value = props.user.name
      team.value = props.user.team ?? ''
      role.value = props.user.role
      permissionEvidence.value = props.user.permissionEvidence
      status.value = props.user.status ?? 'active'
      password.value = ''
    } else {
      email.value = ''
      name.value = ''
      password.value = ''
      team.value = ''
      role.value = 'developer'
      permissionEvidence.value = false
      status.value = 'active'
    }
  }
)

function close() {
  if (saving.value) return
  emit('update:modelValue', false)
}

function validate(): string | null {
  if (!name.value.trim()) return '이름을 입력하세요.'
  if (!isEdit.value) {
    if (!email.value.trim()) return '이메일을 입력하세요.'
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim())) return '이메일 형식이 올바르지 않습니다.'
    if (!password.value) return '비밀번호를 입력하세요.'
    if (password.value.length < 8) return '비밀번호는 8자 이상이어야 합니다.'
  }
  return null
}

async function save() {
  const err = validate()
  if (err) {
    errorMsg.value = err
    return
  }
  saving.value = true
  errorMsg.value = ''
  try {
    if (isEdit.value && props.user) {
      const payload: UserUpdatePayload = {
        name: name.value.trim(),
        team: team.value.trim(),
        role: role.value,
        permissionEvidence: permissionEvidence.value,
        status: status.value,
      }
      await usersApi.update(props.user.id, payload)
    } else {
      const payload: UserCreatePayload = {
        email: email.value.trim(),
        name: name.value.trim(),
        password: password.value,
        team: team.value.trim() || undefined,
        role: role.value,
        permissionEvidence: permissionEvidence.value,
      }
      await usersApi.create(payload)
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    errorMsg.value =
      e.response?.data?.message || e.message || '저장에 실패했습니다. 잠시 후 다시 시도하세요.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div
    v-if="modelValue"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
    @click.self="close"
    @keydown.esc="close"
  >
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-md max-h-[90vh] overflow-y-auto">
      <!-- 헤더 -->
      <div class="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <h2 class="text-lg font-semibold text-gray-900">
          {{ isEdit ? '계정 수정' : '계정 추가' }}
        </h2>
        <button class="p-1 text-gray-400 hover:text-gray-600" title="닫기" @click="close">
          <i class="pi pi-times text-sm"></i>
        </button>
      </div>

      <!-- 본문 -->
      <div class="px-6 py-5 space-y-4">
        <!-- 이메일 -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">이메일</label>
          <input
            v-model="email"
            type="email"
            :disabled="isEdit"
            placeholder="user@company.com"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none disabled:bg-gray-50 disabled:text-gray-500"
          />
          <p v-if="isEdit" class="mt-1 text-xs text-gray-400">이메일은 변경할 수 없습니다.</p>
        </div>

        <!-- 이름 -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">이름</label>
          <input
            v-model="name"
            type="text"
            placeholder="이름"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          />
        </div>

        <!-- 비밀번호 (생성 모드만) -->
        <div v-if="!isEdit">
          <label class="block text-sm font-medium text-gray-700 mb-1">비밀번호</label>
          <input
            v-model="password"
            type="password"
            placeholder="8자 이상"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          />
        </div>

        <!-- 소속 -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">소속 <span class="text-gray-400 font-normal">(선택)</span></label>
          <input
            v-model="team"
            type="text"
            placeholder="예: 보안팀"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          />
        </div>

        <!-- 역할 -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">역할</label>
          <select
            v-model="role"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none bg-white"
          >
            <option v-for="opt in roleOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>

        <!-- 증빙 접근 권한 -->
        <div class="flex items-center gap-2">
          <input
            id="perm-evidence"
            v-model="permissionEvidence"
            type="checkbox"
            class="w-4 h-4 text-blue-500 border-gray-300 rounded focus:ring-blue-500"
          />
          <label for="perm-evidence" class="text-sm text-gray-700">증빙 접근 권한 부여</label>
        </div>

        <!-- 상태 (수정 모드만) -->
        <div v-if="isEdit">
          <label class="block text-sm font-medium text-gray-700 mb-1">상태</label>
          <select
            v-model="status"
            class="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none bg-white"
          >
            <option value="active">활성</option>
            <option value="inactive">비활성</option>
          </select>
          <p class="mt-1 text-xs text-gray-400">비활성 계정은 로그인할 수 없습니다.</p>
        </div>

        <!-- 에러 -->
        <div v-if="errorMsg" class="p-3 bg-red-50 border border-red-200 rounded-lg">
          <p class="text-sm text-red-600">{{ errorMsg }}</p>
        </div>
      </div>

      <!-- 푸터 -->
      <div class="flex items-center justify-end gap-2 px-6 py-4 border-t border-gray-200">
        <button
          class="px-4 py-2 text-sm font-medium text-gray-600 rounded-lg hover:bg-gray-100"
          :disabled="saving"
          @click="close"
        >
          취소
        </button>
        <button
          class="flex items-center gap-2 px-4 py-2 bg-blue-500 text-white rounded-lg text-sm font-medium hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="saving"
          @click="save"
        >
          <i v-if="saving" class="pi pi-spin pi-spinner text-sm"></i>
          {{ isEdit ? '변경 저장' : '계정 추가' }}
        </button>
      </div>
    </div>
  </div>
</template>