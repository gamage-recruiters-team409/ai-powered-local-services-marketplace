// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

async function renderAppAt(path) {
  vi.resetModules();
  window.history.replaceState({}, '', path);
  const { default: App } = await import('../../../App.jsx');
  return render(<App />);
}

describe('provider profile integration', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'true');
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
    window.sessionStorage.clear();
  });

  it('opens the intended provider profile from a real search card link', async () => {
    const user = userEvent.setup();
    await renderAppAt('/search');

    const profileLink = await screen.findByRole('link', {
      name: "View profile for Maya's Home Care",
    });

    expect(profileLink.getAttribute('href')).toBe('/providers/prv-001');

    await user.click(profileLink);

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "Maya's Home Care",
      }),
    ).toBeTruthy();
    expect(window.location.pathname).toBe('/providers/prv-001');
  });

  it('renders a provider profile from a direct URL and after a reload', async () => {
    await renderAppAt('/providers/prv-002');

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'BrightNest Cleaning',
      }),
    ).toBeTruthy();

    cleanup();
    await renderAppAt('/providers/prv-002');

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'BrightNest Cleaning',
      }),
    ).toBeTruthy();
  });
});
