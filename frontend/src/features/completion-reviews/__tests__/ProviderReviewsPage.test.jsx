// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

import ProviderReviewsPage from '../pages/ProviderReviewsPage'
import { filterProviderReviews, initials, notRepliedCount, ratingDistribution } from '../utils/reviewUtils'

// These screen tests open dialogs and type text; slower PCs need more than the 5 s default.
vi.setConfig({ testTimeout: 20000 })

afterEach(() => cleanup())

const reviews = [
  { id: 'r1', providerId: 'p-1', customerName: 'Amaya Rathnayake', rating: 5, comment: 'Careful job.', status: 'PUBLISHED', providerReply: null, edited: true, createdAt: '2026-11-18T04:00:00Z' },
  { id: 'r2', providerId: 'p-1', customerName: 'Ravi Silva', rating: 4, comment: 'A little late.', status: 'PUBLISHED', providerReply: { text: 'Sorry for the delay!' }, edited: false, createdAt: '2026-11-16T04:00:00Z' },
  { id: 'r3', providerId: 'p-1', rating: 2, comment: 'Did not finish.', status: 'PUBLISHED', providerReply: null, edited: false, createdAt: '2026-11-15T04:00:00Z' },
  { id: 'r4', providerId: 'p-1', customerName: 'Kasun J', rating: 3, comment: 'Spam', status: 'REMOVED', providerReply: null, edited: false, createdAt: '2026-11-10T04:00:00Z' },
]
const summary = { providerId: 'p-1', averageRating: 3.7, reviewCount: 3, ratingCounts: { 5: 1, 4: 1, 3: 0, 2: 1, 1: 0 } }

function fakeApi(overrides = {}) {
  return {
    getProviderReviews: vi.fn().mockResolvedValue(reviews),
    getRatingSummary: vi.fn().mockResolvedValue(summary),
    replyToReview: vi.fn().mockImplementation(async (id, { text }) => ({
      ...reviews.find((r) => r.id === id),
      providerReply: { text, repliedAt: '2026-11-18T09:00:00Z' },
    })),
    ...overrides,
  }
}

const renderPage = (api) =>
  render(
    <MemoryRouter>
      <ProviderReviewsPage api={api} />
    </MemoryRouter>,
  )

describe('S11 Provider My Reviews', () => {
  it('shows the stats, the rating distribution and the review cards', async () => {
    const api = fakeApi()
    renderPage(api)

    expect(await screen.findByText('Amaya Rathnayake')).toBeTruthy()
    expect(api.getRatingSummary).toHaveBeenCalledWith('p-1')
    expect(screen.getAllByText('3.7').length).toBeGreaterThan(0)
    expect(within(screen.getByText('Not replied', { selector: '.rv-stat-label' }).parentElement).getByText('2')).toBeTruthy()
    expect(screen.getByRole('meter', { name: '5 star reviews' }).getAttribute('aria-valuenow')).toBe('33')
    expect(screen.getByText('Customer')).toBeTruthy() // no name from the API -> "Customer"
    expect(screen.getByText('Sorry for the delay!')).toBeTruthy()
    expect(screen.getByText(/Removed by a moderator/)).toBeTruthy()
    // Reply only for published reviews without a reply
    expect(screen.getAllByRole('button', { name: /^Reply to/ })).toHaveLength(2)
  })

  it('filters by "Not replied" and by rating', async () => {
    const user = userEvent.setup()
    renderPage(fakeApi())

    await screen.findByText('Amaya Rathnayake')
    await user.click(screen.getByRole('button', { name: 'Not replied' }))
    expect(screen.queryByText('Ravi Silva')).toBeNull()
    expect(screen.queryByText('Kasun J')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Not replied' }))
    await user.selectOptions(screen.getAllByRole('combobox')[0], '4')
    expect(screen.getByText('Ravi Silva')).toBeTruthy()
    expect(screen.queryByText('Amaya Rathnayake')).toBeNull()
  })

  it('S12: posts a reply, then shows it and hides the Reply button', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    renderPage(api)

    await user.click(await screen.findByRole('button', { name: 'Reply to Amaya Rathnayake' }))
    await user.click(screen.getByRole('button', { name: 'Post reply' }))
    expect(screen.getByText('Please write your reply.')).toBeTruthy()

    await user.type(screen.getByRole('textbox', { name: /Your public reply/ }), '  Thank you, Amaya!  ')
    await user.click(screen.getByRole('button', { name: 'Post reply' }))

    await waitFor(() => expect(api.replyToReview).toHaveBeenCalledWith('r1', { text: 'Thank you, Amaya!' }))
    expect(await screen.findByText('Your reply has been posted.')).toBeTruthy()
    expect(screen.getByText('Thank you, Amaya!')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Reply to Amaya Rathnayake' })).toBeNull()
  })

  it('S12: keeps the form open with the backend message when the reply fails', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      replyToReview: vi.fn().mockRejectedValue({ response: { status: 409, data: { message: 'You can only reply to a published review' } } }),
    })
    renderPage(api)

    await user.click(await screen.findByRole('button', { name: 'Reply to Amaya Rathnayake' }))
    await user.type(screen.getByRole('textbox', { name: /Your public reply/ }), 'Thanks')
    await user.click(screen.getByRole('button', { name: 'Post reply' }))
    expect(await screen.findByText('You can only reply to a published review')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Post reply' })).toBeTruthy()
  })

  it('shows the empty state and does not ask for a summary without reviews', async () => {
    const api = fakeApi({ getProviderReviews: vi.fn().mockResolvedValue([]) })
    renderPage(api)

    expect(await screen.findByText('No reviews yet')).toBeTruthy()
    expect(api.getRatingSummary).not.toHaveBeenCalled()
  })
})

describe('provider review helpers', () => {
  it('counts not-replied published reviews and sorts/filters', () => {
    expect(notRepliedCount(reviews)).toBe(2)
    expect(filterProviderReviews(reviews, { sort: 'lowest' }).map((r) => r.id)).toEqual(['r3', 'r4', 'r2', 'r1'])
    expect(filterProviderReviews(reviews, { rating: '5' }).map((r) => r.id)).toEqual(['r1'])
  })

  it('builds the distribution and initials', () => {
    expect(ratingDistribution(summary).map((r) => r.percent)).toEqual([33, 33, 0, 33, 0])
    expect(ratingDistribution(null).every((r) => r.percent === 0)).toBe(true)
    expect(initials('Amaya Rathnayake')).toBe('AR')
    expect(initials(undefined)).toBe('C')
  })
})
