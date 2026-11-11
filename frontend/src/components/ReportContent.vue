<script setup lang="ts">
import { computed } from 'vue'
import type { ReportContent as ReportContentShape } from '@/api/types'

/**
 * 渲染报告的结构化内容。
 * 契约未固定 content 的具体形状，因此这里做容错处理：
 * - { sections: [...] }：章节数组
 * - [ ... ]：直接是章节数组
 * - { key: value }：键值对（值为对象时递归展开）
 */

interface Props {
  content?: ReportContentShape | null
}

const props = defineProps<Props>()

type SectionValue = unknown

interface Section {
  key: string
  title: string
  body: SectionValue
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function titleOf(value: Record<string, unknown>, fallback: string): string {
  for (const key of ['title', 'name', 'heading', 'label']) {
    const candidate = value[key]
    if (typeof candidate === 'string' && candidate.trim()) return candidate
  }
  return fallback
}

function bodyOf(value: Record<string, unknown>): SectionValue {
  for (const key of ['content', 'body', 'text', 'value', 'data']) {
    if (key in value) return value[key]
  }
  const rest = { ...value }
  delete rest.title
  delete rest.name
  delete rest.heading
  delete rest.label
  return rest
}

const sections = computed<Section[]>(() => {
  const raw = props.content
  if (!raw) return []

  const source = Array.isArray(raw.sections) ? raw.sections : Array.isArray(raw) ? raw : null

  if (source) {
    return source.map((item, index) => {
      if (isPlainObject(item)) {
        return {
          key: `section-${index}`,
          title: titleOf(item, `章节 ${index + 1}`),
          body: bodyOf(item)
        }
      }
      return { key: `section-${index}`, title: `章节 ${index + 1}`, body: item }
    })
  }

  return Object.entries(raw)
    .filter(([key]) => key !== 'sections')
    .map(([key, value]) => ({ key, title: key, body: value }))
})

const hasContent = computed(() => sections.value.length > 0)

/** 段落列表：数组中的对象由「子章节」渲染，此处只取纯文本项 */
function asParagraphs(value: SectionValue): string[] {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === 'string')
  }
  return []
}

/** 键值对 */
function asEntries(value: SectionValue): [string, unknown][] {
  return isPlainObject(value) ? Object.entries(value) : []
}

/** 子章节 */
function asSubsections(value: SectionValue): Section[] {
  if (!Array.isArray(value)) return []
  return value.map((item, index) => {
    if (isPlainObject(item)) {
      return { key: `sub-${index}`, title: titleOf(item, `子章节 ${index + 1}`), body: bodyOf(item) }
    }
    return { key: `sub-${index}`, title: `子章节 ${index + 1}`, body: item }
  })
}
</script>

<template>
  <div v-if="hasContent" class="fp-rpt-content">
    <section v-for="section in sections" :key="section.key" class="fp-rpt-content__section">
      <h3 class="fp-rpt-content__title">{{ section.title }}</h3>

      <p
        v-for="(paragraph, index) in asParagraphs(section.body)"
        :key="`p-${index}`"
        class="fp-rpt-content__paragraph"
      >
        {{ paragraph }}
      </p>

      <dl v-if="asEntries(section.body).length" class="fp-rpt-content__entries">
        <template v-for="[key, value] in asEntries(section.body)" :key="key">
          <dt>{{ key }}</dt>
          <dd>
            <span v-if="typeof value === 'string' || typeof value === 'number'">{{ value }}</span>
            <span v-else-if="value === null">—</span>
            <pre v-else>{{ JSON.stringify(value, null, 2) }}</pre>
          </dd>
        </template>
      </dl>

      <div v-if="asSubsections(section.body).length" class="fp-rpt-content__subs">
        <div v-for="sub in asSubsections(section.body)" :key="sub.key" class="fp-rpt-content__sub">
          <h4 class="fp-rpt-content__subtitle">{{ sub.title }}</h4>
          <p
            v-for="(paragraph, index) in asParagraphs(sub.body)"
            :key="`sp-${index}`"
            class="fp-rpt-content__paragraph"
          >
            {{ paragraph }}
          </p>
          <pre v-if="!asParagraphs(sub.body).length && !asEntries(sub.body).length" class="fp-rpt-content__code">{{
            JSON.stringify(sub.body, null, 2)
          }}</pre>
        </div>
      </div>
    </section>
  </div>

  <p v-else class="fp-muted fp-rpt-content__empty">该报告没有结构化内容，可下载 Markdown 查看原文。</p>
</template>

<style scoped>
.fp-rpt-content {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
}

.fp-rpt-content__section {
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--fp-border);
}

.fp-rpt-content__section:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.fp-rpt-content__title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: var(--sp-3);
}

.fp-rpt-content__subtitle {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fp-text-muted);
  margin-bottom: var(--sp-2);
}

.fp-rpt-content__paragraph {
  margin: 0 0 var(--sp-2);
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--fp-text);
}

.fp-rpt-content__entries {
  display: grid;
  grid-template-columns: minmax(120px, 200px) minmax(0, 1fr);
  gap: var(--sp-2) var(--sp-4);
  margin: var(--sp-3) 0 0;
}

.fp-rpt-content__entries dt {
  font-size: 12px;
  font-weight: 600;
  color: var(--fp-text-subtle);
  text-transform: capitalize;
}

.fp-rpt-content__entries dd {
  margin: 0;
  font-size: 13.5px;
  word-break: break-word;
}

.fp-rpt-content__entries pre,
.fp-rpt-content__code {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-surface-3);
  font-size: 11.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.fp-rpt-content__subs {
  margin-top: var(--sp-4);
  padding-left: var(--sp-4);
  border-left: 2px solid var(--fp-border);
}

.fp-rpt-content__empty {
  font-size: 13px;
}

@media (max-width: 768px) {
  .fp-rpt-content__entries {
    grid-template-columns: 1fr;
    gap: 2px var(--sp-3);
  }

  .fp-rpt-content__entries dd {
    margin-bottom: var(--sp-2);
  }
}
</style>
