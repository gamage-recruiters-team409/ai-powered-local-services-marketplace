// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ServicesEditor, PayoutEditor } from './ProviderManagementDialogs'

const categories = [{ id: 'cat-electric', name: 'Electrical Services' }]
const provinces = [{ name: 'Western', districts: ['Colombo', 'Gampaha'] }]
const profile = { categoryIds: ['cat-electric'], serviceAreaDistricts: ['Colombo'] }

function renderServices(onSave = vi.fn()) {
  render(<ServicesEditor profile={profile} categories={categories} provinces={provinces}
    loading={false} onSave={onSave} onCancel={vi.fn()} error="" />)
  return onSave
}

describe('WP02 PR #56 — Manage Services validation', () => {
  it('blocks saving after the last category is removed and shows an inline error', async () => {
    const onSave = renderServices()
    await userEvent.click(screen.getByRole('button', { name: /Electrical Services ×/i }))
    expect(screen.getByText(/Select at least one service category/i)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Save changes' }).disabled).toBe(true)
    expect(onSave).not.toHaveBeenCalled()
  })

  it('keeps valid selected categories and districts when saving', async () => {
    const onSave = renderServices()
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(onSave).toHaveBeenCalledWith({
      categoryIds: ['cat-electric'], serviceAreaDistricts: ['Colombo'],
    })
  })

  it('preserves the selected categories and districts when an API update fails', async () => {
    const onSave = vi.fn()
    const { rerender } = render(<ServicesEditor profile={profile} categories={categories}
      provinces={provinces} loading={false} onSave={onSave} onCancel={vi.fn()} error="" />)
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(onSave).toHaveBeenCalledOnce()
    rerender(<ServicesEditor profile={profile} categories={categories}
      provinces={provinces} loading={false} onSave={onSave} onCancel={vi.fn()} error="Save failed" />)
    expect(screen.getByText('Save failed')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Electrical Services ×/i })).toBeTruthy()
    expect(screen.getByText(/1 districts selected/)).toBeTruthy()
  })

  it('prevents saving when there is no selected service-area district', async () => {
    const onSave = vi.fn()
    render(<ServicesEditor profile={{ ...profile, serviceAreaDistricts: [] }}
      categories={categories} provinces={provinces} loading={false}
      onSave={onSave} onCancel={vi.fn()} error="" />)
    expect(screen.getByRole('button', { name: 'Save changes' }).disabled).toBe(true)
    expect(onSave).not.toHaveBeenCalled()
  })
})

describe('WP02 PR #56 — payout account validation', () => {
  it('normalizes formatted valid account numbers before sending', async () => {
    const onSave = vi.fn()
    render(<PayoutEditor payout={null} loading={false} error="" onCancel={vi.fn()} onSave={onSave} />)
    await userEvent.type(screen.getByRole('textbox', { name: /Bank name/i }), 'Commercial Bank')
    await userEvent.type(screen.getByRole('textbox', { name: /Account holder name/i }), 'Test Provider')
    await userEvent.type(screen.getByRole('textbox', { name: /Account number/i }), '123456-789 012')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(onSave).toHaveBeenCalledWith({
      bankName: 'Commercial Bank', accountHolderName: 'Test Provider',
      accountNumber: '123456789012',
    })
  })

  it('blocks invalid account-number characters', async () => {
    const onSave = vi.fn()
    render(<PayoutEditor payout={null} loading={false} error="" onCancel={vi.fn()} onSave={onSave} />)
    await userEvent.type(screen.getByRole('textbox', { name: /Account number/i }), '12ABCD')
    expect(screen.getByRole('button', { name: 'Save changes' }).disabled).toBe(true)
    expect(onSave).not.toHaveBeenCalled()
  })
})
