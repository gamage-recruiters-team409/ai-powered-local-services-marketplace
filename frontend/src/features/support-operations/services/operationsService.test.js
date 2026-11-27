import { describe, expect, it, vi } from 'vitest'
import axios from 'axios'

vi.mock('../../disputes-admin/services/authBridge', () => ({
  getAccessToken: () => 'mock-token',
}))

vi.mock('../../disputes-admin/services/apiTokenGuard', () => ({
  shouldAttachToken: () => true,
}))

describe('operationsService CSV export metadata propagation', () => {
  it('propagates sourceLimited, truncated, and rows metadata from response headers', async () => {
    const mockBlob = new Blob(['col1,col2\nval1,val2'])
    const mockHeaders = {
      'content-disposition': 'attachment; filename="export-test.csv"',
      'x-export-source-limited': 'true',
      'x-export-truncated': 'false',
      'x-export-rows': '42',
    }

    vi.spyOn(axios.Axios.prototype, 'get').mockResolvedValueOnce({
      data: mockBlob,
      headers: mockHeaders,
    })

    const { exportRepeatCsv } = await import('./operationsService')
    const result = await exportRepeatCsv({ search: 'user' })

    expect(result.filename).toBe('export-test.csv')
    expect(result.sourceLimited).toBe(true)
    expect(result.truncated).toBe(false)
    expect(result.rows).toBe(42)
  })

  it('handles complete (not limited, not truncated) export correctly', async () => {
    const mockBlob = new Blob(['col1,col2\nval1,val2'])
    const mockHeaders = {
      'content-disposition': 'attachment; filename="complete.csv"',
      'x-export-source-limited': 'false',
      'x-export-truncated': 'false',
      'x-export-rows': '10',
    }

    vi.spyOn(axios.Axios.prototype, 'get').mockResolvedValueOnce({
      data: mockBlob,
      headers: mockHeaders,
    })

    const { exportOverdueCsv } = await import('./operationsService')
    const result = await exportOverdueCsv()

    expect(result.filename).toBe('complete.csv')
    expect(result.sourceLimited).toBe(false)
    expect(result.truncated).toBe(false)
    expect(result.rows).toBe(10)
  })
})
