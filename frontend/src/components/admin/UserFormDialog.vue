<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { usersApi } from '@/services/api'
import { frameworksApi, reviewerAccessApi } from '@/services/evidenceApi'
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

/**
 * v19.24 — 신규 계정은 "관리자 / 심사원" 2택 (담당자 폐기).
 * 수정 모드에서 현재 역할이 레거시(approver/developer)면 그 옵션을 유지해
 * select 가 값을 잃거나 실수로 역할이 바뀌지 않게 한다. 신규 생성엔 미노출.
 */
const roleOptions = computed<{ value: UserRole; label: string }[]>(() => {
  const base: { value: UserRole; label: string }[] = [
    { value: 'admin', label: '관리자' },
    { value: 'reviewer', label: '심사원' },
  ]
  if (isEdit.value && (role.value === 'approver' || role.value === 'developer')) {
    base.push({
      value: role.value,
      label: role.value === 'approver' ? '결재자 (레거시)' : '개발자 (레거시)',
    })
  }
  return base
})

// ── 폼 상태 ──
const email = ref('')
const name = ref('')
const password = ref('')
const team = ref('')
const role = ref<UserRole>('reviewer')
const permissionEvidence = ref(false)
const status = ref<UserStatus>('active')

const saving = ref(false)
const errorMsg = ref('')

// ── v19.25 심사원 프레임워크 배정 ──
const isReviewer = computed(() => role.value === 'reviewer')
const allFrameworks = ref<{ id: number; name: string }[]>([])
const selectedFrameworkIds = ref<number[]>([])
const loadingFrameworks = ref(false)

async function loadFrameworkOptions() {
  if (allFrameworks.value.length > 0) return // 한 번만 로드
  loadingFrameworks.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      allFrameworks.value = data.data.map((f) => ({ id: f.id, name: f.name }))
    }
  } catch {
    // 목록 실패는 치명적이지 않음 — 배정 UI 만 비게 됨
  } finally {
    loadingFrameworks.value = false
  }
}

async function loadAssignedFrameworks(userId: number) {
  try {
    const { data } = await reviewerAccessApi.getFrameworks(userId)
    if (data.success) selectedFrameworkIds.value = data.data
  } catch {
    selectedFrameworkIds.value = []
  }
}

// 다이얼로그가 열릴 때마다 폼을 모드에 맞게 초기화
watch(
  () => props.modelValue,
  async (open) => {
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
      role.value = 'reviewer'      // v19.24 — 신규 기본값: 심사원
      permissionEvidence.value = false
      status.value = 'active'
    }
    // v19.25 — 심사원 프레임워크 배정: 옵션 목록 로드 + (수정·심사원일 때) 기존 배정 로드
    selectedFrameworkIds.value = []
    await loadFrameworkOptions()
    if (props.user && props.user.role === 'reviewer') {
      await loadAssignedFrameworks(props.user.id)
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
    let userId: number
    if (isEdit.value && props.user) {
      const payload: UserUpdatePayload = {
        name: name.value.trim(),
        team: team.value.trim(),
        role: role.value,
        permissionEvidence: permissionEvidence.value,
        status: status.value,
      }
      await usersApi.update(props.user.id, payload)
      userId = props.user.id
    } else {
      const payload: UserCreatePayload = {
        email: email.value.trim(),
        name: name.value.trim(),
        password: password.value,
        team: team.value.trim() || undefined,
        role: role.value,
        permissionEvidence: permissionEvidence.value,
      }
      const { data } = await usersApi.create(payload)
      userId = data.data.id
    }
    // v19.25 — 심사원이면 프레임워크 배정 저장(계정 저장 성공 후, replace-set)
    if (role.value === 'reviewer') {
      await reviewerAccessApi.setFrameworks(userId, selectedFrameworkIds.value)
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
          <p class="mt-1 text-xs text-gray-400">
            심사원은 관리 항목과 최신 증빙을 열람·다운로드만 할 수 있습니다.
          </p>
        </div>

        <!-- v19.25 — 심사원 열람 프레임워크 배정 (역할=심사원일 때만) -->
        <div v-if="isReviewer">
          <label class="block text-sm font-medium text-gray-700 mb-1">열람 프레임워크</label>
          <div v-if="loadingFrameworks" class="text-xs text-gray-400 py-2">불러오는 중...</div>
          <div v-else-if="allFrameworks.length === 0" class="text-xs text-gray-400 py-2">
            등록된 프레임워크가 없습니다.
          </div>
          <div
            v-else
            class="max-h-40 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100"
          >
            <label
              v-for="fw in allFrameworks"
              :key="fw.id"
              class="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 cursor-pointer"
            >
              <input
                type="checkbox"
                :value="fw.id"
                v-model="selectedFrameworkIds"
                class="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span class="truncate">{{ fw.name }}</span>
            </label>
          </div>
          <p class="mt-1 text-xs text-gray-400">
            선택한 프레임워크만 이 심사원에게 열립니다. 미선택 시 아무 것도 열람할 수 없습니다.
          </p>
        </div>

        <!-- v19.24 — 증빙 접근 권한 체크박스 제거. 담당자 폐기로 신규 계정은
             업로드 권한을 갖지 않는다(permission_evidence=false 고정). 레거시
             담당자 계정의 기존 권한은 수정 시 그대로 보존된다. -->

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