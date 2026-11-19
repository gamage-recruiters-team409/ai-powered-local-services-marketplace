/**
 * Pure helpers for the markdown toolbar. Each returns { value, selectionStart, selectionEnd } so the editor can
 * update the text and restore the cursor. There is no underline: markdown has none.
 */

/** Wraps the selection with a marker (** for bold, _ for italic); inserts a placeholder when nothing is selected. */
export function wrapSelection(value, start, end, marker, placeholder) {
  const selected = value.slice(start, end) || placeholder
  const next = `${value.slice(0, start)}${marker}${selected}${marker}${value.slice(end)}`
  const selectionStart = start + marker.length
  return { value: next, selectionStart, selectionEnd: selectionStart + selected.length }
}

/** [text](https://) with the URL part selected so it can be typed over. */
export function insertLink(value, start, end) {
  const text = value.slice(start, end) || 'link text'
  const url = 'https://'
  const inserted = `[${text}](${url})`
  const next = `${value.slice(0, start)}${inserted}${value.slice(end)}`
  const selectionStart = start + text.length + 3
  return { value: next, selectionStart, selectionEnd: selectionStart + url.length }
}

/** Prefixes every line touched by the selection with "- " or "1. ", "2. ", ... */
export function prefixLines(value, start, end, numbered) {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  const nextBreak = value.indexOf('\n', end)
  const lineEnd = nextBreak === -1 ? value.length : nextBreak
  const lines = value.slice(lineStart, lineEnd).split('\n')
  const prefixed = lines.map((line, i) => `${numbered ? `${i + 1}. ` : '- '}${line}`).join('\n')
  const next = `${value.slice(0, lineStart)}${prefixed}${value.slice(lineEnd)}`
  return { value: next, selectionStart: lineStart + prefixed.length, selectionEnd: lineStart + prefixed.length }
}
