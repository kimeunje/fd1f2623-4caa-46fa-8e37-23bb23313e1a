<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="p-6">
    <div class="bg-white rounded-xl border border-gray-200 p-12 text-center">
      <i class="pi pi-wrench text-4xl text-gray-300 mb-4"></i>
      <h2 class="text-xl font-bold text-gray-900 mb-2">{{ route.meta.title }}</h2>
      <p class="text-gray-500">이 페이지는 다음 Phase에서 구현 예정입니다.</p>
      <p class="text-sm text-gray-400 mt-2">Route: {{ route.path }}</p>
    </div>
  </div>
</template>
