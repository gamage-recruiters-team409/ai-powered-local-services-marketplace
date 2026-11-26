// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { CustomerAvatar } from './CustomerAvatar'

afterEach(() => {
  cleanup()
})

describe('CustomerAvatar', () => {
  it('shows image when src is provided', () => {
    const { container } = render(
      <CustomerAvatar name="Kamal Perera" src="https://cdn.example.com/avatar.jpg" className="test-avatar" />
    )

    const img = container.querySelector('img')
    expect(img).toBeTruthy()
    expect(img.getAttribute('src')).toBe('https://cdn.example.com/avatar.jpg')
    expect(screen.queryByText('KP')).toBeNull()
  })

  it('shows initials when no src is provided', () => {
    const { container } = render(
      <CustomerAvatar name="Kamal Perera" src={null} className="test-avatar" />
    )

    expect(screen.getByText('KP')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })

  it('shows initials when src is an empty or whitespace string', () => {
    const { container } = render(
      <CustomerAvatar name="Kamal Perera" src="   " className="test-avatar" />
    )

    expect(screen.getByText('KP')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })

  it('falls back to initials on img error', () => {
    const { container } = render(
      <CustomerAvatar name="Kamal Perera" src="https://broken.example.com/photo.jpg" className="test-avatar" />
    )

    const img = container.querySelector('img')
    expect(img).toBeTruthy()
    fireEvent.error(img)

    expect(screen.getByText('KP')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })

  it('resets failed state after src changes to a new value', () => {
    const { container, rerender } = render(
      <CustomerAvatar name="Kamal Perera" src="https://broken.example.com/photo.jpg" className="test-avatar" />
    )

    // Trigger error on initial image
    const img = container.querySelector('img')
    expect(img).toBeTruthy()
    fireEvent.error(img)
    expect(screen.getByText('KP')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()

    // Rerender with a new src
    rerender(
      <CustomerAvatar name="Kamal Perera" src="https://working.example.com/new-photo.jpg" className="test-avatar" />
    )

    const newImg = container.querySelector('img')
    expect(newImg).toBeTruthy()
    expect(newImg.getAttribute('src')).toBe('https://working.example.com/new-photo.jpg')
    expect(screen.queryByText('KP')).toBeNull()
  })

  it('maintains aria-hidden="true" on wrapper span', () => {
    const { container } = render(<CustomerAvatar name="Kamal Perera" src={null} className="test-avatar" />)

    const span = container.querySelector('span')
    expect(span.getAttribute('aria-hidden')).toBe('true')
    expect(span.className).toBe('test-avatar')
  })
})
