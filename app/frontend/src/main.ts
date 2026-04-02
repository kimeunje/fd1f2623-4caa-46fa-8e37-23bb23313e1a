import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

import 'primeicons/primeicons.css'
import './assets/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

// Auth 초기화 (localStorage에서 토큰 복원)
const authStore = useAuthStore()
authStore.initialize()

app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: Aura,
    options: {
      darkModeSelector: false,
    },
  },
})
app.use(ToastService)
app.use(ConfirmationService)

app.mount('#app')
