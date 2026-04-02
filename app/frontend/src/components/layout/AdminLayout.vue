<script setup lang="ts">
import AdminSidebar from './AdminSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<template>
  <div class="min-h-screen bg-gray-100">
    <AdminSidebar />
    <div class="ml-60">
      <AppHeader />
      <main>
        <slot />
      </main>
    </div>
  </div>
</template>
