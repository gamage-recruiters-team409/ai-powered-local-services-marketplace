import { describe, expect, it } from 'vitest'

import { AREA_MODE, getAreaMode, normalizeAccess } from '../access/accessRules'
import { ADMIN_ROLES, areaPath } from './adminAreas'
import { ADMIN_NAV_ENTRIES } from './adminNavConfig'
import { buildNavSections, findNavConfigProblems, getAreaEntries } from './navModel'

const { SUPER_ADMIN, ADMIN, MODERATOR } = ADMIN_ROLES

const labelsOf = (sections) => sections.flatMap((section) => section.items.flatMap((item) => [
  item.label,
  ...(item.children ?? []).map((child) => child.label),
]))

describe('admin sidebar: moderation belongs to the MODERATOR area', () => {
  it('the ADMIN area has no moderation pages and no Moderation group', () => {
    const adminEntries = getAreaEntries(ADMIN)
    expect(adminEntries.some((entry) => entry.path.startsWith('moderation/'))).toBe(false)
    expect(adminEntries.some((entry) => entry.group)).toBe(false)
    expect(ADMIN_NAV_ENTRIES.find((entry) => entry.id === 'admin.moderation.reviews')).toBeUndefined()
    expect(ADMIN_NAV_ENTRIES.find((entry) => entry.id === 'admin.moderation.reported-content')).toBeUndefined()

    const labels = labelsOf(buildNavSections(ADMIN))
    expect(labels).not.toContain('Moderation')
    expect(labels).not.toContain('Review moderation')
    expect(labels).not.toContain('Reported content')
    // The rest of the ADMIN area is unchanged.
    expect(labels).toEqual(expect.arrayContaining(['Dashboard', 'Disputes', 'Users', 'Announcements']))
  })

  it('the MODERATOR area keeps Reported content and Review moderation under its Moderation group', () => {
    const groups = buildNavSections(MODERATOR).flatMap((section) => section.items).filter((item) => item.children)
    const moderation = groups.find((group) => group.label === 'Moderation')

    expect(moderation.children.map((child) => child.label)).toEqual(['Reported content', 'Review moderation'])
    expect(ADMIN_NAV_ENTRIES.find((entry) => entry.id === 'moderator.moderation.reviews').path).toBe('moderation/reviews')
    expect(areaPath(MODERATOR, 'moderation/reviews')).toBe('/admin/moderator/moderation/reviews')
    expect(areaPath(MODERATOR, 'moderation/reported-content')).toBe('/admin/moderator/moderation/reported-content')
  })

  it('the nav config has no duplicate ids or paths', () => {
    expect(findNavConfigProblems()).toEqual([])
  })
})

describe('area access (from /admin/access) is unchanged', () => {
  // What the backend answers for each role (AdminAccessPolicyService).
  const superAdmin = normalizeAccess({
    role: SUPER_ADMIN,
    canEdit: [SUPER_ADMIN],
    canView: [ADMIN, 'SUPPORT_ADMIN', MODERATOR, 'FINANCE_ADMIN'],
    canAccessSuperAdmin: true,
  })
  const admin = normalizeAccess({ role: ADMIN, canEdit: [ADMIN], canView: [], canAccessSuperAdmin: false })
  const moderator = normalizeAccess({ role: MODERATOR, canEdit: [MODERATOR], canView: [], canAccessSuperAdmin: false })

  it('SUPER_ADMIN keeps read-only access to the moderator area', () => {
    expect(getAreaMode(superAdmin, MODERATOR)).toBe(AREA_MODE.VIEW)
  })

  it('MODERATOR edits its own area; ADMIN cannot open it', () => {
    expect(getAreaMode(moderator, MODERATOR)).toBe(AREA_MODE.EDIT)
    expect(getAreaMode(admin, MODERATOR)).toBe(AREA_MODE.NONE)
    expect(getAreaMode(admin, ADMIN)).toBe(AREA_MODE.EDIT)
  })
})
