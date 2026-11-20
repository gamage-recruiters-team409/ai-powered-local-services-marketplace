// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import MyReviewsPage from '../pages/MyReviewsPage'
import WriteReviewPage from '../pages/WriteReviewPage'
import EditReviewPage from '../pages/EditReviewPage'

// These screen tests open dialogs and type text; slower PCs need more than the 5 s default.
vi.setConfig({ testTimeout: 20000 })

afterEach(() => cleanup())

const NOW = new Date('2026-11-18T10:00:00Z')
const hoursAgo = (h) => new Date(NOW.getTime() - h * 3600 * 1000).toISOString()

const booking = { id: 'bk-1', ref: 'BK-1', title: 'Home cleaning – 3 rooms', providerName: 'Bright Home Cleaning', completedAt: hoursAgo(20) }

const published = { id: 'rv-1', bookingId: 'bk-9', rating: 4, comment: 'Careful job.', status: 'PUBLISHED', providerReply: null, edited: false, createdAt: hoursAgo(5) }
const replied = { id: 'rv-2', bookingId: 'bk-8', rating: 5, comment: 'Great.', status: 'PUBLISHED', providerReply: { text: 'Thank you!' }, edited: true, createdAt: hoursAgo(10) }
const removed = { id: 'rv-3', bookingId: 'bk-7', rating: 1, comment: null, status: 'REMOVED', providerReply: null, edited: false, createdAt: hoursAgo(100) }

function fakeApi(overrides = {}) {
  return {
    supportsToReview: true,
    getMyReviews: vi.fn().mockResolvedValue([published, replied, removed]),
    getBookingsToReview: vi.fn().mockResolvedValue([booking]),
    getBookingSummary: vi.fn().mockResolvedValue(null),
    createReview: vi.fn().mockResolvedValue({ ...published, id: 'rv-new', bookingId: 'bk-1' }),
    updateReview: vi.fn().mockResolvedValue({ ...published, edited: true }),
    ...overrides,
  }
}

function renderAt(path, api, state) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      <Routes>
        <Route path="/customer/reviews" element={<MyReviewsPage api={api} now={NOW} />} />
        <Route path="/customer/reviews/new/:bookingId" element={<WriteReviewPage api={api} />} />
        <Route path="/customer/reviews/:reviewId/edit" element={<EditReviewPage api={api} now={NOW} />} />
        <Route path="/search" element={<p>Search page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('S09 My Reviews', () => {
  it('opens on "To review" with counts and goes to S07 from "Leave a review"', async () => {
    const user = userEvent.setup()
    renderAt('/customer/reviews', fakeApi())

    const toReviewTab = await screen.findByRole('tab', { name: /To review/ })
    await waitFor(() => expect(toReviewTab.getAttribute('aria-selected')).toBe('true'))
    expect(within(toReviewTab).getByText('1')).toBeTruthy()
    expect(within(screen.getByRole('tab', { name: /My reviews/ })).getByText('3')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: /Leave a review/ }))
    expect(await screen.findByRole('heading', { name: 'Leave a review' })).toBeTruthy()
    expect(screen.getByText('Home cleaning – 3 rooms')).toBeTruthy()
  })

  it('shows statuses, Edit only within 48 h before a reply, the reply and the removed message', async () => {
    const user = userEvent.setup()
    renderAt('/customer/reviews', fakeApi())

    await user.click(await screen.findByRole('tab', { name: /My reviews/ }))
    expect(screen.getAllByText('Published')).toHaveLength(2)
    expect(screen.getByText('Removed')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /^Edit review/ })).toHaveLength(1)
    expect(screen.getByText('Thank you!')).toBeTruthy()
    expect(screen.getByText(/provider has replied/)).toBeTruthy()
    expect(screen.getByText('Removed by a moderator.')).toBeTruthy()
    expect(screen.getByText('Edited')).toBeTruthy()
  })

  it('shows the empty state with "Browse services"', async () => {
    const user = userEvent.setup()
    renderAt('/customer/reviews', fakeApi({ getMyReviews: vi.fn().mockResolvedValue([]), getBookingsToReview: vi.fn().mockResolvedValue([]) }))

    expect(await screen.findByText('No reviews yet')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Browse services' }))
    expect(await screen.findByText('Search page')).toBeTruthy()
  })

  it('shows a notice in "To review" while the booking list is not connected (real API)', async () => {
    const user = userEvent.setup()
    renderAt('/customer/reviews', fakeApi({ supportsToReview: false, getBookingsToReview: vi.fn().mockResolvedValue([]) }))

    await user.click(await screen.findByRole('tab', { name: /To review/ }))
    expect(screen.getByText(/Open a completed booking from My Bookings/)).toBeTruthy()
  })

  it('shows the error with a retry button', async () => {
    const user = userEvent.setup()
    const api = fakeApi({ getMyReviews: vi.fn().mockRejectedValueOnce({ response: { status: 500 } }).mockResolvedValue([]) })
    renderAt('/customer/reviews', api)

    expect(await screen.findByText('Something went wrong. Please try again.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.getMyReviews).toHaveBeenCalledTimes(2))
  })
})

describe('S07 Write review', () => {
  it('requires a rating, then submits the trimmed review and returns to My Reviews', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    renderAt('/customer/reviews/new/bk-1', api, { booking })

    await user.click(screen.getByRole('button', { name: 'Submit review' }))
    expect(screen.getByText('Please choose a rating from 1 to 5 stars.')).toBeTruthy()
    expect(api.createReview).not.toHaveBeenCalled()

    await user.click(screen.getByRole('radio', { name: /4 stars/ }))
    expect(screen.getByText('Very good')).toBeTruthy()
    await user.type(screen.getByRole('textbox'), '  Arrived on time.  ')
    await user.click(screen.getByRole('button', { name: 'Submit review' }))

    await waitFor(() =>
      expect(api.createReview).toHaveBeenCalledWith({ bookingId: 'bk-1', rating: 4, comment: 'Arrived on time.' }),
    )
    expect(await screen.findByText('Thanks! Your review has been published.')).toBeTruthy()
    expect(screen.getByRole('tab', { name: /My reviews/ }).getAttribute('aria-selected')).toBe('true')
  })

  it('supports the keyboard for the star rating', async () => {
    const user = userEvent.setup()
    renderAt('/customer/reviews/new/bk-1', fakeApi(), { booking })

    await user.click(screen.getByRole('radio', { name: /2 stars/ }))
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('radio', { name: /3 stars/ }).getAttribute('aria-checked')).toBe('true')
  })

  it('shows the backend message when the review is rejected', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      createReview: vi.fn().mockRejectedValue({
        response: { status: 409, data: { message: 'Reviews can be submitted within 7 days of completion' } },
      }),
    })
    renderAt('/customer/reviews/new/bk-1', api, { booking })

    await user.click(screen.getByRole('radio', { name: /5 stars/ }))
    await user.click(screen.getByRole('button', { name: 'Submit review' }))
    expect(await screen.findByText('Reviews can be submitted within 7 days of completion')).toBeTruthy()
  })
})

describe('S08 Edit review', () => {
  it('pre-fills the review and saves the changes', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    renderAt('/customer/reviews/rv-1/edit', api, { review: published })

    expect(screen.getByRole('radio', { name: /4 stars/ }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('textbox').value).toBe('Careful job.')
    expect(screen.getByText(/Editable until/)).toBeTruthy()

    await user.click(screen.getByRole('radio', { name: /3 stars/ }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(api.updateReview).toHaveBeenCalledWith('rv-1', { rating: 3, comment: 'Careful job.' }))
    expect(await screen.findByText('Your changes have been saved.')).toBeTruthy()
  })

  it('loads the review on a direct visit and blocks editing after a provider reply', async () => {
    renderAt('/customer/reviews/rv-2/edit', fakeApi())

    expect(await screen.findByText(/provider has replied/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save changes' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Back to My Reviews' })).toBeTruthy()
  })

  it('shows "not found" for an unknown review', async () => {
    renderAt('/customer/reviews/rv-x/edit', fakeApi())
    expect(await screen.findByText('This review could not be found.')).toBeTruthy()
  })
})
