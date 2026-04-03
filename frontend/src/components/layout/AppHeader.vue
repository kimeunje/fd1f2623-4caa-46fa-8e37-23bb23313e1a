<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const title = computed(() => (route.meta.title as string) || '')
</script>

<template>
  <header class="h-16 bg-white border-b border-gray-200 flex items-center justify-between px-6 sticky top-0 z-10">
    <div>
      <h1 class="text-lg font-bold text-gray-900">{{ title }}</h1>
    </div>
    <div class="flex items-center gap-3">
      <button class="relative p-2 text-gray-400 hover:text-gray-600 transition-colors">
        <i class="pi pi-bell text-lg"></i>
        <span class="absolute top-1 right-1 w-2 h-2 bg-red-500 rounded-full"></span>
      </button>
    </div>
  </header>
</template>
