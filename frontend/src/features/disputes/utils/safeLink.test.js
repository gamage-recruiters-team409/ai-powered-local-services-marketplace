import { describe, expect, it } from 'vitest'

import { safeEvidenceHref } from './safeLink'

const ORIGIN = 'https://app.localserve.example'

describe('safeEvidenceHref', () => {
  it('allows absolute https URLs', () => {
    expect(safeEvidenceHref('https://files.example.com/a.jpg', ORIGIN)).toBe('https://files.example.com/a.jpg')
  })

  it('allows root-relative paths on the same origin', () => {
    expect(safeEvidenceHref('/files/a.jpg', ORIGIN)).toBe(`${ORIGIN}/files/a.jpg`)
  })

  it.each([
    'http://files.example.com/a.jpg',
    'javascript:alert(1)',
    'data:text/html,<b>x</b>',
    '//evil.example/a.jpg',
    '/\\evil.example',
    ' https://files.example.com/a.jpg',
    'evidence/sample-photo-1.jpg',
    '',
  ])('rejects %j', (reference) => {
    expect(safeEvidenceHref(reference, ORIGIN)).toBeNull()
  })

  it('rejects non-strings', () => {
    expect(safeEvidenceHref(undefined, ORIGIN)).toBeNull()
    expect(safeEvidenceHref(42, ORIGIN)).toBeNull()
  })
})
