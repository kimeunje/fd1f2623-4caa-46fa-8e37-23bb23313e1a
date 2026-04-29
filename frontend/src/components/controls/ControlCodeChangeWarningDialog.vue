<script setup lang="ts">
/**
 * Phase 5-14h — 통제 코드 변경 사전 경고 다이얼로그 (spec §3.3.1.5).
 *
 * 기존 leaf 의 코드 input 에서 blur 시 ImpactSummary 합산 > 0 인 경우 노출.
 * old → new 화살표 + 영향 카운트 + 권장사항. [취소] 시 코드 되돌림 (UnifiedControlsDialog
 * 가 tree.setCode(node, oldCode) 호출), [변경] 시 dirty 유지.
 *
 * z-index: 70 (UnifiedControlsDialog 55 + ImportControlsDialog 60 위에 stacking).
 */

import type { UnifiedNode } from '@/composables/useControlTree'
import type { ImpactSummary } from '@/types/evidence'

defineProps<{
  node: UnifiedNode
  oldCode: string
  newCode: string
  impact: ImpactSummary
}>()

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

function handleConfirm(): void { emit('confirm') }
function handleCancel(): void { emit('cancel') }
</script>

<template>
  <Teleport to="body">
    <div class="warn-backdrop" @click.self="handleCancel">
      <div class="warn-shell" role="alertdialog" aria-labelledby="code-warn-title">
        <header class="warn-header">
          <i class="pi pi-exclamation-triangle warn-icon"></i>
          <h3 id="code-warn-title" class="warn-title">통제 코드 변경 영향</h3>
        </header>
        <div class="warn-body">
          <div class="code-arrow">
            <span class="code-old">{{ oldCode }}</span>
            <i class="pi pi-arrow-right code-arrow-icon"></i>
            <span class="code-new">{{ newCode }}</span>
          </div>
          <p class="warn-name">"{{ node.name }}" — 이 통제의 코드를 변경합니다</p>
          <div class="impact-grid">
            <div class="impact-item" :class="{ active: impact.evidenceFileCount > 0 }">
              <span class="impact-num">{{ impact.evidenceFileCount }}</span>
              <span class="impact-label">증빙 파일</span>
            </div>
            <div class="impact-item" :class="{ active: impact.jobCount > 0 }">
              <span class="impact-num">{{ impact.jobCount }}</span>
              <span class="impact-label">수집 작업</span>
            </div>
            <div class="impact-item" :class="{ active: impact.reviewCount > 0 }">
              <span class="impact-num">{{ impact.reviewCount }}</span>
              <span class="impact-label">검토 이력</span>
            </div>
          </div>
          <div class="warn-affects">
            <p class="affects-title">코드 변경 시 다음 위치에 영향이 있습니다:</p>
            <ul class="affects-list">
              <li>이미 발송된 이메일/PDF 문서의 코드 표기</li>
              <li>외부 공유 링크 / 보고서 / 감사 이력의 참조</li>
              <li>증빙 파일 ZIP 다운로드 명명 규칙</li>
            </ul>
          </div>
          <div class="warn-recommend">
            <i class="pi pi-info-circle"></i>
            가능하면 외부 공유나 감사 진행 중인 통제의 코드는 변경하지 않는 것을 권장합니다.
          </div>
        </div>
        <footer class="warn-footer">
          <button type="button" class="btn-secondary" @click="handleCancel">
            취소 (코드 되돌림)
          </button>
          <button type="button" class="btn-warn" @click="handleConfirm">
            변경 진행
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.warn-backdrop {
  position: fixed; inset: 0;
  background: rgba(15, 23, 42, 0.6);
  z-index: 70;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.warn-shell {
  width: min(480px, 100%);
  background: white;
  border-radius: 12px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  overflow: hidden;
}
.warn-header {
  padding: 16px 20px;
  display: flex; align-items: center; gap: 10px;
  border-bottom: 1px solid rgb(254 226 226);
  background: rgb(254 242 242);
}
.warn-icon { color: rgb(217 119 6); font-size: 18px; }
.warn-title { font-size: 15px; font-weight: 600; color: rgb(17 24 39); margin: 0; }
.warn-body { padding: 20px; }
.code-arrow {
  display: flex; align-items: center; justify-content: center; gap: 12px;
  padding: 12px;
  background: rgb(249 250 251);
  border-radius: 8px;
  margin-bottom: 12px;
}
.code-old, .code-new {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 14px; font-weight: 500;
  padding: 4px 10px; border-radius: 4px;
}
.code-old { background: rgb(254 226 226); color: rgb(153 27 27); text-decoration: line-through; }
.code-new { background: rgb(220 252 231); color: rgb(22 101 52); }
.code-arrow-icon { color: rgb(156 163 175); font-size: 14px; }
.warn-name {
  text-align: center;
  font-size: 13px; color: rgb(75 85 99);
  margin: 0 0 16px 0;
}
.impact-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 16px;
}
.impact-item {
  display: flex; flex-direction: column; align-items: center;
  padding: 10px 8px;
  background: rgb(249 250 251);
  border-radius: 8px;
  border: 1px solid rgb(229 231 235);
}
.impact-item.active {
  background: rgb(254 242 242);
  border-color: rgb(252 165 165);
}
.impact-num {
  font-size: 20px; font-weight: 700;
  color: rgb(107 114 128);
  font-variant-numeric: tabular-nums;
}
.impact-item.active .impact-num { color: rgb(220 38 38); }
.impact-label { font-size: 11px; color: rgb(107 114 128); margin-top: 2px; }
.warn-affects { margin-bottom: 12px; }
.affects-title { font-size: 12px; color: rgb(75 85 99); margin: 0 0 6px 0; }
.affects-list {
  list-style: disc; padding-left: 20px;
  font-size: 12px; color: rgb(107 114 128); margin: 0;
}
.affects-list li { margin: 2px 0; }
.warn-recommend {
  padding: 10px 12px;
  background: rgb(255 251 235);
  border: 1px solid rgb(252 211 77);
  border-radius: 6px;
  font-size: 12px; color: rgb(120 53 15);
  display: flex; align-items: flex-start; gap: 6px;
}
.warn-footer {
  padding: 12px 20px;
  border-top: 1px solid rgb(229 231 235);
  display: flex; justify-content: flex-end; gap: 8px;
  background: rgb(249 250 251);
}
.btn-secondary {
  height: 32px; padding: 0 14px;
  background: white; color: rgb(17 24 39);
  border: 1px solid rgb(229 231 235); border-radius: 6px;
  font-size: 12px; font-weight: 500;
  cursor: pointer;
}
.btn-secondary:hover { background: rgb(243 244 246); }
.btn-warn {
  height: 32px; padding: 0 14px;
  background: rgb(217 119 6); color: white;
  border: none; border-radius: 6px;
  font-size: 12px; font-weight: 500;
  cursor: pointer;
}
.btn-warn:hover { background: rgb(180 83 9); }
</style>