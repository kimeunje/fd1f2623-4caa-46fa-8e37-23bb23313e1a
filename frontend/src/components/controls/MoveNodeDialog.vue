<script setup lang="ts">
/**
 * Phase 5-14h — 이동 다이얼로그.
 *
 * 카테고리 ⋯ 액션 (다른 분류로 이동) 클릭 시 노출. tree.getMoveTargets(node) 결과를
 * 라디오 리스트로 표시. 자기 자손 + 깊이10 초과 케이스는 자동 제외 (composable 측 필터).
 *
 * z-index: 70.
 */

import { computed, ref } from 'vue'
import type { ControlTreeApi, UnifiedNode } from '@/composables/useControlTree'

const props = defineProps<{
  node: UnifiedNode
  tree: ControlTreeApi
}>()

const emit = defineEmits<{
  confirm: [payload: { node: UnifiedNode; newParent: UnifiedNode }]
  cancel: []
}>()

const targets = computed<UnifiedNode[]>(() => props.tree.getMoveTargets(props.node))
const selectedKey = ref<string | null>(null)

/** 노드의 ancestors path 빌드 — 라디오 라벨에 표시 ("1 ▸ 1.1 ▸ 1.1.2") */
function buildPath(node: UnifiedNode): string {
  const segments: string[] = [`${node.code}`]
  let cursor: UnifiedNode | null = node
  let guard = 0
  while (cursor && cursor.parentId !== null && guard++ < 12) {
    const parent: UnifiedNode | null = props.tree.findById(cursor.parentId)
    if (!parent) break
    segments.unshift(parent.code)
    cursor = parent
  }
  return segments.join(' ▸ ')
}

function handleConfirm(): void {
  if (!selectedKey.value) return
  const target = props.tree.findByKey(selectedKey.value)
  if (!target) return
  emit('confirm', { node: props.node, newParent: target })
}

function handleCancel(): void { emit('cancel') }
</script>

<template>
  <Teleport to="body">
    <div class="move-backdrop" @click.self="handleCancel">
      <div class="move-shell" role="dialog" aria-labelledby="move-title">
        <header class="move-header">
          <h3 id="move-title" class="move-title">"{{ node.name }}" 이동</h3>
          <button type="button" class="move-close" @click="handleCancel" aria-label="닫기">
            <i class="pi pi-times"></i>
          </button>
        </header>
        <div class="move-body">
          <p class="move-hint">이동할 위치를 선택하세요</p>
          <div v-if="targets.length === 0" class="move-empty">
            <!-- v15.1 5-15a 후속-2: hybrid 후 leaf 도 target. "분류" → "위치" -->
            이동 가능한 위치가 없습니다.
            <br />
            <span class="empty-sub">
              (자기 자신/자손은 제외되고, 깊이 10 을 초과하는 위치도 제외됩니다)
            </span>
          </div>
          <ul v-else class="target-list">
            <li v-for="t in targets" :key="t._key" class="target-item">
              <label class="target-label">
                <input
                  type="radio"
                  name="move-target"
                  :value="t._key"
                  v-model="selectedKey"
                  class="target-radio"
                />
                <span class="target-content">
                  <span class="target-path">{{ buildPath(t) }}</span>
                  <span class="target-name">{{ t.name }}</span>
                </span>
              </label>
            </li>
          </ul>
        </div>
        <footer class="move-footer">
          <button type="button" class="btn-secondary" @click="handleCancel">취소</button>
          <button
            type="button"
            class="btn-primary"
            :disabled="selectedKey === null || targets.length === 0"
            @click="handleConfirm">
            이동
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.move-backdrop {
  position: fixed; inset: 0;
  background: rgba(15, 23, 42, 0.6);
  z-index: 70;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.move-shell {
  width: min(520px, 100%);
  max-height: min(560px, 100%);
  background: white;
  border-radius: 12px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  display: flex; flex-direction: column;
  overflow: hidden;
}
.move-header {
  padding: 14px 20px;
  display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid rgb(229 231 235);
  flex-shrink: 0;
}
.move-title { font-size: 15px; font-weight: 600; color: rgb(17 24 39); margin: 0; }
.move-close {
  width: 28px; height: 28px;
  border: none; background: transparent;
  border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  color: rgb(156 163 175); cursor: pointer;
}
.move-close:hover { background: rgb(243 244 246); color: rgb(17 24 39); }
.move-body { padding: 16px 20px; overflow-y: auto; flex: 1; }
.move-hint { font-size: 12px; color: rgb(107 114 128); margin: 0 0 12px 0; }
.move-empty {
  padding: 32px 12px;
  text-align: center;
  font-size: 13px; color: rgb(107 114 128);
}
.empty-sub { font-size: 11px; color: rgb(156 163 175); }

.target-list { list-style: none; padding: 0; margin: 0; }
.target-item { margin-bottom: 4px; }
.target-label {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.1s;
}
.target-label:hover { background: rgb(249 250 251); }
.target-radio {
  width: 14px; height: 14px;
  flex-shrink: 0;
  accent-color: rgb(37 99 235);
}
.target-content {
  display: flex; flex-direction: column; gap: 2px;
  flex: 1; min-width: 0;
}
.target-path {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: rgb(107 114 128);
}
.target-name {
  font-size: 13px;
  color: rgb(17 24 39);
}

.move-footer {
  padding: 12px 20px;
  border-top: 1px solid rgb(229 231 235);
  background: rgb(249 250 251);
  display: flex; justify-content: flex-end; gap: 8px;
  flex-shrink: 0;
}
.btn-secondary {
  height: 32px; padding: 0 14px;
  background: white; color: rgb(17 24 39);
  border: 1px solid rgb(229 231 235); border-radius: 6px;
  font-size: 12px; font-weight: 500;
  cursor: pointer;
}
.btn-secondary:hover { background: rgb(243 244 246); }
.btn-primary {
  height: 32px; padding: 0 14px;
  background: rgb(17 24 39); color: white;
  border: none; border-radius: 6px;
  font-size: 12px; font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) { background: rgb(31 41 55); }
.btn-primary:disabled { background: rgb(209 213 219); cursor: not-allowed; }
</style>