<script setup lang="ts">
/**
 * v19.23 — NodeDescriptionDialog.vue
 *
 * 관리 항목(control_nodes)의 설명 편집/열람 모달. 원문(markdown) + 미리보기 2단.
 *
 * <h3>mode — 같은 UI, 다른 커밋</h3>
 * <ul>
 *   <li><b>draft</b>  : UnifiedControlsDialog 안에서 호출. [적용] 은 저장이 아니라
 *       tree.setDescription() 으로 dirty 에 올리기만 한다. 실제 PATCH 는
 *       다이얼로그 푸터의 [변경 저장 (N)] 이 담당 — 낙관적 락 흐름을 하나로 유지.</li>
 *   <li><b>immediate</b> : ControlsView 본문에서 호출. 푸터가 없으므로 [저장] 이
 *       tree.saveDescriptionImmediate() 로 즉시 PATCH. 선행 조건 dirtyCount === 0
 *       (호출 측이 ✎ 를 disabled 처리).</li>
 * </ul>
 *
 * <p><b>readonly</b>: 일반사용자(심사원). textarea 미렌더, 미리보기 단독 표시.
 * 단, FE 숨김은 방어가 아니다 — PATCH /tree 는 TreeController 클래스 레벨
 * {@code @PreAuthorize("hasRole('ADMIN')")} 로 이미 차단된다.</p>
 *
 * <h3>z-index</h3>
 * <p>z-[65] — UnifiedControlsDialog(Teleport body) 위, ControlsView 토스트(z-[60])
 * 아래가 아니라 위. 토스트가 계속 최상단이어야 하면 z-[55] 로 내릴 것.</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ControlTreeApi, UnifiedNode } from '@/composables/useControlTree'
import { renderMarkdown, isBlankDescription } from '@/composables/useMarkdown'

interface Props {
  open: boolean
  /** 대상 노드. unified(다이얼로그) / view(본문) 양쪽 모두 id·code·name·description 보유. */
  node: UnifiedNode | null
  treeState: ControlTreeApi
  mode: 'draft' | 'immediate'
  readonly?: boolean
}

const props = withDefaults(defineProps<Props>(), { readonly: false })

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  /** immediate 모드 저장 성공 */
  (e: 'saved'): void
  /** immediate 모드 409 — 호출 측이 TreeConflictDialog 를 띄운다 */
  (e: 'conflict', currentVersion: number): void
  /** 그 외 실패 — 호출 측 토스트 */
  (e: 'error', message: string): void
}>()

const draft = ref('')
const saving = ref(false)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

/** 서버 null 과 '' 은 동치 — no-op 저장 방지 */
const original = computed(() => props.node?.description ?? '')
const isDirty = computed(() => draft.value !== original.value)

// v19.26 — 인수인계 노트 작성자/수정일 (트리 노드에 실림). 없으면 표시 안 함.
const auditName = computed(
  () => (props.node as { descriptionUpdatedByName?: string } | null)?.descriptionUpdatedByName ?? '',
)
const auditAt = computed(
  () => (props.node as { descriptionUpdatedAt?: string } | null)?.descriptionUpdatedAt ?? '',
)
/** ISO → 'YYYY-MM-DD'. 파싱 실패 시 원문 앞 10자. */
function formatDate(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso.slice(0, 10) : d.toISOString().slice(0, 10)
}
const auditLine = computed(() => {
  if (!auditName.value && !auditAt.value) return ''
  const date = formatDate(auditAt.value)
  if (auditName.value && date) return `${date} · ${auditName.value} 수정`
  return auditName.value ? `${auditName.value} 수정` : `${date} 수정`
})
const previewHtml = computed(() => renderMarkdown(draft.value))
const isEmpty = computed(() => isBlankDescription(draft.value))

/** 설명을 지우는 저장인지 — 확인 문구 대신 조용한 힌트로 안내 */
const willClear = computed(() => isEmpty.value && !isBlankDescription(original.value))

watch(
  () => props.open,
  async (v) => {
    if (!v) return
    draft.value = original.value
    saving.value = false
    if (props.readonly) return
    await nextTick()
    textareaRef.value?.focus()
  },
  { immediate: true },
)

function requestClose(): void {
  if (!props.readonly && isDirty.value) {
    const ok = window.confirm('적용하지 않은 변경이 있습니다. 닫으시겠습니까?')
    if (!ok) return
  }
  emit('update:open', false)
}

async function commit(): Promise<void> {
  if (props.readonly || !props.node) return
  if (!isDirty.value) {
    emit('update:open', false)
    return
  }

  if (props.mode === 'draft') {
    // dirty 에 올리기만 한다. 저장은 UnifiedControlsDialog 푸터 책임.
    props.treeState.setDescription(props.node, draft.value)
    emit('update:open', false)
    return
  }

  saving.value = true
  try {
    const result = await props.treeState.saveDescriptionImmediate(props.node, draft.value)
    if (result.ok) {
      emit('saved')
      emit('update:open', false)
      return
    }
    if (result.kind === 'conflict') {
      emit('conflict', result.currentVersion)
      emit('update:open', false)
      return
    }
    // 즉시 모드 실패 — 모달을 닫아 토스트(z-60)가 모달(z-65)에 가리지 않게 한다.
    // saveDescriptionImmediate 가 실패 시 dirty 를 이미 되돌렸으므로 잔여 상태 없음.
    emit('error', result.kind === 'validation' ? '설명 저장이 거부되었습니다' : result.message)
    emit('update:open', false)
  } finally {
    saving.value = false
  }
}

function onKeydown(e: KeyboardEvent): void {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.stopPropagation() // 뒤의 UnifiedControlsDialog 가 같이 닫히지 않도록
    requestClose()
    return
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    e.stopPropagation()
    void commit()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, true))
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open && node"
      class="fixed inset-0 z-[65] flex items-center justify-center bg-black/45 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="node-desc-title"
      @click.self="requestClose"
    >
      <div class="flex max-h-[90vh] w-full max-w-6xl flex-col rounded-xl bg-white shadow-xl">
        <header class="flex items-center justify-between border-b border-gray-200 px-5 py-3">
          <div class="flex flex-col gap-0.5">
            <div class="flex items-baseline gap-2">
              <h2 id="node-desc-title" class="text-sm font-medium text-gray-900">인수인계 노트</h2>
              <span class="font-mono text-xs text-blue-600">{{ node.code }}</span>
              <span class="text-xs text-gray-500">{{ node.name }}</span>
              <span
                v-if="readonly"
                class="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-500"
              >
                읽기 전용
              </span>
            </div>
            <!-- v19.26 — 작성자/수정일. 아직 저장 이력이 없으면 표시 안 함. -->
            <span v-if="auditLine" class="text-[11px] text-gray-400">{{ auditLine }}</span>
          </div>
          <button
            type="button"
            class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            aria-label="닫기"
            @click="requestClose"
          >
            <i class="pi pi-times"></i>
          </button>
        </header>

        <div class="grid min-h-0 flex-1 grid-cols-1" :class="readonly ? '' : 'md:grid-cols-2'">
          <section
            v-if="!readonly"
            class="flex min-h-0 flex-col border-b border-gray-200 p-4 md:border-b-0 md:border-r"
          >
            <div class="mb-2 flex items-center justify-between">
              <label for="node-desc-src" class="text-[11px] text-gray-400">원문 (markdown)</label>
              <span class="text-[11px] text-gray-400">{{ draft.length }}자</span>
            </div>
            <textarea
              id="node-desc-src"
              ref="textareaRef"
              v-model="draft"
              spellcheck="false"
              class="min-h-[440px] flex-1 resize-none rounded-md border border-gray-200 bg-gray-50 p-3 font-mono text-sm leading-relaxed text-gray-700 focus:border-blue-400 focus:outline-none"
              placeholder="다음 담당자를 위한 인수인계 메모&#10;&#10;예)&#10;- 이 증빙은 인사팀 김대리에게 매분기 요청해서 받음&#10;- 2024년부터 양식이 바뀌었으니 구버전과 혼동 주의&#10;- 자동수집 스크립트가 있었으나 API가 막혀 수동 전환함"
            ></textarea>
            <p v-if="willClear" class="mt-2 text-[11px] text-amber-600">
              비워서 적용하면 설명이 삭제됩니다.
            </p>
          </section>

          <section class="flex min-h-0 flex-col overflow-y-auto p-4">
            <div class="mb-2 text-[11px] text-gray-400">미리보기</div>
            <div
              v-if="!isEmpty"
              class="prose-node-desc text-sm leading-relaxed text-gray-700"
              v-html="previewHtml"
            ></div>
            <p v-else class="text-xs text-gray-400">
              {{ readonly ? '등록된 인수인계 노트가 없습니다.' : '왼쪽에 인수인계 노트를 작성하세요.' }}
            </p>
          </section>
        </div>

        <footer class="flex items-center justify-end gap-2 border-t border-gray-200 px-5 py-3">
          <button
            v-if="readonly"
            type="button"
            class="rounded-md border border-gray-300 px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-50"
            @click="requestClose"
          >
            닫기
          </button>
          <template v-else>
            <button
              type="button"
              class="rounded-md border border-gray-300 px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-50"
              :disabled="saving"
              @click="requestClose"
            >
              취소
            </button>
            <button
              type="button"
              class="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              :disabled="saving || !isDirty"
              @click="commit"
            >
              {{ saving ? '저장 중…' : mode === 'draft' ? '적용' : '저장' }}
            </button>
          </template>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/**
 * v-html 대상 — DOMPurify 가 class/style 을 전부 제거하므로 태그 셀렉터로만 스타일링.
 * ALLOWED_TAGS 와 1:1 대응.
 */
.prose-node-desc :deep(p) {
  margin: 0 0 0.75em;
}
.prose-node-desc :deep(strong) {
  font-weight: 500;
  color: #111827;
}
.prose-node-desc :deep(ul),
.prose-node-desc :deep(ol) {
  margin: 0 0 0.75em;
  padding-left: 1.25em;
  list-style: disc;
}
.prose-node-desc :deep(ol) {
  list-style: decimal;
}
.prose-node-desc :deep(li) {
  margin: 0.2em 0;
}
.prose-node-desc :deep(li > ul) {
  list-style: circle;
  margin: 0.2em 0;
}
.prose-node-desc :deep(h1),
.prose-node-desc :deep(h2),
.prose-node-desc :deep(h3),
.prose-node-desc :deep(h4) {
  font-size: 0.875rem;
  font-weight: 500;
  color: #111827;
  margin: 1em 0 0.4em;
}
.prose-node-desc :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0 0 0.75em;
}
.prose-node-desc :deep(th),
.prose-node-desc :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 4px 8px;
  text-align: left;
}
.prose-node-desc :deep(code) {
  font-family: ui-monospace, monospace;
  font-size: 0.85em;
  background: #f3f4f6;
  padding: 0.1em 0.3em;
  border-radius: 3px;
}
.prose-node-desc :deep(blockquote) {
  border-left: 2px solid #e5e7eb;
  padding-left: 0.75em;
  color: #6b7280;
  margin: 0 0 0.75em;
}
</style>