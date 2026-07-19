/**
 * v19.23 — 관리 항목 설명(description) 마크다운 렌더러.
 *
 * <p>단일 진입점. NodeDescriptionDialog(편집 미리보기) + ControlNodeRow(읽기 패널)
 * + v19.25 심사원 뷰가 모두 본 composable 을 공유한다. 렌더 규칙이 세 곳에서
 * 갈라지면 관리자가 본 미리보기와 심사원이 보는 화면이 달라진다.</p>
 *
 * <h3>보안 — 이중 방어</h3>
 * <ol>
 *   <li>markdown-it 의 {@code html: false} — 원문 안의 raw HTML 을 이스케이프</li>
 *   <li>DOMPurify.sanitize() — 혹시 통과한 것까지 제거</li>
 * </ol>
 *
 * <p>description 은 관리자만 작성하지만, 렌더 대상은 심사원 화면이다.
 * 즉 stored XSS 경로 (관리자 계정 탈취 → 심사원 브라우저에서 실행).
 * v19.8 CSP 헤더와 정합. 둘 중 하나만 쓰지 말 것.</p>
 *
 * <h3>폐쇄망</h3>
 * <p>markdown-it / dompurify 는 npm 반입 확인 완료 (v19.23 착수 조건).
 * CDN 로드 금지 — L_CLOSED_NETWORK_DEPENDENCY.</p>
 */
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

/**
 * 허용 태그 — ISMS-P 해설서 구조(제목/불릿/중첩불릿/강조/표)에 필요한 최소 집합.
 * 링크/이미지는 의도적으로 제외: 폐쇄망이라 외부 이미지는 어차피 깨지고,
 * 링크는 심사원 화면에서 피싱 벡터가 된다.
 */
const ALLOWED_TAGS = [
  'p',
  'br',
  'strong',
  'em',
  'ul',
  'ol',
  'li',
  'h1',
  'h2',
  'h3',
  'h4',
  'blockquote',
  'code',
  'pre',
  'hr',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
]

/** 속성 전면 차단 — class/style/href/onerror 등 전부 제거. */
const ALLOWED_ATTR: string[] = []

const md = new MarkdownIt({
  html: false, // 1차 방어: raw HTML 이스케이프
  linkify: false, // 자동 링크화 금지 (링크 태그 자체를 안 씀)
  breaks: true, // 단일 개행 → <br> (해설서 붙여넣기 시 자연스러움)
  typographer: false,
})

/** 빈 문자열/null/공백만 → true. dirty 판정과 별개로 렌더 스킵용. */
export function isBlankDescription(raw: string | null | undefined): boolean {
  return !raw || raw.trim().length === 0
}

/**
 * 마크다운 → 안전한 HTML.
 *
 * @param raw description 원문 (null/undefined 허용)
 * @returns sanitize 된 HTML 문자열. 빈 입력이면 빈 문자열.
 */
export function renderMarkdown(raw: string | null | undefined): string {
  if (isBlankDescription(raw)) return ''
  const dirty = md.render(raw as string)
  return DOMPurify.sanitize(dirty, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    // 주석/CDATA 등 노드 타입 자체를 제거
    KEEP_CONTENT: true,
  })
}

export function useMarkdown() {
  return { renderMarkdown, isBlankDescription }
}