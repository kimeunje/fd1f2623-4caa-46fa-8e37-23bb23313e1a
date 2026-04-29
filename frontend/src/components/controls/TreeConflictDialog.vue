<script setup lang="ts">
/**
 * Phase 5-14h — 트리 동시 편집 충돌 다이얼로그 (409 version_mismatch).
 *
 * 다른 사용자가 먼저 PATCH /tree 를 수행해 expectedVersion 이 어긋났을 때 노출.
 * spec §3.3.1.4 의 409 응답을 받아 UnifiedControlsDialog 가 본 다이얼로그를 띄움.
 *
 * 5-14d Q1=B 로 현재는 lastEditedBy/At 을 응답에 포함하지 않으므로 currentVersion
 * 만 표시.
 *
 * 두 액션:
 *   [무시하고 닫기] — dirty 그대로 유지, 사용자가 수동으로 다시 시도 가능
 *   [최신 다시 불러오기] — load() 호출 (dirty 손실 + 트리 재로드)
 *
 * z-index: 70.
 */

defineProps<{
  currentVersion: number
}>()

const emit = defineEmits<{
  reload: []
  dismiss: []
}>()

function handleReload(): void { emit('reload') }
function handleDismiss(): void { emit('dismiss') }
</script>

<template>
  <Teleport to="body">
    <div class="conflict-backdrop" @click.self="handleDismiss">
      <div class="conflict-shell" role="alertdialog" aria-labelledby="conflict-title">
        <header class="conflict-header">
          <i class="pi pi-exclamation-circle conflict-icon"></i>
          <h3 id="conflict-title" class="conflict-title">동시 편집 충돌</h3>
        </header>
        <div class="conflict-body">
          <p class="conflict-msg">
            다른 사용자가 먼저 변경사항을 저장하여 트리가 갱신되었습니다.
          </p>
          <div class="version-info">
            <span class="version-label">최신 버전:</span>
            <span class="version-number">v{{ currentVersion }}</span>
          </div>
          <div class="conflict-options">
            <p class="options-title">다음 중 선택하세요:</p>
            <ul class="options-list">
              <li>
                <strong>[최신 다시 불러오기]</strong> — 미저장 변경 {N}건이 손실되며,
                서버의 최신 트리를 다시 받아옵니다. 충돌이 해결됩니다.
              </li>
              <li>
                <strong>[무시하고 닫기]</strong> — 미저장 변경은 그대로 유지됩니다.
                다시 [변경 저장] 시도 시 같은 충돌이 다시 발생합니다.
              </li>
            </ul>
          </div>
        </div>
        <footer class="conflict-footer">
          <button type="button" class="btn-secondary" @click="handleDismiss">
            무시하고 닫기
          </button>
          <button type="button" class="btn-primary" @click="handleReload">
            최신 다시 불러오기
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.conflict-backdrop {
  position: fixed; inset: 0;
  background: rgba(15, 23, 42, 0.6);
  z-index: 70;
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.conflict-shell {
  width: min(480px, 100%);
  background: white;
  border-radius: 12px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  overflow: hidden;
}
.conflict-header {
  padding: 16px 20px;
  display: flex; align-items: center; gap: 10px;
  border-bottom: 1px solid rgb(254 226 226);
  background: rgb(254 242 242);
}
.conflict-icon { color: rgb(220 38 38); font-size: 18px; }
.conflict-title { font-size: 15px; font-weight: 600; color: rgb(17 24 39); margin: 0; }
.conflict-body { padding: 18px 20px; }
.conflict-msg {
  font-size: 13px; color: rgb(55 65 81);
  margin: 0 0 12px 0;
  line-height: 1.5;
}
.version-info {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px;
  background: rgb(249 250 251);
  border-radius: 6px;
  margin-bottom: 14px;
}
.version-label { font-size: 12px; color: rgb(107 114 128); }
.version-number {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 14px; font-weight: 600;
  color: rgb(17 24 39);
}
.conflict-options { font-size: 12px; }
.options-title { color: rgb(75 85 99); margin: 0 0 6px 0; }
.options-list {
  list-style: none; padding: 0; margin: 0;
}
.options-list li {
  padding: 8px 12px;
  background: rgb(249 250 251);
  border-left: 3px solid rgb(229 231 235);
  border-radius: 4px;
  margin-bottom: 6px;
  color: rgb(75 85 99);
  line-height: 1.5;
}
.options-list li strong { color: rgb(17 24 39); }
.conflict-footer {
  padding: 12px 20px;
  border-top: 1px solid rgb(229 231 235);
  background: rgb(249 250 251);
  display: flex; justify-content: flex-end; gap: 8px;
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
.btn-primary:hover { background: rgb(31 41 55); }
</style>