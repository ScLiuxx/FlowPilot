import { createPinia } from 'pinia'

/** 全局 Pinia 实例：供 api 层在非组件环境读取 auth store */
export const pinia = createPinia()
