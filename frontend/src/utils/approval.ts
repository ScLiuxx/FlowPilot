import type { JsonObject } from '@/api/types'

/** 审批请求里附带的报告章节 */
export interface ApprovalReportSection {
  title: string
  content: string
}

/** 审批请求里附带的报告：摘要、风险等级与各章节正文 */
export interface ApprovalReport {
  title: string
  summary: string
  riskLevel: string
  sections: ApprovalReportSection[]
}

/**
 * 从审批请求的 requestData 里解析出报告。
 *
 * Agent 中断时报告位于顶层的 report，HUMAN 节点同样位于顶层；payload.report
 * 是兼容旧的嵌套形状。没有报告内容时返回 null。
 */
export function parseApprovalReport(requestData?: JsonObject | null): ApprovalReport | null {
  if (!requestData || typeof requestData !== 'object') return null
  const payload =
    requestData.payload && typeof requestData.payload === 'object'
      ? (requestData.payload as JsonObject)
      : undefined
  const source = (requestData.report ?? payload?.report) as JsonObject | undefined
  if (!source || typeof source !== 'object') return null

  const sections: ApprovalReportSection[] = Array.isArray(source.sections)
    ? source.sections
        .filter((item): item is JsonObject => !!item && typeof item === 'object')
        .map((item) => ({
          title: typeof item.title === 'string' ? item.title : '',
          content: typeof item.content === 'string' ? item.content : ''
        }))
        .filter((item) => item.content)
    : []
  const summary = typeof source.summary === 'string' ? source.summary : ''
  const riskLevel = typeof source.riskLevel === 'string' ? source.riskLevel : ''
  if (!sections.length && !summary && !riskLevel) return null

  return {
    title: typeof source.title === 'string' ? source.title : '',
    summary,
    riskLevel,
    sections
  }
}
