<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AdminLayout from '@/components/layout/AdminLayout.vue'
import DevLayout from '@/components/layout/DevLayout.vue'

const route = useRoute()

const layout = computed(() => {
  return (route.meta.layout as string) || 'blank'
})
</script>

<template>
  <AdminLayout v-if="layout === 'admin'">
    <router-view />
  </AdminLayout>

  <DevLayout v-else-if="layout === 'dev'">
    <router-view />
  </DevLayout>

  <router-view v-else />
</template>
