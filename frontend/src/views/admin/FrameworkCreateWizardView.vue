<script setup lang="ts">
/**
 * Framework 생성 Wizard (v11 Phase 5-11).
 *
 * secuhub_unified_prototype.html 의 stage-choose / stage-config / stage-confirm
 * 3단계를 하나의 뷰로 통합한 wizard. 라우트 `/controls/new` 에서 진입.
 *
 * 진입 경로 2곳:
 *  - FrameworkListView 우측 상단 [+ 새 Framework] 버튼
 *  - FrameworkSwitcher 드롭다운 "새 Framework 만들기" 메뉴
 *
 * 동작:
 *  STEP 1 (choose) — 생성 방식 선택 (inherit 권장 / blank)
 *  STEP 2 (config) — 상세 입력 (inherit 시: 원본 Framework + 새 이름, blank 시: 새 이름만)
 *  STEP 3 (confirm) — 복제 대상 확인 + [생성하기]
 *
 *  inherit → frameworksApi.inherit(), blank → frameworksApi.create()
 *  성공 시 새 Framework 상세로 이동 (router.push framework-detail)
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { frameworksApi } from '@/services/evidenceApi'
import type { Framework } from '@/types/evidence'

const router = useRouter()

// ========================================
// Wizard state
// ========================================
type Step = 'choose' | 'config' | 'confirm'
type Mode = 'inherit' | 'blank'

const step = ref<Step>('choose')
const mode = ref<Mode>('inherit')

// 원본 Framework 후보
const frameworks = ref<Framework[]>([])
const loadingFrameworks = ref(false)

// 폼 값
const form = ref({
  sourceFrameworkId: null as number | null,
  name: '',
  description: '',
})

// 종료된 Framework 포함 토글
const showArchived = ref(false)

// 제출 중 플래그
const submitting = ref(false)

// 토스트
const toast = ref({ show: false, message: '', type: 'success' as 'success' | 'error' })
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => { toast.value.show = false }, 3000)
}

// ========================================
// 원본 후보 목록 (archived 토글 반영)
// ========================================
const availableFrameworks = computed<Framework[]>(() => {
  if (showArchived.value) return frameworks.value
  return frameworks.value.filter(f => f.status !== 'archived')
})

// 선택된 원본
const selectedSource = computed<Framework | null>(() => {
  if (form.value.sourceFrameworkId == null) return null
  return frameworks.value.find(f => f.id === form.value.sourceFrameworkId) ?? null
})

// 원본 Framework 가 하나도 없으면 'inherit' 모드 비활성화
const hasAnyFramework = computed(() => frameworks.value.length > 0)

// ========================================
// 진행 가능 여부
// ========================================
const canProceedFromConfig = computed(() => {
  if (!form.value.name.trim()) return false
  if (mode.value === 'inherit' && form.value.sourceFrameworkId == null) return false
  return true
})

// ========================================
// 원본 선택 변경 시 이름 자동 제안
// ========================================
function onSourceChange() {
  if (selectedSource.value && !form.value.name.trim()) {
    form.value.name = `${selectedSource.value.name} (복제)`
  }
}

// ========================================
// 단계 전환
// ========================================
function goToStep(next: Step) {
  step.value = next
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function cancel() {
  router.push({ name: 'framework-list' })
}

// ========================================
// 최종 제출
// ========================================
async function handleSubmit() {
  if (submitting.value) return
  if (!form.value.name.trim()) {
    showToast('Framework 이름을 입력하세요.', 'error')
    goToStep('config')
    return
  }
  submitting.value = true
  try {
    if (mode.value === 'inherit') {
      if (form.value.sourceFrameworkId == null) {
        showToast('상속받을 Framework 를 선택하세요.', 'error')
        goToStep('config')
        return
      }
      const { data } = await frameworksApi.inherit({
        sourceFrameworkId: form.value.sourceFrameworkId,
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
      })
      if (data.success) {
        router.push({ name: 'framework-detail', params: { frameworkId: data.data.id } })
      } else {
        showToast(data.message ?? 'Framework 상속 생성에 실패했습니다.', 'error')
      }
    } else {
      // blank 모드
      const { data } = await frameworksApi.create({
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
      })
      if (data.success) {
        router.push({ name: 'framework-detail', params: { frameworkId: data.data.id } })
      } else {
        showToast('Framework 생성에 실패했습니다.', 'error')
      }
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message ?? 'Framework 생성에 실패했습니다. 잠시 후 다시 시도해주세요.'
    showToast(msg, 'error')
  } finally {
    submitting.value = false
  }
}

// ========================================
// 초기 데이터 로드
// ========================================
onMounted(async () => {
  loadingFrameworks.value = true
  try {
    const { data } = await frameworksApi.list()
    if (data.success) {
      frameworks.value = data.data
      // Framework 가 하나도 없으면 blank 모드로 시작
      if (!hasAnyFramework.value) {
        mode.value = 'blank'
      }
    }
  } catch (e) {
    console.error('Framework 목록 조회 실패:', e)
    showToast('원본 Framework 후보 목록을 불러오지 못했습니다.', 'error')
  } finally {
    loadingFrameworks.value = false
  }
})
</script>

<template>
  <div class="p-6 max-w-3xl mx-auto">
    <!-- 토스트 -->
    <Transition name="toast">
      <div v-if="toast.show"
        class="fixed top-6 right-6 z-[60] px-4 py-3 rounded-lg shadow-lg text-sm font-medium flex items-center gap-2"
        :class="toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'">
        <i :class="['pi text-sm', toast.type === 'success' ? 'pi-check-circle' : 'pi-times-circle']"></i>
        {{ toast.message }}
      </div>
    </Transition>

    <!-- ========================================
         STEP 1: 생성 방식 선택 (choose)
         ======================================== -->
    <template v-if="step === 'choose'">
      <button
        @click="cancel"
        class="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-900 mb-3">
        <i class="pi pi-chevron-left text-[10px]"></i>
        Framework 목록으로
      </button>
      <div class="bg-white border border-gray-200 rounded-xl p-8">
        <div class="mb-1 text-[11px] text-gray-400 font-medium tracking-wider">STEP 1 / 3</div>
        <h2 class="text-xl font-bold text-gray-900 mb-1">새 Framework 을 어떻게 만들까요?</h2>
        <p class="text-sm text-gray-500 mb-6">생성 방식을 선택해주세요.</p>

        <div class="space-y-3">
          <!-- 기존 Framework 상속 -->
          <label
            class="block rounded-lg p-5 cursor-pointer transition-colors"
            :class="[
              mode === 'inherit'
                ? 'border-2 border-gray-900 bg-gray-50'
                : 'border border-gray-200 hover:border-gray-400',
              !hasAnyFramework ? 'opacity-50 cursor-not-allowed' : '',
            ]">
            <div class="flex items-start gap-3">
              <input
                type="radio"
                value="inherit"
                v-model="mode"
                :disabled="!hasAnyFramework"
                class="mt-1" />
              <div class="flex-1">
                <div class="flex items-center gap-2 mb-1">
                  <span class="font-medium text-sm text-gray-900">기존 Framework 상속</span>
                  <span class="inline-block px-2 py-0.5 text-[10px] font-medium rounded-md bg-blue-50 text-blue-700">
                    권장
                  </span>
                </div>
                <div class="text-xs text-gray-500 leading-relaxed">
                  기존 Framework 의 통제 항목·증빙 유형·수집 작업을 모두 복제해서 시작합니다.
                  이후에는 독립적으로 관리됩니다. 매년 감사 주기를 새로 시작할 때 사용합니다.
                </div>
                <div
                  v-if="!hasAnyFramework"
                  class="text-[11px] text-amber-600 mt-2">
                  상속할 Framework 가 없습니다. "처음부터 만들기" 를 선택해주세요.
                </div>
              </div>
            </div>
          </label>

          <!-- 처음부터 만들기 -->
          <label
            class="block rounded-lg p-5 cursor-pointer transition-colors"
            :class="mode === 'blank'
              ? 'border-2 border-gray-900 bg-gray-50'
              : 'border border-gray-200 hover:border-gray-400'">
            <div class="flex items-start gap-3">
              <input type="radio" value="blank" v-model="mode" class="mt-1" />
              <div class="flex-1">
                <div class="font-medium text-sm text-gray-900 mb-1">처음부터 만들기</div>
                <div class="text-xs text-gray-500 leading-relaxed">
                  빈 Framework 으로 시작합니다. 통제 항목을 하나씩 등록하거나 엑셀로 가져옵니다.
                </div>
              </div>
            </div>
          </label>
        </div>

        <div class="flex justify-end gap-2 mt-8">
          <button
            @click="cancel"
            class="h-9 px-4 text-sm border border-gray-200 bg-white rounded-md hover:bg-gray-50">
            취소
          </button>
          <button
            @click="goToStep('config')"
            class="h-9 px-4 text-sm bg-gray-900 text-white rounded-md hover:bg-gray-800">
            다음
          </button>
        </div>
      </div>
    </template>

    <!-- ========================================
         STEP 2: 상세 정보 입력 (config)
         ======================================== -->
    <template v-else-if="step === 'config'">
      <button
        @click="goToStep('choose')"
        class="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-900 mb-3">
        <i class="pi pi-chevron-left text-[10px]"></i>
        이전 단계
      </button>
      <div class="bg-white border border-gray-200 rounded-xl p-8">
        <div class="mb-1 text-[11px] text-gray-400 font-medium tracking-wider">STEP 2 / 3</div>
        <h2 class="text-xl font-bold text-gray-900 mb-1">
          <template v-if="mode === 'inherit'">상속받을 Framework 과 새 이름을 지정하세요</template>
          <template v-else>새 Framework 의 정보를 입력하세요</template>
        </h2>
        <p class="text-sm text-gray-500 mb-6">
          <template v-if="mode === 'inherit'">
            원본 Framework 를 선택하면 이름이 자동으로 제안됩니다.
          </template>
          <template v-else>
            빈 Framework 으로 시작합니다.
          </template>
        </p>

        <div class="space-y-5 max-w-2xl">
          <!-- 원본 Framework (inherit 모드에서만) -->
          <div v-if="mode === 'inherit'">
            <div class="flex items-center justify-between mb-1.5">
              <label class="block text-xs font-medium text-gray-700">
                상속받을 Framework <span class="text-red-500">*</span>
              </label>
              <label class="flex items-center gap-1 text-[11px] text-gray-400 cursor-pointer">
                <input
                  type="checkbox"
                  v-model="showArchived"
                  class="rounded border-gray-300 text-blue-600 focus:ring-blue-500 scale-90" />
                종료된 Framework 포함
              </label>
            </div>
            <select
              v-model="form.sourceFrameworkId"
              @change="onSourceChange"
              :disabled="loadingFrameworks"
              class="w-full h-10 px-3 text-sm bg-white border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none">
              <option :value="null">
                {{ loadingFrameworks ? '불러오는 중…' : '-- 상속받을 Framework 선택 --' }}
              </option>
              <option v-for="fw in availableFrameworks" :key="fw.id" :value="fw.id">
                {{ fw.name }}<template v-if="fw.status === 'archived'"> [종료]</template>
                (통제 {{ fw.controlCount }} · 증빙 {{ fw.evidenceTypeCount ?? 0 }} · 작업 {{ fw.jobCount ?? 0 }})
              </option>
            </select>
          </div>

          <!-- 새 Framework 이름 -->
          <div>
            <label class="block text-xs font-medium text-gray-700 mb-1.5">
              새 Framework 이름 <span class="text-red-500">*</span>
            </label>
            <input
              type="text"
              v-model="form.name"
              placeholder="ISMS-P 2027"
              class="w-full h-10 px-3 text-sm border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none" />
            <p class="text-[11px] text-gray-400 mt-1.5">중복되지 않는 이름을 입력하세요.</p>
          </div>

          <!-- 설명 (선택) -->
          <div>
            <label class="block text-xs font-medium text-gray-700 mb-1.5">설명</label>
            <textarea
              v-model="form.description"
              rows="3"
              placeholder="간단한 설명 (선택)"
              class="w-full px-3 py-2 text-sm border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none resize-none"></textarea>
          </div>
        </div>

        <div class="flex justify-end gap-2 mt-8">
          <button
            @click="goToStep('choose')"
            class="h-9 px-4 text-sm border border-gray-200 bg-white rounded-md hover:bg-gray-50">
            뒤로
          </button>
          <button
            @click="goToStep('confirm')"
            :disabled="!canProceedFromConfig"
            class="h-9 px-4 text-sm bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:bg-gray-300 disabled:cursor-not-allowed">
            다음
          </button>
        </div>
      </div>
    </template>

    <!-- ========================================
         STEP 3: 확인 (confirm)
         ======================================== -->
    <template v-else-if="step === 'confirm'">
      <button
        @click="goToStep('config')"
        class="inline-flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-900 mb-3">
        <i class="pi pi-chevron-left text-[10px]"></i>
        이전 단계
      </button>
      <div class="bg-white border border-gray-200 rounded-xl p-8">
        <div class="mb-1 text-[11px] text-gray-400 font-medium tracking-wider">STEP 3 / 3</div>
        <h2 class="text-xl font-bold text-gray-900 mb-1">
          <template v-if="mode === 'inherit'">아래 내용으로 상속 생성합니다</template>
          <template v-else>아래 내용으로 새 Framework 를 생성합니다</template>
        </h2>
        <p class="text-sm text-gray-500 mb-6">
          <strong class="text-gray-900">{{ form.name }}</strong>
          <template v-if="mode === 'inherit' && selectedSource">
            · 원본: {{ selectedSource.name }}
          </template>
        </p>

        <!-- inherit 모드: 복제 대상 카드 -->
        <template v-if="mode === 'inherit' && selectedSource">
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-5">
            <div class="relative pl-10">
              <div class="absolute left-0 top-0 w-[30px] h-[30px] rounded-full bg-green-50 text-green-700 flex items-center justify-center">
                <i class="pi pi-check text-xs"></i>
              </div>
              <div class="text-sm font-medium text-gray-900">통제 항목 {{ selectedSource.controlCount }}개</div>
              <div class="text-[11px] text-gray-500 mt-0.5">원본 구조 그대로 복제</div>
            </div>
            <div class="relative pl-10">
              <div class="absolute left-0 top-0 w-[30px] h-[30px] rounded-full bg-green-50 text-green-700 flex items-center justify-center">
                <i class="pi pi-check text-xs"></i>
              </div>
              <div class="text-sm font-medium text-gray-900">증빙 유형 {{ selectedSource.evidenceTypeCount ?? 0 }}개</div>
              <div class="text-[11px] text-gray-500 mt-0.5">담당자·마감일 포함</div>
            </div>
            <div class="relative pl-10">
              <div class="absolute left-0 top-0 w-[30px] h-[30px] rounded-full bg-green-50 text-green-700 flex items-center justify-center">
                <i class="pi pi-check text-xs"></i>
              </div>
              <div class="text-sm font-medium text-gray-900">수집 작업 {{ selectedSource.jobCount ?? 0 }}개</div>
              <div class="text-[11px] text-gray-500 mt-0.5">새 증빙 유형에 재연결</div>
            </div>
            <div class="relative pl-10">
              <div class="absolute left-0 top-0 w-[30px] h-[30px] rounded-full bg-gray-100 text-gray-400 flex items-center justify-center">
                <i class="pi pi-minus text-xs"></i>
              </div>
              <div class="text-sm font-medium text-gray-500">파일 / 실행 이력 복제 안 됨</div>
              <div class="text-[11px] text-gray-400 mt-0.5">빈 상태로 시작</div>
            </div>
          </div>

          <div class="bg-amber-50 border border-amber-100 rounded-md p-4 mb-6">
            <div class="flex gap-2.5">
              <i class="pi pi-exclamation-circle text-amber-600 shrink-0 mt-0.5 text-sm"></i>
              <div>
                <div class="text-sm font-medium text-amber-900 mb-0.5">상속 완료 후 독립적으로 관리됩니다</div>
                <div class="text-[12px] text-amber-800 leading-relaxed">
                  이후 부모 / 자식 Framework 어느 쪽을 수정해도 다른 쪽에 영향이 없습니다.
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- blank 모드 안내 -->
        <template v-else>
          <div class="bg-blue-50 border border-blue-100 rounded-md p-4 mb-6">
            <div class="flex gap-2.5">
              <i class="pi pi-info-circle text-blue-600 shrink-0 mt-0.5 text-sm"></i>
              <div>
                <div class="text-sm font-medium text-blue-900 mb-0.5">빈 Framework 으로 시작합니다</div>
                <div class="text-[12px] text-blue-800 leading-relaxed">
                  생성 후 통제 항목을 하나씩 등록하거나 엑셀 Import 로 일괄 추가할 수 있습니다.
                </div>
              </div>
            </div>
          </div>
        </template>

        <div class="flex justify-end gap-2">
          <button
            @click="goToStep('config')"
            :disabled="submitting"
            class="h-9 px-4 text-sm border border-gray-200 bg-white rounded-md hover:bg-gray-50 disabled:opacity-50">
            뒤로
          </button>
          <button
            @click="handleSubmit"
            :disabled="submitting"
            class="h-9 px-5 text-sm bg-gray-900 text-white rounded-md hover:bg-gray-800 disabled:opacity-50 inline-flex items-center gap-1.5">
            <i v-if="submitting" class="pi pi-spin pi-spinner text-xs"></i>
            {{ submitting ? '생성 중…' : '생성하기' }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}
</style>