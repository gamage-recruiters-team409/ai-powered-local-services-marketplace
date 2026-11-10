import { ADMIN_PATHS, ADMIN_ROLES, AREA_ORDER, areaFromPathname, areaPath } from '../config/adminAreas'

/**
 * Pure access helpers. All decisions come from the backend /admin/access response;
 * nothing here guesses access from the role name.
 */

export const AREA_MODE = Object.freeze({ EDIT: 'edit', VIEW: 'view', NONE: 'none' })

/** Role card for the Super Admin area when the user must sign in as a Super Admin. */
export const CARD_SIGN_IN = 'sign-in'

/** Role card for an area the user's role cannot open (shown, but disabled). */
export const CARD_LOCKED = 'locked'

export const ACCESS_ERROR = Object.freeze({
  UNAUTHORIZED: 'unauthorized',
  FORBIDDEN: 'forbidden',
  FAILED: 'failed',
})

const knownAreas = (value) => (Array.isArray(value) ? value.filter((area) => AREA_ORDER.includes(area)) : [])

/** Makes the response safe to use: unknown areas dropped, canView never repeats canEdit. */
export function normalizeAccess(data) {
  const canEdit = knownAreas(data?.canEdit)
  return {
    role: data?.role ?? null,
    canEdit,
    canView: knownAreas(data?.canView).filter((area) => !canEdit.includes(area)),
    canAccessSuperAdmin: data?.canAccessSuperAdmin === true,
  }
}

export function getAreaMode(access, area) {
  if (!access || !area) {
    return AREA_MODE.NONE
  }
  if (area === ADMIN_ROLES.SUPER_ADMIN && !access.canAccessSuperAdmin) {
    return AREA_MODE.NONE
  }
  if (access.canEdit.includes(area)) {
    return AREA_MODE.EDIT
  }
  if (access.canView.includes(area)) {
    return AREA_MODE.VIEW
  }
  return AREA_MODE.NONE
}

export function hasAnyArea(access) {
  return AREA_ORDER.some((area) => getAreaMode(access, area) !== AREA_MODE.NONE)
}

/** Super Admin lands on the Super Admin dashboard; every other admin on role selection. */
export function getAdminHomePath(access) {
  return getAreaMode(access, ADMIN_ROLES.SUPER_ADMIN) === AREA_MODE.EDIT
    ? areaPath(ADMIN_ROLES.SUPER_ADMIN)
    : ADMIN_PATHS.selectRole
}

/**
 * Cards for role selection and the switch-role modal, one per admin area.
 * Areas the user can open get their mode (edit / view). Areas they cannot open are shown
 * as locked, except the Super Admin card, which other admins can use to sign in as a Super Admin.
 */
export function getRoleCards(access) {
  if (!access) {
    return []
  }
  return AREA_ORDER.map((area) => {
    const mode = getAreaMode(access, area)
    if (mode !== AREA_MODE.NONE) {
      return { area, mode }
    }
    return { area, mode: area === ADMIN_ROLES.SUPER_ADMIN ? CARD_SIGN_IN : CARD_LOCKED }
  })
}

/** Area whose links the sidebar shows: the area in the URL if allowed, else the user's own area. */
export function resolveNavArea(access, pathname) {
  if (!access) {
    return null
  }
  const urlArea = areaFromPathname(pathname)
  if (urlArea && getAreaMode(access, urlArea) !== AREA_MODE.NONE) {
    return urlArea
  }
  return getAreaMode(access, access.role) !== AREA_MODE.NONE ? access.role : null
}

export function classifyAccessError(error) {
  const status = error?.response?.status
  if (status === 401) {
    return ACCESS_ERROR.UNAUTHORIZED
  }
  if (status === 403) {
    return ACCESS_ERROR.FORBIDDEN
  }
  return ACCESS_ERROR.FAILED
}
