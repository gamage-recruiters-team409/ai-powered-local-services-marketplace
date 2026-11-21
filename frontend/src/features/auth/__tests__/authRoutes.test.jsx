// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { authRoutes } from '../routes/authRoutes';

describe('authRoutes', () => {
  it('registers all standard auth routes', () => {
    const paths = authRoutes.children.map((r) => r.path);
    expect(paths).toContain('/login');
    expect(paths).toContain('/register');
    expect(paths).toContain('/check-email');
    expect(paths).toContain('/verify-email');
    expect(paths).toContain('/forgot-password');
    expect(paths).toContain('/reset-password');
    expect(paths).toContain('/change-password');
  });

  it('dev preview route inclusion matches import.meta.env.DEV flag', () => {
    const hasPreview = authRoutes.children.some((r) => r.path === '/dev/auth-preview');
    expect(hasPreview).toBe(Boolean(import.meta.env.DEV));
  });
});
