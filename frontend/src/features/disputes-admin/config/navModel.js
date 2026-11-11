import { areaPath } from './adminAreas'
import { ADMIN_NAV_ENTRIES } from './adminNavConfig'

export function getAreaEntries(area, entries = ADMIN_NAV_ENTRIES) {
  return entries.filter((entry) => entry.area === area)
}

/** Groups an area's entries into the section/item shape that AdminLayout expects. */
export function buildNavSections(area, entries = ADMIN_NAV_ENTRIES) {
  const sections = []
  const sectionsByTitle = new Map()

  for (const entry of getAreaEntries(area, entries)) {
    const key = entry.section ?? ''
    let section = sectionsByTitle.get(key)
    if (!section) {
      const slug = key ? key.toLowerCase().replace(/\s+/g, '-') : 'main'
      section = { id: `${area}-${slug}`, title: entry.section ?? null, items: [] }
      sectionsByTitle.set(key, section)
      sections.push(section)
    }

    const link = {
      id: entry.id,
      label: entry.label,
      to: areaPath(area, entry.path),
      icon: entry.icon,
      end: entry.path === '',
    }

    if (entry.group) {
      const groupId = `${area}-${entry.group.id}`
      let group = section.items.find((item) => item.id === groupId)
      if (!group) {
        group = { id: groupId, label: entry.group.label, icon: entry.group.icon, children: [] }
        section.items.push(group)
      }
      group.children.push(link)
    } else {
      section.items.push(link)
    }
  }

  return sections
}

/** Dev-only guard: duplicate ids or paths would silently break the sidebar or routing. */
export function findNavConfigProblems(entries = ADMIN_NAV_ENTRIES) {
  const problems = []
  const ids = new Set()
  const paths = new Set()
  for (const entry of entries) {
    const pathKey = `${entry.area}:${entry.path}`
    if (ids.has(entry.id)) {
      problems.push(`Duplicate nav id "${entry.id}"`)
    }
    if (paths.has(pathKey)) {
      problems.push(`Duplicate path "${entry.path}" in area ${entry.area}`)
    }
    ids.add(entry.id)
    paths.add(pathKey)
  }
  return problems
}
