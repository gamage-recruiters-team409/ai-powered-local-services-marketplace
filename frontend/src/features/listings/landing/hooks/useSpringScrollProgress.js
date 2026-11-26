import { useEffect, useRef, useState } from 'react'

/**
 * Computes raw 0..1 scroll progress through a tall pinned container and smoothly integrates it
 * using Hooke's-law spring physics (`stiffness: 180, damping: 30`) via `requestAnimationFrame`.
 *
 * Calls `onProgress(smoothProgress, rawProgress)` directly inside the animation loop so high-DPI
 * canvas draws and choreographed beat transforms run at native 60fps with zero React re-renders.
 */
export function useSpringScrollProgress(
  containerRef,
  onProgress,
  { stiffness = 180, damping = 30 } = {}
) {
  const callbackRef = useRef(onProgress)

  useEffect(() => {
    callbackRef.current = onProgress
  }, [onProgress])

  useEffect(() => {
    const container = containerRef.current
    if (!container || typeof window === 'undefined') return undefined

    const reducedMotionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    const prefersReducedMotion = Boolean(reducedMotionQuery?.matches)

    let target = 0
    let current = 0
    let velocity = 0
    let rafId = null
    let lastTime = performance.now()

    const computeTarget = () => {
      const rect = container.getBoundingClientRect()
      const scrollableDistance = Math.max(container.offsetHeight - window.innerHeight, 1)
      const scrolled = Math.min(Math.max(-rect.top, 0), scrollableDistance)
      return scrolled / scrollableDistance
    }

    const step = (now) => {
      const dt = Math.min((now - lastTime) / 1000, 0.05)
      lastTime = now

      if (prefersReducedMotion) {
        current = target
        velocity = 0
      } else {
        const force = -stiffness * (current - target)
        const damper = -damping * velocity
        const acceleration = force + damper
        velocity += acceleration * dt
        current += velocity * dt
      }

      const settled =
        Math.abs(target - current) < 0.0004 && Math.abs(velocity) < 0.0004
      if (settled) {
        current = target
        velocity = 0
      }

      const clamped = Math.min(Math.max(current, 0), 1)
      callbackRef.current?.(clamped, target)

      if (!settled) {
        rafId = window.requestAnimationFrame(step)
      } else {
        rafId = null
      }
    }

    const schedule = () => {
      target = computeTarget()
      if (rafId === null) {
        lastTime = performance.now()
        rafId = window.requestAnimationFrame(step)
      }
    }

    // Initial sync on mount
    target = computeTarget()
    current = target
    callbackRef.current?.(current, target)

    window.addEventListener('scroll', schedule, { passive: true })
    window.addEventListener('resize', schedule, { passive: true })

    return () => {
      window.removeEventListener('scroll', schedule)
      window.removeEventListener('resize', schedule)
      if (rafId !== null) {
        window.cancelAnimationFrame(rafId)
      }
    }
  }, [containerRef, stiffness, damping])
}

/**
 * Triggers once when an element enters the viewport (falls back to `true` in environments
 * without IntersectionObserver such as jsdom unit tests).
 */
export function useInViewOnce(ref, threshold = 0.2) {
  const [inView, setInView] = useState(
    () => typeof window === 'undefined' || typeof window.IntersectionObserver === 'undefined'
  )

  useEffect(() => {
    if (inView) return undefined
    const node = ref.current
    if (!node || typeof window.IntersectionObserver === 'undefined') return undefined

    const observer = new window.IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setInView(true)
          observer.disconnect()
        }
      },
      { threshold }
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [ref, threshold, inView])

  return inView
}
