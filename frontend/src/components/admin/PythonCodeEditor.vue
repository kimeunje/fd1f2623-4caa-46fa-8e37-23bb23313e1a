<!--
  v18.9.9 — Python 코드 에디터 (CodeMirror 6 wrapper)

  본질: plain textarea 는 Python 의 critical 한 들여쓰기 / syntax 실수를 잡지 못함.
  CodeMirror 6 으로 syntax highlight + line number + 자동 들여쓰기 제공.

  ## 사용 패턴
  ```vue
  <PythonCodeEditor v-model="content" :disabled="loading" :height="500" />
  ```

  ## 기능
  - Python syntax highlight (`@codemirror/lang-python`)
  - Line numbers
  - 자동 들여쓰기 (Enter 시 이전 라인 indent 보존)
  - Tab 키 = 4 spaces (Python 표준)
  - bracket matching
  - 가로/세로 스크롤
  - disabled 시 read-only + 회색 처리

  ## 자동완성 / 폴딩 / linting
  포함 안 함 — 단순성 유지. 향후 phase 에서 추가 가능.
-->
<script setup lang="ts">
import { Codemirror } from 'vue-codemirror'
import { python } from '@codemirror/lang-python'
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { indentUnit, bracketMatching } from '@codemirror/language'
import { Compartment } from '@codemirror/state'
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    disabled?: boolean
    height?: number
  }>(),
  { disabled: false, height: 480 },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

const editableCompartment = new Compartment()

// CodeMirror extensions — 본 프로젝트 admin UI light 기조 정합
const extensions = computed(() => [
  python(),
  lineNumbers(),
  highlightActiveLine(),
  bracketMatching(),
  indentUnit.of('    '),                                     // Python 표준 4 spaces
  EditorView.lineWrapping,
  editableCompartment.of(EditorView.editable.of(!props.disabled)),
  EditorView.theme({
    '&': {
      fontSize: '12px',
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    },
    '.cm-content': {
      caretColor: '#1f2937',
      padding: '8px 0',
    },
    '.cm-gutters': {
      backgroundColor: '#f9fafb',
      borderRight: '1px solid #e5e7eb',
      color: '#9ca3af',
    },
    '.cm-activeLine': {
      backgroundColor: '#f3f4f6',
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#e5e7eb',
      color: '#4b5563',
    },
    '&.cm-focused': {
      outline: 'none',
    },
  }, { dark: false }),
])

function onChange(v: string) {
  emit('update:modelValue', v)
}
</script>

<template>
  <div
    class="rounded-lg overflow-hidden border"
    :class="disabled ? 'bg-gray-50 border-gray-200' : 'bg-white border-gray-300'">
    <Codemirror
      :model-value="modelValue"
      :extensions="extensions"
      :disabled="disabled"
      :style="{ height: `${height}px` }"
      :indent-with-tab="true"
      @update:model-value="onChange"
    />
  </div>
</template>