// Run from frontend/: node --test src/features/disputes-admin/services/apiTokenGuard.test.js
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { shouldAttachToken } from './apiTokenGuard.js'

const API = 'http://localhost:8080/api/v1'

describe('shouldAttachToken', () => {
  it('allows relative API paths', () => {
    assert.equal(shouldAttachToken('/admin/access', API), true)
    assert.equal(shouldAttachToken('users/me', API), true)
    assert.equal(shouldAttachToken('/admin/access?x=1', `${API}/`), true)
  })

  it('blocks scheme-absolute URLs to another origin', () => {
    assert.equal(shouldAttachToken('https://example.com', API), false)
    assert.equal(shouldAttachToken('https://example.com/api/v1/admin/access', API), false)
    assert.equal(shouldAttachToken('HTTPS://EXAMPLE.COM/x', API), false)
    assert.equal(shouldAttachToken('ftp://example.com/x', API), false)
  })

  it('blocks protocol-relative URLs', () => {
    assert.equal(shouldAttachToken('//example.com/path', API), false)
    assert.equal(shouldAttachToken('//localhost:9999/api/v1/admin/access', API), false)
  })

  it('blocks backslash, whitespace and control-character tricks', () => {
    assert.equal(shouldAttachToken('\\\\example.com/path', API), false)
    assert.equal(shouldAttachToken('/\\example.com/path', API), false)
    assert.equal(shouldAttachToken(' //example.com/path', API), false)
    assert.equal(shouldAttachToken('\t//example.com/path', API), false)
    assert.equal(shouldAttachToken('/admin/access ', API), false)
    assert.equal(shouldAttachToken('/admin\n/access', API), false)
  })

  it('allows an absolute URL only when its origin matches the API base', () => {
    assert.equal(shouldAttachToken('http://localhost:8080/api/v1/admin/access', API), true)
    assert.equal(shouldAttachToken('http://localhost:8081/api/v1/admin/access', API), false)
    assert.equal(shouldAttachToken('https://localhost:8080/api/v1/admin/access', API), false)
    assert.equal(shouldAttachToken('http://localhost:8080.example.com/api/v1', API), false)
    assert.equal(shouldAttachToken('http://user@example.com/api/v1', API), false)
  })

  it('blocks a per-request baseURL that points at another origin', () => {
    assert.equal(shouldAttachToken('/admin/access', 'https://example.com/api', API), false)
  })

  it('blocks empty, undefined and non-string URLs', () => {
    assert.equal(shouldAttachToken('', API), false)
    assert.equal(shouldAttachToken(undefined, API), false)
    assert.equal(shouldAttachToken(null, API), false)
    assert.equal(shouldAttachToken(42, API), false)
  })

  it('blocks everything when the API base URL is missing or not http(s)', () => {
    assert.equal(shouldAttachToken('/admin/access', undefined), false)
    assert.equal(shouldAttachToken('/admin/access', ''), false)
    assert.equal(shouldAttachToken('/admin/access', 'not a url'), false)
    assert.equal(shouldAttachToken('/admin/access', 'javascript:alert(1)'), false)
  })
})
