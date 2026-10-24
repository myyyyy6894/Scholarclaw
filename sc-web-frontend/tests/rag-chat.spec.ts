import { describe, expect, it } from 'vitest'

import type { ChatMessage } from '@/api/rag'
import {
  applyDone,
  applyInterrupted,
  applyRejection,
  applyStreamFailure,
  applyToken,
  citationPageLabel,
  citationScoreLabel,
  createLocalMessage,
  formatDuration,
  historyToUiMessages,
  queryError,
  splitInlineCitations,
  splitSupplement,
  SUPPLEMENT_PREFIX,
} from '@/views/rag/rag-chat'

function historyMessage(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    message_id: '90001',
    course_id: '2001',
    platform: 'WEB',
    role: 'USER',
    intent_type: 'KB_QA',
    content: '问题',
    citations: null,
    is_rejected: false,
    trace_id: 'tr_0123456789ab',
    retrieval_cost_ms: null,
    llm_first_token_ms: null,
    created_at: '2026-10-23T10:00:00.000+08:00',
    ...overrides,
  }
}

describe('rag chat presentation', () => {
  it('把拒答后的通用补充与课件依据拆开', () => {
    expect(splitSupplement('课件答案\n\n' + SUPPLEMENT_PREFIX + '\n通用解释')).toEqual({
      main: '课件答案',
      supplement: '通用解释',
    })
    expect(splitSupplement('只有课件答案')).toEqual({ main: '只有课件答案', supplement: '' })
  })

  it('历史记录跳过 SYSTEM 并解析补充段', () => {
    const messages = historyToUiMessages([
      historyMessage({ message_id: '90001', role: 'USER', content: '问' }),
      historyMessage({
        message_id: '90002',
        role: 'ASSISTANT',
        content: '答\n\n' + SUPPLEMENT_PREFIX + '\n补充',
        citations: [{
          parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
          page_start: 1, page_end: 2, section_title: null, content: '内容',
        }],
      }),
      historyMessage({ message_id: '90003', role: 'SYSTEM', content: '系统' }),
    ])

    expect(messages).toHaveLength(2)
    expect(messages[1]?.content).toBe('答')
    expect(messages[1]?.supplement).toBe('补充')
    expect(messages[1]?.citations?.[0]?.doc_id).toBe('50001')
    expect(messages[1]?.messageId).toBe('90002')
  })

  it('问题校验 trim 后非空且不超过 2000 字', () => {    expect(queryError('   ')).toBe('请输入问题。')
    expect(queryError(' 问题 ')).toBeNull()
    expect(queryError('问'.repeat(2001))).toBe('问题长度不得超过 2000 个字符。')
  })

  it('引用页码与相似度展示', () => {
    expect(citationPageLabel({
      parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 14, page_end: 14, section_title: null, content: '',
    })).toBe('第 14 页')
    expect(citationPageLabel({
      parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 14, page_end: 16, section_title: null, content: '',
    })).toBe('第 14–16 页')
    expect(citationScoreLabel({
      parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 1, page_end: 1, section_title: null, content: '',
      similarity_score: 0.8421,
    })).toBe('相似度 0.842')
    expect(citationScoreLabel({
      parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 1, page_end: 1, section_title: null, content: '',
    })).toBeNull()
  })

  it('流式 token 按是否为通用补充分流', () => {
    const message = createLocalMessage('ASSISTANT')

    applyToken(message, '课件', false)
    applyToken(message, '补充', true)

    expect(message.content).toBe('课件')
    expect(message.supplement).toBe('补充')
  })

  it('done 更新 ID/耗时并结束流式状态', () => {
    const message = createLocalMessage('ASSISTANT')

    applyDone(message, {
      user_message_id: '90001',
      assistant_message_id: '90002',
      is_rejected: false,
      retrieval_cost_ms: 185,
      llm_first_token_ms: 1120,
      stripped_citation_count: 1,
      trace_id: 'tr_0123456789ab',
    })

    expect(message.messageId).toBe('90002')
    expect(message.streaming).toBe(false)
    expect(formatDuration(message.retrievalCostMs)).toBe('185 ms')
  })

  it('拒答与中断状态可区分', () => {
    const rejected = createLocalMessage('ASSISTANT')
    applyRejection(rejected, {
      user_message_id: '90001',
      notice_text: '未找到相关内容',
      has_general_supplement: true,
    })
    expect(rejected.isRejected).toBe(true)
    expect(rejected.noticeText).toBe('未找到相关内容')
    expect(rejected.content).toBe('')
    expect(rejected.citations).toBeNull()

    const interrupted = createLocalMessage('ASSISTANT')
    applyInterrupted(interrupted, '连接中断')
    expect(interrupted.streaming).toBe(false)
    expect(interrupted.interrupted).toBe(true)
    expect(interrupted.errorText).toBe('连接中断')
  })

  it('错误/断流/引用失败丢弃半截回答与引用', () => {
    const message = createLocalMessage('ASSISTANT')
    message.content = '半截回答'
    message.supplement = '补充'
    message.citations = [{
      parent_chunk_id: 'pc_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 1, page_end: 1, section_title: null, content: '内容',
    }]

    applyStreamFailure(message, '引用校验未通过')

    expect(message.content).toBe('')
    expect(message.supplement).toBe('')
    expect(message.citations).toBeNull()
    expect(message.streaming).toBe(false)
    expect(message.interrupted).toBe(true)
    expect(message.errorText).toBe('引用校验未通过')
  })

  it('拒答历史恢复声明与通用补充，不把声明当普通正文', () => {
    const messages = historyToUiMessages([
      historyMessage({
        message_id: '90002',
        role: 'ASSISTANT',
        is_rejected: true,
        content: '📌 检索说明：未找到相关内容。\n\n'
          + SUPPLEMENT_PREFIX + '\n通用补充正文',
      }),
    ])

    expect(messages[0]?.content).toBe('')
    expect(messages[0]?.noticeText).toBe('📌 检索说明：未找到相关内容。')
    expect(messages[0]?.supplement).toBe('通用补充正文')
    expect(messages[0]?.isRejected).toBe(true)
  })

  it('只高亮同时命中 citation 的文件名与页码', () => {
    const citations = [{
      parent_chunk_id: 'pc_50001_v1_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 14, page_end: 16, section_title: null, content: '内容',
    }]
    const segments = splitInlineCitations(
      '结论[来源：a.pdf - 第15页]，伪造[来源：a.pdf - 第99页]，'
        + '未知[来源：b.pdf - 第1页]，中文【来源：a.pdf - 第14页】。',
      citations,
    )

    expect(segments.filter((segment) => segment.citationIndex !== null)
      .map((segment) => segment.text)).toEqual([
      '[来源：a.pdf - 第15页]',
      '【来源：a.pdf - 第14页】',
    ])
    expect(segments.map((segment) => segment.text).join('')).toBe(
      '结论[来源：a.pdf - 第15页]，伪造[来源：a.pdf - 第99页]，'
        + '未知[来源：b.pdf - 第1页]，中文【来源：a.pdf - 第14页】。',
    )
  })

  it('没有引用时正文整体作为纯文本', () => {
    expect(splitInlineCitations('结论[来源：a.pdf - 第1页]', null)).toEqual([
      { text: '结论[来源：a.pdf - 第1页]', citationIndex: null },
    ])
  })

  it('相似度为 null 时不展示（兼容历史旧记录）', () => {
    expect(citationScoreLabel({
      parent_chunk_id: 'pc_50001_v1_1', doc_id: '50001', doc_name: 'a.pdf',
      page_start: 1, page_end: 1, section_title: null, content: '',
      similarity_score: null,
    })).toBeNull()
  })
})
