<!--
  v18.9.9 — Python 코드 에디터 (CodeMirror 6 wrapper)
  v19.6 — 선택 영역 하이라이트 가시성 fix.

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

  ## v19.6 — 선택 영역 가시성 fix
  CodeMirror 6 은 선택 영역(drawSelection)을 텍스트 뒤 레이어에 그린다. 활성 줄에
  불투명 배경(.cm-activeLine)을 깔면 그 배경이 "같은 줄" 선택 영역을 덮어버려, 한 줄
  안에서 드래그/Shift 선택 시 하이라이트가 안 보였다(여러 줄은 비활성 줄들이 보여서
  보이는 것처럼 느껴짐). → 활성 줄 배경을 반투명으로 바꿔 선택이 비쳐 보이게 하고,
  선택 배경색을 또렷한 보라색으로 명시.

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
    // v19.6 — 반투명. 뒤쪽 선택 레이어가 비쳐 보이도록(같은 줄 선택 가시성).
    '.cm-activeLine': {
      backgroundColor: 'rgba(243, 244, 246, 0.5)',
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#e5e7eb',
      color: '#4b5563',
    },
    // v19.6 — 선택 영역 보라색 명시 (drawSelection 레이어 + 네이티브 fallback).
    '.cm-selectionBackground': {
      backgroundColor: '#ddd6fe !important',   // violet-200
    },
    '&.cm-focused .cm-selectionBackground': {
      backgroundColor: '#c4b5fd !important',   // violet-300 (포커스 시 더 또렷)
    },
    '.cm-content ::selection': {
      backgroundColor: '#c4b5fd',
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