<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  DataLine,
  Document,
  Fold,
  Share,
  Stamp,
  Sunny,
  Moon,
  VideoPlay,
  Expand,
  SwitchButton,
  ArrowDown
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

interface NavItem {
  name: string
  label: string
  icon: typeof DataLine
}

const NAV_ITEMS: NavItem[] = [
  { name: 'dashboard', label: '概览', icon: DataLine },
  { name: 'workflows', label: '工作流', icon: Share },
  { name: 'executions', label: '执行记录', icon: VideoPlay },
  { name: 'approvals', label: '审批中心', icon: Stamp },
  { name: 'reports', label: '报告', icon: Document }
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { mode, toggleTheme } = useTheme()

const drawerOpen = ref(false)

const activeName = computed(() => {
  const parent = route.meta.parent
  return typeof parent === 'string' ? parent : (route.name as string)
})

const currentTitle = computed(() => (typeof route.meta.title === 'string' ? route.meta.title : ''))

watch(
  () => route.fullPath,
  () => {
    drawerOpen.value = false
  }
)

async function logout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="fp-shell">
    <!-- 侧边导航 -->
    <aside class="fp-sidebar" :class="{ 'fp-sidebar--open': drawerOpen }">
      <div class="fp-sidebar__brand">
        <div class="fp-sidebar__mark" aria-hidden="true">FP</div>
        <div class="fp-grow">
          <div class="fp-sidebar__name">FlowPilot</div>
          <div class="fp-sidebar__sub">管理控制台</div>
        </div>
      </div>

      <nav class="fp-nav" aria-label="主导航">
        <router-link
          v-for="item in NAV_ITEMS"
          :key="item.name"
          :to="{ name: item.name }"
          class="fp-nav__item"
          :class="{ 'fp-nav__item--active': activeName === item.name }"
        >
          <el-icon class="fp-nav__icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="fp-sidebar__foot">
        <div class="fp-sidebar__user">
          <div class="fp-avatar" aria-hidden="true">
            {{ auth.displayName.slice(0, 1).toUpperCase() }}
          </div>
          <div class="fp-grow">
            <div class="fp-sidebar__username fp-truncate">{{ auth.displayName }}</div>
            <div class="fp-sidebar__role">{{ auth.user?.role ?? '—' }}</div>
          </div>
        </div>
      </div>
    </aside>

    <div v-if="drawerOpen" class="fp-scrim" @click="drawerOpen = false" />

    <div class="fp-main">
      <header class="fp-topbar">
        <el-button
          class="fp-topbar__toggle"
          text
          :icon="drawerOpen ? Fold : Expand"
          aria-label="切换导航"
          @click="drawerOpen = !drawerOpen"
        />
        <div class="fp-topbar__title">{{ currentTitle }}</div>
        <div class="fp-grow" />
        <el-tooltip :content="mode === 'dark' ? '切换到浅色主题' : '切换到深色主题'" placement="bottom">
          <el-button
            text
            circle
            :icon="mode === 'dark' ? Sunny : Moon"
            aria-label="切换主题"
            @click="toggleTheme"
          />
        </el-tooltip>
        <el-dropdown trigger="click" @command="(command: string) => command === 'logout' && logout()">
          <el-button text class="fp-topbar__account">
            <span class="fp-avatar fp-avatar--sm" aria-hidden="true">
              {{ auth.displayName.slice(0, 1).toUpperCase() }}
            </span>
            <span class="fp-topbar__account-name">{{ auth.displayName }}</span>
            <el-icon class="fp-topbar__caret"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ auth.user?.username ?? '未登录' }}</el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="fp-content">
        <router-view v-slot="{ Component }">
          <transition name="fp-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.fp-shell {
  display: flex;
  min-height: 100vh;
}

.fp-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-w);
  padding: var(--sp-5) var(--sp-3) var(--sp-4);
  background: var(--fp-surface);
  border-right: 1px solid var(--fp-border);
}

.fp-sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 0 var(--sp-2) var(--sp-5);
}

.fp-sidebar__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--fp-accent);
  color: var(--fp-on-accent);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.fp-sidebar__name {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.fp-sidebar__sub {
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
}

.fp-nav__item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 9px var(--sp-3);
  border-radius: var(--fp-radius-sm);
  color: var(--fp-text-muted);
  font-size: 13.5px;
  font-weight: 500;
  transition:
    background-color 0.16s var(--ease),
    color 0.16s var(--ease);
}

.fp-nav__item:hover {
  background: var(--fp-surface-3);
  color: var(--fp-text);
}

.fp-nav__item--active {
  background: var(--fp-accent-soft);
  color: var(--fp-accent);
}

.fp-nav__icon {
  font-size: 16px;
}

.fp-sidebar__foot {
  padding-top: var(--sp-3);
  border-top: 1px solid var(--fp-border);
}

.fp-sidebar__user {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2);
}

.fp-sidebar__username {
  font-size: 13px;
  font-weight: 600;
}

.fp-sidebar__role {
  font-size: 11.5px;
  color: var(--fp-text-subtle);
}

.fp-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--fp-accent-soft);
  color: var(--fp-accent);
  font-size: 13px;
  font-weight: 650;
}

.fp-avatar--sm {
  width: 24px;
  height: 24px;
  font-size: 11px;
}

.fp-main {
  flex: 1 1 auto;
  min-width: 0;
  margin-left: var(--sidebar-w);
  display: flex;
  flex-direction: column;
}

.fp-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  height: var(--topbar-h);
  padding: 0 var(--sp-6);
  background: color-mix(in srgb, var(--fp-bg) 82%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--fp-border);
}

.fp-topbar__toggle {
  display: none;
}

.fp-topbar__title {
  font-size: 14px;
  font-weight: 600;
}

.fp-topbar__account {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 4px 8px;
  height: auto;
}

.fp-topbar__account-name {
  font-size: 13px;
  font-weight: 500;
}

.fp-topbar__caret {
  font-size: 12px;
  color: var(--fp-text-subtle);
}

.fp-content {
  flex: 1 1 auto;
}

.fp-scrim {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: var(--fp-overlay);
}

.fp-fade-enter-active,
.fp-fade-leave-active {
  transition: opacity 0.16s var(--ease);
}

.fp-fade-enter-from,
.fp-fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .fp-sidebar {
    transform: translateX(-100%);
    transition: transform 0.22s var(--ease);
    box-shadow: var(--fp-shadow-2);
  }

  .fp-sidebar--open {
    transform: translateX(0);
  }

  .fp-main {
    margin-left: 0;
  }

  .fp-topbar {
    padding: 0 var(--sp-3);
  }

  .fp-topbar__toggle {
    display: inline-flex;
  }

  .fp-topbar__account-name {
    display: none;
  }
}
</style>
