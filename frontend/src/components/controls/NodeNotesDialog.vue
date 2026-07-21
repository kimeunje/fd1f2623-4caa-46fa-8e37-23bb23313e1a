<script setup lang="ts">
/**
 * v19.27 — NodeNotesDialog.vue
 *
 * 관리 항목 인수인계 노트. 관리 항목당 여러 노트를 시간순 누적한다.
 * 단일 설명(v19.23)을 대체 — 편집 시 덮어쓰지 않고 새 노트가 쌓인다.
 *
 * <h3>독립 저장</h3>
 * 노트는 트리 PATCH(낙관적 락)와 무관한 자체 API(notesApi)로 즉시 반영된다.
 * 따라서 dirty/version/충돌 처리가 없다 — 추가/수정/삭제가 곧바로 서버에 반영된다.
 *
 * <h3>작성자</h3>
 * 관리자 계정이 공용일 수 있어 작성자 이름을 직접 입력한다(로그인 계정과 무관).
 * 추가 폼의 작성자 칸은 defaultAuthor(로그인 관리자 이름)로 pre-fill 하되 편집 가능.
 *
 * <h3>draft 항목</h3>
 * 아직 저장되지 않은 트리 항목(nodeId=null)은 노트를 붙일 수 없다 — 안내만 표시.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { notesApi } from '@/services/api'
import type { ControlNodeNote } from '@/types'
import { renderMarkdown } from '@/composables/useMarkdown'

interface Props {
  open: boolean
  /** 대상 관리 항목 id. null = 아직 저장 안 된 항목(노트 추가 불가). */
  nodeId: number | null
  nodeCode: string
  nodeName: string
  /** 추가 폼 작성자 pre-fill (로그인 관리자 이름). */
  defaultAuthor?: string
}

const props = withDefaults(defineProps<Props>(), { defaultAuthor: '' })

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  /** 노트가 추가/수정/삭제됨 — 부모(본문 패널)가 목록을 다시 불러온다. */
  (e: 'changed'): void
}>()

const notes = ref<ControlNodeNote[]>([])
const loading = ref(false)
const loadError = ref('')

// 추가 폼
const newAuthor = ref('')
const newBody = ref('')
const adding = ref(false)
const newBodyRef = ref<HTMLTextAreaElement | null>(null)

// 인라인 수정
const editingId = ref<number | null>(null)
const editAuthor = ref('')
const editBody = ref('')
const savingEdit = ref(false)

const canAdd = computed(
  () => props.nodeId != null && newAuthor.value.trim() !== '' && newBody.value.trim() !== '',
)

function fmtDate(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso.slice(0, 10) : d.toISOString().slice(0, 10)
}
function noteMeta(n: ControlNodeNote): string {
  const date = fmtDate(n.createdAt)
  const base = date && n.authorName ? `${date} · ${n.authorName}` : date || n.authorName
  return n.edited ? `${base} · 수정됨` : base
}
function renderBody(body: string): string {
  return renderMarkdown(body)
}

async function loadNotes() {
  if (props.nodeId == null) {
    notes.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await notesApi.list(props.nodeId)
    notes.value = data.success ? data.data : []
  } catch {
    loadError.value = '노트를 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  async (v) => {
    if (!v) return
    // 초기화
    editingId.value = null
    newBody.value = ''
    newAuthor.value = props.defaultAuthor
    await loadNotes()
    await nextTick()
    newBodyRef.value?.focus()
  },
  { immediate: true },
)

async function addNote() {
  if (!canAdd.value || props.nodeId == null) return
  adding.value = true
  try {
    const { data } = await notesApi.create(props.nodeId, {
      authorName: newAuthor.value.trim(),
      body: newBody.value,
    })
    if (data.success) {
      notes.value.push(data.data)
      newBody.value = ''
      // 작성자는 유지 — 같은 사람이 연속 작성하는 경우가 흔함
      emit('changed')
      await nextTick()
      newBodyRef.value?.focus()
    }
  } catch {
    loadError.value = '노트 추가에 실패했습니다.'
  } finally {
    adding.value = false
  }
}

function startEdit(n: ControlNodeNote) {
  editingId.value = n.id
  editAuthor.value = n.authorName
  editBody.value = n.body
}
function cancelEdit() {
  editingId.value = null
}
async function saveEdit(n: ControlNodeNote) {
  if (props.nodeId == null) return
  if (editAuthor.value.trim() === '' || editBody.value.trim() === '') return
  savingEdit.value = true
  try {
    const { data } = await notesApi.update(props.nodeId, n.id, {
      authorName: editAuthor.value.trim(),
      body: editBody.value,
    })
    if (data.success) {
      const idx = notes.value.findIndex((x) => x.id === n.id)
      if (idx !== -1) notes.value[idx] = data.data
      editingId.value = null
      emit('changed')
    }
  } catch {
    loadError.value = '노트 수정에 실패했습니다.'
  } finally {
    savingEdit.value = false
  }
}
async function removeNote(n: ControlNodeNote) {
  if (props.nodeId == null) return
  if (!window.confirm('이 노트를 삭제할까요? 되돌릴 수 없습니다.')) return
  try {
    await notesApi.delete(props.nodeId, n.id)
    notes.value = notes.value.filter((x) => x.id !== n.id)
    emit('changed')
  } catch {
    loadError.value = '노트 삭제에 실패했습니다.'
  }
}

function requestClose() {
  emit('update:open', false)
}
function onKeydown(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.stopPropagation() // 뒤의 UnifiedControlsDialog 가 함께 닫히지 않도록
    requestClose()
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, true))
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[65] flex items-center justify-center bg-black/45 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="node-notes-title"
    >
      <div class="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-xl bg-white shadow-xl">
        <header class="flex items-center justify-between border-b border-gray-200 px-5 py-3">
          <div class="flex items-baseline gap-2">
            <h2 id="node-notes-title" class="text-sm font-medium text-gray-900">인수인계 노트</h2>
            <span class="font-mono text-xs text-blue-600">{{ nodeCode }}</span>
            <span class="text-xs text-gray-500">{{ nodeName }}</span>
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

        <!-- 노트 목록 -->
        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <p v-if="loading" class="text-xs text-gray-400">불러오는 중…</p>
          <p v-else-if="loadError" class="text-xs text-red-500">{{ loadError }}</p>
          <p v-else-if="notes.length === 0" class="text-xs text-gray-400">
            아직 인수인계 노트가 없습니다. 아래에서 첫 노트를 추가하세요.
          </p>

          <ul v-else class="flex flex-col gap-3">
            <li
              v-for="n in notes"
              :key="n.id"
              class="rounded-lg border border-gray-200 p-3"
            >
              <!-- 인라인 수정 -->
              <div v-if="editingId === n.id" class="flex flex-col gap-2">
                <input
                  v-model="editAuthor"
                  type="text"
                  placeholder="작성자"
                  class="w-48 rounded-md border border-gray-300 px-2 py-1 text-xs focus:border-blue-400 focus:outline-none"
                />
                <textarea
                  v-model="editBody"
                  rows="4"
                  class="w-full resize-y rounded-md border border-gray-300 bg-gray-50 p-2 font-mono text-xs leading-relaxed focus:border-blue-400 focus:outline-none"
                ></textarea>
                <div class="flex justify-end gap-2">
                  <button
                    type="button"
                    class="rounded-md border border-gray-300 px-2.5 py-1 text-xs text-gray-600 hover:bg-gray-50"
                    :disabled="savingEdit"
                    @click="cancelEdit"
                  >
                    취소
                  </button>
                  <button
                    type="button"
                    class="rounded-md bg-blue-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                    :disabled="savingEdit || !editAuthor.trim() || !editBody.trim()"
                    @click="saveEdit(n)"
                  >
                    {{ savingEdit ? '저장 중…' : '저장' }}
                  </button>
                </div>
              </div>

              <!-- 읽기 -->
              <div v-else>
                <div class="mb-1.5 flex items-center justify-between">
                  <span class="text-[11px] text-gray-400">{{ noteMeta(n) }}</span>
                  <div class="flex gap-1">
                    <button
                      type="button"
                      class="rounded px-1.5 py-0.5 text-[11px] text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                      @click="startEdit(n)"
                    >
                      수정
                    </button>
                    <button
                      type="button"
                      class="rounded px-1.5 py-0.5 text-[11px] text-gray-400 hover:bg-red-50 hover:text-red-600"
                      @click="removeNote(n)"
                    >
                      삭제
                    </button>
                  </div>
                </div>
                <div class="prose-node-desc text-sm leading-relaxed text-gray-700" v-html="renderBody(n.body)"></div>
              </div>
            </li>
          </ul>
        </div>

        <!-- 추가 폼 -->
        <footer class="border-t border-gray-200 px-5 py-3">
          <p v-if="nodeId == null" class="text-xs text-amber-600">
            항목을 저장한 뒤 노트를 추가할 수 있습니다.
          </p>
          <div v-else class="flex flex-col gap-2">
            <input
              v-model="newAuthor"
              type="text"
              placeholder="작성자 이름"
              class="w-56 rounded-md border border-gray-300 px-2.5 py-1.5 text-xs focus:border-blue-400 focus:outline-none"
            />
            <textarea
              ref="newBodyRef"
              v-model="newBody"
              rows="4"
              placeholder="다음 담당자를 위한 인수인계 메모&#10;예) 이 증빙은 운영실 김프로에게 매분기 요청해서 받음. 2024년부터 양식이 바뀜."
              class="w-full resize-y rounded-md border border-gray-300 bg-gray-50 p-2.5 font-mono text-xs leading-relaxed focus:border-blue-400 focus:outline-none"
            ></textarea>
            <div class="flex justify-end">
              <button
                type="button"
                class="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                :disabled="!canAdd || adding"
                @click="addNote"
              >
                {{ adding ? '추가 중…' : '노트 추가' }}
              </button>
            </div>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* prose-node-desc :deep 규칙은 ControlNodeRow.vue 와 동일 — v19.27 추후 shared css 추출 대상. */
.prose-node-desc :deep(p) { margin: 0 0 0.6em; }
.prose-node-desc :deep(strong) { font-weight: 500; color: #111827; }
.prose-node-desc :deep(em) { font-style: italic; }
.prose-node-desc :deep(ul),
.prose-node-desc :deep(ol) { margin: 0 0 0.6em; padding-left: 1.25em; list-style: disc; }
.prose-node-desc :deep(ol) { list-style: decimal; }
.prose-node-desc :deep(li) { margin: 0.2em 0; }
.prose-node-desc :deep(li > ul) { list-style: circle; margin: 0.2em 0; }
.prose-node-desc :deep(h1),
.prose-node-desc :deep(h2),
.prose-node-desc :deep(h3),
.prose-node-desc :deep(h4) {
  font-size: 0.9rem; font-weight: 500; color: #111827; margin: 0.9em 0 0.35em;
}
.prose-node-desc :deep(table) { width: 100%; border-collapse: collapse; margin: 0 0 0.6em; }
.prose-node-desc :deep(th),
.prose-node-desc :deep(td) { border: 1px solid #e5e7eb; padding: 4px 8px; text-align: left; }
.prose-node-desc :deep(code) {
  font-family: ui-monospace, monospace; font-size: 0.85em;
  background: #f3f4f6; padding: 0.1em 0.3em; border-radius: 3px;
}
.prose-node-desc :deep(blockquote) {
  border-left: 2px solid #e5e7eb; padding-left: 0.75em; color: #6b7280; margin: 0 0 0.6em;
}
</style>