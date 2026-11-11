import { createContext } from 'react'

import { AREA_MODE } from './accessRules'

export const ACCESS_STATUS = Object.freeze({ LOADING: 'loading', READY: 'ready', ERROR: 'error' })

/** { status, access, user, errorKind, reload } from AdminAccessProvider. */
export const AdminAccessContext = createContext(null)

/** { area, mode, canEdit, isViewOnly } for the area the current page belongs to. */
export const AreaAccessContext = createContext({
  area: null,
  mode: AREA_MODE.NONE,
  canEdit: false,
  isViewOnly: false,
})
