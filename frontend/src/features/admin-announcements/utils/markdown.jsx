/**
 * Small, SAFE markdown renderer for announcements. It builds React elements only: text is always rendered as text
 * (never as HTML, no dangerouslySetInnerHTML), and links are kept only for http, https and mailto URLs.
 *
 * Supported: paragraphs and line breaks, # / ## / ### headings, "-" / "*" bullet lists, "1." numbered lists,
 * **bold**, *italic* / _italic_, `code`, [text](url) and <https://...> autolinks. Anything else stays plain text.
 * No dependency is added: the project has no markdown library and announcements only need this subset.
 */

const SAFE_PROTOCOLS = new Set(['http:', 'https:', 'mailto:'])

/** The URL if it is absolute and http/https/mailto; otherwise null (rendered as plain text). */
export function safeHref(url) {
  if (typeof url !== 'string' || url.trim() !== url || url === '') return null
  try {
    const parsed = new URL(url)
    return SAFE_PROTOCOLS.has(parsed.protocol) ? parsed.href : null
  } catch {
    return null
  }
}

// Order matters: code first (its content is literal), then links, autolinks, bold, italic.
const INLINE = new RegExp(
  [
    '`(?<code>[^`\\n]+)`',
    '\\[(?<linkText>[^\\]\\n]+)\\]\\((?<linkUrl>[^)\\s]+)\\)',
    '<(?<auto>(?:https?:\\/\\/|mailto:)[^>\\s]+)>',
    '\\*\\*(?<bold>[^*\\n]+?)\\*\\*',
    '__(?<bold2>[^_\\n]+?)__',
    '\\*(?<italic>[^*\\n]+?)\\*',
    '(?<![A-Za-z0-9])_(?<italic2>[^_\\n]+?)_(?![A-Za-z0-9])',
  ].join('|'),
  'g',
)

function link(href, children, key) {
  return (
    <a key={key} href={href} target="_blank" rel="noopener noreferrer nofollow">
      {children}
    </a>
  )
}

/** Inline markdown -> array of strings and elements. */
export function renderInline(text, keyPrefix = 'i') {
  const nodes = []
  let last = 0
  let index = 0
  INLINE.lastIndex = 0
  for (const match of text.matchAll(INLINE)) {
    if (match.index > last) nodes.push(text.slice(last, match.index))
    const key = `${keyPrefix}-${index++}`
    const g = match.groups
    if (g.code !== undefined) {
      nodes.push(<code key={key}>{g.code}</code>)
    } else if (g.linkText !== undefined) {
      const href = safeHref(g.linkUrl)
      nodes.push(href ? link(href, renderInline(g.linkText, key), key) : match[0])
    } else if (g.auto !== undefined) {
      const href = safeHref(g.auto)
      nodes.push(href ? link(href, g.auto, key) : match[0])
    } else if (g.bold !== undefined || g.bold2 !== undefined) {
      nodes.push(<strong key={key}>{renderInline(g.bold ?? g.bold2, key)}</strong>)
    } else {
      nodes.push(<em key={key}>{renderInline(g.italic ?? g.italic2, key)}</em>)
    }
    last = match.index + match[0].length
  }
  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}

const HEADING = /^(#{1,3})\s+(.*)$/
const BULLET = /^\s*[-*+]\s+(.*)$/
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/

/** Block markdown -> array of elements. Headings start at h3 so they never compete with the page headings. */
export function renderMarkdown(source) {
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  const blocks = []
  let i = 0
  let key = 0

  const isBlockStart = (line) => HEADING.test(line) || BULLET.test(line) || NUMBERED.test(line)

  while (i < lines.length) {
    const line = lines[i]
    if (line.trim() === '') {
      i += 1
      continue
    }
    const heading = line.match(HEADING)
    if (heading) {
      const Tag = `h${heading[1].length + 2}`
      blocks.push(<Tag key={key}>{renderInline(heading[2], `h${key}`)}</Tag>)
      key += 1
      i += 1
      continue
    }
    const list = BULLET.test(line) ? BULLET : NUMBERED.test(line) ? NUMBERED : null
    if (list) {
      const items = []
      while (i < lines.length && list.test(lines[i])) {
        items.push(lines[i].match(list)[1])
        i += 1
      }
      const Tag = list === BULLET ? 'ul' : 'ol'
      blocks.push(
        <Tag key={key}>
          {items.map((item, n) => (
            <li key={n}>{renderInline(item, `l${key}-${n}`)}</li>
          ))}
        </Tag>,
      )
      key += 1
      continue
    }
    const paragraph = []
    while (i < lines.length && lines[i].trim() !== '' && !(paragraph.length > 0 && isBlockStart(lines[i]))) {
      paragraph.push(lines[i])
      i += 1
    }
    blocks.push(
      <p key={key}>
        {paragraph.map((text, n) => (
          <span key={n}>
            {n > 0 && <br />}
            {renderInline(text, `p${key}-${n}`)}
          </span>
        ))}
      </p>,
    )
    key += 1
  }
  return blocks
}
