<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
</script>

<template>
  <div class="p-6 space-y-6">
    <!-- 환영 메시지 -->
    <div class="bg-white rounded-xl border border-gray-200 p-6">
      <h2 class="text-xl font-bold text-gray-900 mb-2">
        안녕하세요, {{ authStore.user?.name }}님 👋
      </h2>
      <p class="text-gray-500">SecuHub 보안 운영 통합 관리 플랫폼에 오신 것을 환영합니다.</p>
    </div>

    <!-- 통계 카드 -->
    <div class="grid grid-cols-2 gap-6">
      <!-- 증빙 수집 현황 -->
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center justify-between mb-4">
          <h3 class="font-bold text-gray-900">증빙 수집 현황</h3>
          <span class="text-xs text-gray-500">ISMS-P 기준</span>
        </div>
        <div class="grid grid-cols-4 gap-3 mb-4">
          <div v-for="stat in [
            { label: '전체', value: 247, color: 'text-gray-900' },
            { label: '완료', value: 198, color: 'text-green-600' },
            { label: '미수집', value: 42, color: 'text-amber-600' },
            { label: '실패', value: 7, color: 'text-red-600' },
          ]" :key="stat.label" class="text-center">
            <p :class="['text-2xl font-bold', stat.color]">{{ stat.value }}</p>
            <p class="text-xs text-gray-500">{{ stat.label }}</p>
          </div>
        </div>
        <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
          <div class="h-full bg-green-500 rounded-full" style="width: 80%"></div>
        </div>
      </div>

      <!-- 취약점 조치 현황 -->
      <div class="bg-white rounded-xl border border-gray-200 p-5">
        <div class="flex items-center justify-between mb-4">
          <h3 class="font-bold text-gray-900">취약점 조치 현황</h3>
          <span class="text-xs text-gray-500">2025년 1분기</span>
        </div>
        <div class="grid grid-cols-4 gap-3 mb-4">
          <div v-for="stat in [
            { label: '전체', value: 156, color: 'text-gray-900' },
            { label: '완료', value: 98, color: 'text-green-600' },
            { label: '진행중', value: 34, color: 'text-blue-600' },
            { label: '미조치', value: 24, color: 'text-red-600' },
          ]" :key="stat.label" class="text-center">
            <p :class="['text-2xl font-bold', stat.color]">{{ stat.value }}</p>
            <p class="text-xs text-gray-500">{{ stat.label }}</p>
          </div>
        </div>
        <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
          <div class="h-full bg-green-500 rounded-full" style="width: 63%"></div>
        </div>
      </div>
    </div>

    <!-- 긴급 취약점 + 예정된 수집 -->
    <div class="grid grid-cols-2 gap-6">
      <div class="bg-white rounded-xl border border-gray-200">
        <div class="p-4 border-b border-gray-200 flex items-center gap-2">
          <i class="pi pi-exclamation-triangle text-red-500"></i>
          <h3 class="font-bold text-gray-900">긴급 조치 필요</h3>
        </div>
        <div class="divide-y divide-gray-100">
          <div
            v-for="item in [
              { id: 'VUL-0089', name: 'SQL Injection', due: 'D-1', assignee: '김개발' },
              { id: 'VUL-0092', name: 'XSS 취약점', due: 'D-3', assignee: '이보안' },
              { id: 'VUL-0095', name: '인증 우회', due: 'D-5', assignee: '박백엔드' },
            ]"
            :key="item.id"
            class="p-3 hover:bg-gray-50 cursor-pointer"
          >
            <div class="flex items-center justify-between">
              <span class="font-mono text-xs text-red-600">{{ item.id }}</span>
              <span class="px-1.5 py-0.5 bg-red-100 text-red-700 text-xs font-bold rounded">{{ item.due }}</span>
            </div>
            <p class="text-sm text-gray-900 mt-1">{{ item.name }}</p>
            <p class="text-xs text-gray-500">담당: {{ item.assignee }}</p>
          </div>
        </div>
      </div>

      <div class="bg-white rounded-xl border border-gray-200">
        <div class="p-4 border-b border-gray-200 flex items-center gap-2">
          <i class="pi pi-calendar text-blue-500"></i>
          <h3 class="font-bold text-gray-900">예정된 수집</h3>
        </div>
        <div class="divide-y divide-gray-100">
          <div
            v-for="item in [
              { name: '접근권한 현황 추출', time: '오늘 18:00' },
              { name: '백신 업데이트 현황', time: '내일 09:00' },
              { name: '방화벽 정책 스크린샷', time: '내일 14:00' },
            ]"
            :key="item.name"
            class="p-3 hover:bg-gray-50 cursor-pointer"
          >
            <p class="text-sm text-gray-900">{{ item.name }}</p>
            <p class="text-xs text-gray-500 mt-1">{{ item.time }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
