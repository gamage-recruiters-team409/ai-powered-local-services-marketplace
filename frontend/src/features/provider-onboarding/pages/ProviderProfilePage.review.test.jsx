// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ProfileFields } from './ProviderProfilePage'
import { formFromProfile, validateProfile, toProfileEditPayload } from '../services/providerProfileForm'

const form = {
  providerType: 'INDIVIDUAL', displayName: 'Test Provider', bio: 'Services',
  district: 'Colombo', city: 'Colombo', experienceYears: '5',
  categoryIds: ['category-1'], serviceAreaDistricts: ['Colombo'],
}
const fields = { form, errors: {}, districts: ['Colombo'], provinces: [],
  categories: [], busy: false, setField: vi.fn(), toggleItem: vi.fn(), includeServices: false }

describe('WP02 PR #56 — provider profile integrity', () => {
  it('allows selecting Individual or Business only while creating a profile', () => {
    render(<ProfileFields {...fields} onboarding />)
    expect(screen.getByRole('radio', { name: 'Individual' })).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Business' })).toBeTruthy()
  })

  it('shows existing provider classification as read-only in Edit Profile', () => {
    render(<ProfileFields {...fields} onboarding={false} />)
    expect(screen.queryByRole('radio', { name: 'Business' })).toBeNull()
    expect(screen.getByText(/Provider type cannot be changed after profile creation/i)).toBeTruthy()
  })

  it('never sends providerType during an ordinary profile edit', () => {
    const payload = toProfileEditPayload(form)
    expect(payload.providerType).toBeUndefined()
    expect(payload.experienceYears).toBe(5)
  })

  it('rejects silently clearing a previously saved experience value', () => {
    const errors = validateProfile({ ...form, experienceYears: '' }, ['Colombo'], false,
      { providerType: 'INDIVIDUAL', experienceYears: 5 })
    expect(errors.experienceYears).toMatch(/cannot be cleared/i)
  })

  it('permits a genuinely empty experience field and valid numeric edits', () => {
    expect(validateProfile({ ...form, experienceYears: '' }, ['Colombo'], false,
      { experienceYears: null }).experienceYears).toBeUndefined()
    expect(validateProfile({ ...form, experienceYears: '0' }, ['Colombo'], false,
      { experienceYears: 5 }).experienceYears).toBeUndefined()
    expect(toProfileEditPayload({ ...form, experienceYears: '' }).experienceYears).toBeUndefined()
    expect(formFromProfile({ experienceYears: 0 }).experienceYears).toBe('0')
    expect(formFromProfile({ experienceYears: 7 }).experienceYears).toBe('7')
  })
})
