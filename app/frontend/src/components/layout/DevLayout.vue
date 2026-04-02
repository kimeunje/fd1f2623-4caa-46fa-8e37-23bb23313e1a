<script setup lang="ts">
import DevSidebar from './DevSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<template>
  <div class="min-h-screen bg-gray-100">
    <DevSidebar />
    <div class="ml-60">
      <AppHeader />
      <main>
        <slot />
      </main>
    </div>
  </div>
</template>
