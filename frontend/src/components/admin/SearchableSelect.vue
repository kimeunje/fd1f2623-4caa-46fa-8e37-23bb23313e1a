<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'

interface Option {
  value: number
  label: string
  sub?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: number | null
    options: Option[]
    placeholder?: string
    disabled?: boolean
  }>(),
  { placeholder: '선택…', disabled: false },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: number | null): void }>()

const open = ref(false)
const query = ref('')
const rootRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)

const selectedLabel = computed(() => {
  const o = props.options.find((o) => o.value === props.modelValue)
  return o ? o.label : ''
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.options
  return props.options.filter(
    (o) => o.label.toLowerCase().includes(q) || (o.sub?.toLowerCase().includes(q) ?? false),
  )
})

function openDropdown() {
  if (props.disabled) return
  open.value = true
  query.value = ''
}

function select(o: Option) {
  emit('update:modelValue', o.value)
  open.value = false
  query.value = ''
}

function onClickOutside(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

watch(open, async (v) => {
  if (v) {
    await nextTick()
    inputRef.value?.focus()
    document.addEventListener('mousedown', onClickOutside)
  } else {
    document.removeEventListener('mousedown', onClickOutside)
  }
})
</script>

<template>
  <div ref="rootRef" class="relative">
    <!-- 닫힘: 선택값/placeholder + chevron -->
    <div
      v-if="!open"
      @click="openDropdown"
      :class="[
        'w-full px-3 py-2 border rounded-lg text-sm flex items-center justify-between',
        disabled
          ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
          : 'bg-white cursor-pointer hover:border-gray-400',
      ]"
    >
      <span :class="['truncate', selectedLabel ? 'text-gray-800' : 'text-gray-400']">
        {{ selectedLabel || placeholder }}
      </span>
      <i class="pi pi-chevron-down text-xs text-gray-400 ml-2 shrink-0"></i>
    </div>

    <!-- 열림: 검색 input + 결과 목록 -->
    <div v-else>
      <input
        ref="inputRef"
        v-model="query"
        :placeholder="placeholder"
        class="w-full px-3 py-2 border rounded-lg text-sm bg-white"
      />
      <div
        class="absolute z-20 mt-1 w-full max-h-60 overflow-auto bg-white border border-gray-200 rounded-lg shadow-lg"
      >
        <button
          v-for="o in filtered"
          :key="o.value"
          type="button"
          @click="select(o)"
          :class="[
            'w-full text-left px-3 py-2 text-sm hover:bg-blue-50 flex flex-col',
            o.value === modelValue ? 'bg-blue-50/60' : '',
          ]"
        >
          <span class="text-gray-800 truncate">{{ o.label }}</span>
          <span v-if="o.sub" class="text-xs text-gray-400 truncate">{{ o.sub }}</span>
        </button>
        <div v-if="filtered.length === 0" class="px-3 py-2 text-sm text-gray-400">결과 없음</div>
      </div>
    </div>
  </div>
</template>