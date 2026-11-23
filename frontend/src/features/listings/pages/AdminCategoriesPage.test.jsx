// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastProvider } from '../../../shared/components/feedback'
import AdminCategoriesPage from './AdminCategoriesPage'

const mockGetAdminCategories = vi.fn()
const mockCreateCategory = vi.fn()
const mockUpdateCategory = vi.fn()
const mockDeactivateCategory = vi.fn()
const mockUseAreaAccess = vi.fn()

vi.mock('../services/catalogService', async () => {
  const actual = await vi.importActual('../services/catalogService')
  return {
    ...actual,
    getAdminCategories: (...args) => mockGetAdminCategories(...args),
    createCategory: (...args) => mockCreateCategory(...args),
    updateCategory: (...args) => mockUpdateCategory(...args),
    deactivateCategory: (...args) => mockDeactivateCategory(...args),
  }
})

vi.mock('../../disputes-admin/access/useAdminAccess', () => ({
  useAreaAccess: () => mockUseAreaAccess(),
}))

const MOCK_TREE = [
  {
    id: 'root-solar',
    name: 'Solar & Renewable Energy',
    slug: 'solar-and-renewable-energy',
    description: 'Clean solar installations and battery backups',
    icon: 'sun',
    commissionRate: 0.1,
    active: true,
    subcategories: [
      {
        id: 'sub-inverter',
        name: 'Inverter Servicing',
        slug: 'inverter-servicing',
        description: 'Hybrid inverter diagnostics',
        icon: 'sun',
        parentId: 'root-solar',
        commissionRate: 0.14,
        active: true,
      },
    ],
  },
  {
    id: 'root-plumbing',
    name: 'Plumbing & Water',
    slug: 'plumbing-and-water',
    description: 'Pipe repairs and water tank maintenance',
    icon: 'water',
    commissionRate: 0.12,
    active: true,
    subcategories: [
      {
        id: 'sub-leak',
        name: 'Leak Detection',
        slug: 'leak-detection',
        description: null,
        icon: 'water',
        parentId: 'root-plumbing',
        commissionRate: 0.12,
        active: false,
      },
    ],
  },
]

function renderPage() {
  return render(
    <ToastProvider>
      <AdminCategoriesPage />
    </ToastProvider>
  )
}

describe('AdminCategoriesPage backend integration & UI safeguards', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAreaAccess.mockReturnValue({ area: 'ADMIN', canEdit: true })
  })

  it('loads real categories from backend, computes live KPIs, and never shows fake seed rows', async () => {
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)

    renderPage()

    const solarNodes = await screen.findAllByText('Solar & Renewable Energy')
    expect(solarNodes.length).toBeGreaterThan(0)
    expect(screen.getAllByText('Plumbing & Water').length).toBeGreaterThan(0)
    expect(screen.queryByText('Electrical & Power')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()

    // Active nodes: root-solar (10%), sub-inverter (14%), root-plumbing (12%) => Avg 12.0%
    const avgKpiCard = screen.getByText('AVG COMMISSION (ACTIVE)').closest('div').parentElement
    expect(within(avgKpiCard).getByText('12.0%')).toBeTruthy()
    expect(within(avgKpiCard).getByText('Range 10.0%–14.0%')).toBeTruthy()
    expect(screen.getByText('Across 3 Active Categories')).toBeTruthy()
    expect(screen.getByText('1 Inactive')).toBeTruthy()
  })

  it('shows honest auth error banner without fake preview categories when backend returns 401', async () => {
    mockGetAdminCategories.mockRejectedValueOnce(
      Object.assign(new Error('Unauthorized'), { status: 401 })
    )

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/sign in and retry/i)
    expect(alert.textContent).not.toMatch(/dev\.accessToken/i)
    expect(screen.queryByText('Electrical & Power')).toBeNull()
  })

  it('preserves description and locks hierarchy fields when editing an existing category', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValue(MOCK_TREE)
    mockUpdateCategory.mockResolvedValueOnce({
      ...MOCK_TREE[0],
      commissionRate: 0.15,
    })

    renderPage()

    await screen.findByText('Inverter Servicing')
    const solarNodes = screen.getAllByText('Solar & Renewable Energy')
    const treeSpan = solarNodes.find((el) => el.className.includes('rootName'))
    expect(treeSpan).toBeTruthy()
    await user.click(treeSpan)

    // Existing description is populated and hierarchy controls are locked during edit
    const descField = screen.getByLabelText(/category description/i)
    expect(descField.value).toBe('Clean solar installations and battery backups')
    expect(screen.getByRole('radio', { name: /root category/i }).disabled).toBe(true)
    expect(screen.getByRole('radio', { name: /subcategory/i }).disabled).toBe(true)
    expect(screen.getByLabelText(/parent category reference/i).disabled).toBe(true)

    const commissionInput = screen.getByLabelText(/platform commission rate/i)
    await user.clear(commissionInput)
    await user.type(commissionInput, '15.00')

    await user.click(screen.getByRole('button', { name: /save category changes/i }))

    await waitFor(() => {
      expect(mockUpdateCategory).toHaveBeenCalledWith('root-solar', {
        name: 'Solar & Renewable Energy',
        slug: 'solar-and-renewable-energy',
        description: 'Clean solar installations and battery backups',
        icon: 'sun',
        commissionRate: 0.15,
        active: true,
      })
    })
  })

  it('confirms before deactivating and surfaces 409 conflict when root has active subcategories', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)
    mockDeactivateCategory.mockRejectedValueOnce(
      Object.assign(new Error('Cannot deactivate category while it has active subcategories'), {
        status: 409,
      })
    )

    renderPage()

    await screen.findAllByText('Solar & Renewable Energy')
    const activeButtons = screen.getAllByRole('button', { name: /^Active$/i })
    await user.click(activeButtons[0])

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toMatch(/Deactivate "Solar & Renewable Energy"\?/i)
    await user.click(within(dialog).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => {
      expect(mockDeactivateCategory).toHaveBeenCalledWith('root-solar')
    })

    const conflictMessages = await screen.findAllByText(
      /Cannot deactivate category while it has active subcategories/i
    )
    expect(conflictMessages.length).toBeGreaterThan(0)
  })

  it('displays subcategories from collapsed roots on the Subcategories tab and keeps true counts on Root tab', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)

    renderPage()

    await screen.findAllByText('Plumbing & Water')

    // Root tab should keep true "1 Subcategory" count instead of falsely showing "0 Subcategories"
    await user.click(screen.getByRole('tab', { name: 'Root Categories' }))
    expect(screen.getAllByText('1 Subcategory')).toHaveLength(2)

    // Subcategories tab must display "Leak Detection" even though Plumbing & Water was collapsed
    await user.click(screen.getByRole('tab', { name: 'Subcategories' }))
    expect(screen.getByText('Inverter Servicing')).toBeTruthy()
    expect(screen.getByText('Leak Detection')).toBeTruthy()
  })

  it('disables inactive parent categories when creating a subcategory', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce([
      { ...MOCK_TREE[0], active: true },
      { ...MOCK_TREE[1], active: false, subcategories: [] },
    ])

    renderPage()

    await screen.findAllByText('Solar & Renewable Energy')
    await user.click(screen.getByRole('radio', { name: /subcategory/i }))

    const parentSelect = screen.getByLabelText(/parent category reference/i)
    const inactiveOption = within(parentSelect).getByRole('option', {
      name: /Plumbing & Water \(Inactive\)/i,
    })
    expect(inactiveOption.disabled).toBe(true)
  })

  it('enforces SUPER_ADMIN read-only mode when canEdit is false', async () => {
    mockUseAreaAccess.mockReturnValue({ area: 'ADMIN', canEdit: false })
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)

    renderPage()

    await screen.findAllByText('Solar & Renewable Energy')
    expect(screen.queryByRole('button', { name: /add new category/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /create & publish/i })).toBeNull()

    const statusButtons = screen.getAllByRole('button', { name: /^Active$/i })
    statusButtons.forEach((btn) => expect(btn.disabled).toBe(true))
  })

  it('creates an inactive category atomically with active=false and never calls deactivateCategory', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValue(MOCK_TREE)
    mockCreateCategory.mockResolvedValueOnce({
      id: 'cat-hidden',
      name: 'Seasonal Cleaning',
      slug: 'seasonal-cleaning',
      description: 'Hidden until launch',
      icon: 'sun',
      parentId: null,
      commissionRate: 0.12,
      active: false,
    })

    renderPage()
    await screen.findAllByText('Solar & Renewable Energy')

    await user.type(screen.getByLabelText(/category name/i), 'Seasonal Cleaning')
    await user.type(screen.getByLabelText(/category description/i), 'Hidden until launch')
    const commissionInput = screen.getByLabelText(/platform commission rate/i)
    await user.clear(commissionInput)
    await user.type(commissionInput, '12.00')

    const visibilityCheckbox = screen.getByRole('checkbox')
    expect(visibilityCheckbox.checked).toBe(true)
    await user.click(visibilityCheckbox)
    expect(visibilityCheckbox.checked).toBe(false)

    await user.click(screen.getByRole('button', { name: /create & publish category/i }))

    await waitFor(() => {
      expect(mockCreateCategory).toHaveBeenCalledTimes(1)
      expect(mockCreateCategory).toHaveBeenCalledWith({
        name: 'Seasonal Cleaning',
        slug: 'seasonal-cleaning',
        description: 'Hidden until launch',
        icon: 'sun',
        parentId: null,
        commissionRate: 0.12,
        active: false,
      })
    })
    expect(mockDeactivateCategory).not.toHaveBeenCalled()
  })

  it('handles failed inactive-category creation cleanly without publishing or calling deactivateCategory', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)
    mockCreateCategory.mockRejectedValueOnce(
      Object.assign(new Error("Category with slug 'draft-cat' already exists"), { status: 409 })
    )

    renderPage()
    await screen.findAllByText('Solar & Renewable Energy')

    await user.type(screen.getByLabelText(/category name/i), 'Draft Cat')
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /create & publish category/i }))

    const errorMatches = await screen.findAllByText(/already exists/i)
    expect(errorMatches.length).toBeGreaterThan(0)
    expect(mockDeactivateCategory).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/category name/i).value).toBe('Draft Cat')
    expect(screen.getByRole('checkbox').checked).toBe(false)
  })

  it('requires confirmation when Edit form transitions Active to Inactive, respects Cancel, and submits on Confirm', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValue(MOCK_TREE)
    mockUpdateCategory.mockResolvedValueOnce({
      ...MOCK_TREE[0].subcategories[0],
      active: false,
    })

    renderPage()

    const subName = await screen.findByText('Inverter Servicing')
    await user.click(subName)

    const visibilityCheckbox = screen.getByRole('checkbox')
    expect(visibilityCheckbox.checked).toBe(true)

    // Uncheck visibility and submit -> must open confirmation dialog without calling updateCategory yet
    await user.click(visibilityCheckbox)
    expect(visibilityCheckbox.checked).toBe(false)
    await user.click(screen.getByRole('button', { name: /save category changes/i }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toMatch(/Deactivate "Inverter Servicing"\?/i)
    expect(mockUpdateCategory).not.toHaveBeenCalled()

    // Cancelling leaves the category unchanged and restores visibility checkbox to Active
    await user.click(within(dialog).getByRole('button', { name: /^Cancel$/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(mockUpdateCategory).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox').checked).toBe(true)

    // Repeat and confirm -> submits updateCategory with active: false
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /save category changes/i }))
    const confirmDialog = await screen.findByRole('dialog')
    await user.click(within(confirmDialog).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => {
      expect(mockUpdateCategory).toHaveBeenCalledTimes(1)
      expect(mockUpdateCategory).toHaveBeenCalledWith('sub-inverter', {
        name: 'Inverter Servicing',
        slug: 'inverter-servicing',
        description: 'Hybrid inverter diagnostics',
        icon: 'sun',
        commissionRate: 0.14,
        active: false,
      })
    })
  })

  it('preserves backend 409 conflict handling when confirmed Edit-form deactivation fails on root with active subcategories', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)
    mockUpdateCategory.mockRejectedValueOnce(
      Object.assign(new Error('Cannot deactivate category while it has active subcategories'), {
        status: 409,
      })
    )

    renderPage()
    await screen.findByText('Inverter Servicing')

    const rootBtn = screen.getByRole('button', { name: /Solar & Renewable Energy.*Root Category/i })
    await user.click(rootBtn)
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /save category changes/i }))

    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /^Deactivate$/i }))

    const conflicts = await screen.findAllByText(
      /Cannot deactivate category while it has active subcategories/i
    )
    expect(conflicts.length).toBeGreaterThan(0)
  })

  it('supports keyboard Enter and Space selection for root and subcategory tree items independently from expand and status buttons', async () => {
    const user = userEvent.setup()
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)

    renderPage()
    await screen.findByText('Inverter Servicing')

    const rootSelectBtn = screen.getByRole('button', {
      name: /Solar & Renewable Energy.*Root Category/i,
    })
    rootSelectBtn.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByText('Editing Root Category')).toBeTruthy()
    expect(screen.getByLabelText(/category name/i).value).toBe('Solar & Renewable Energy')

    const subSelectBtn = screen.getByRole('button', { name: /Inverter Servicing.*Subcategory/i })
    subSelectBtn.focus()
    await user.keyboard(' ')
    expect(screen.getByText('Editing Subcategory')).toBeTruthy()
    expect(screen.getByLabelText(/category name/i).value).toBe('Inverter Servicing')
    expect(mockDeactivateCategory).not.toHaveBeenCalled()
  })

  it('fails closed to read-only mode when AreaAccessContext has no active area', async () => {
    mockUseAreaAccess.mockReturnValue({ area: null, canEdit: false })
    mockGetAdminCategories.mockResolvedValueOnce(MOCK_TREE)

    renderPage()
    await screen.findAllByText('Solar & Renewable Energy')
    expect(screen.queryByRole('button', { name: /add new category/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /create & publish/i })).toBeNull()
  })
})
