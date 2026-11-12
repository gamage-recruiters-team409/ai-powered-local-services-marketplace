// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

import { router } from './router.jsx';

function collectPaths(routes, acc = []) {
  routes.forEach((route) => {
    if (route.path) acc.push(route.path);
    if (route.children) collectPaths(route.children, acc);
  });
  return acc;
}

describe('shared application router', () => {
  const paths = collectPaths(router.routes);

  it('mounts search at /search', () => {
    expect(paths).toContain('/search');
  });

  it('preserves administrative routes', () => {
    expect(paths).toContain('/admin');
  });

  it('does not hijack the root path for search', () => {
    expect(paths).not.toContain('/');
  });

  it('keeps a shared catch-all fallback', () => {
    expect(paths).toContain('*');
  });
});
