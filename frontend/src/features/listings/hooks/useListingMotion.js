import { useEffect, useRef, useState } from 'react'

function prefersReducedMotion() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return true
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Smoothly rolls a numeric display value toward `target` using cubic ease-out
 * via requestAnimationFrame. Immediately returns `target` under reduced-motion or JSDOM.
 */
export function useAnimatedNumber(target, durationMs = 280) {
  const numericTarget = Number.isFinite(Number(target)) ? Number(target) : 0
  const [animatedValue, setAnimatedValue] = useState(numericTarget)
  const previousRef = useRef(numericTarget)
  const canAnimate =
    !prefersReducedMotion() &&
    typeof window !== 'undefined' &&
    typeof window.requestAnimationFrame === 'function'

  useEffect(() => {
    const startVal = previousRef.current
    if (!canAnimate || startVal === numericTarget) {
      previousRef.current = numericTarget
      return undefined
    }

    let frameId = 0
    const startTime = performance.now()

    const tick = (now) => {
      const elapsed = now - startTime
      const progress = Math.min(1, elapsed / durationMs)
      const eased = 1 - Math.pow(1 - progress, 3)
      const next = startVal + (numericTarget - startVal) * eased
      setAnimatedValue(next)
      if (progress < 1) {
        frameId = window.requestAnimationFrame(tick)
      } else {
        previousRef.current = numericTarget
        setAnimatedValue(numericTarget)
      }
    }

    frameId = window.requestAnimationFrame(tick)
    return () => {
      if (frameId) window.cancelAnimationFrame(frameId)
      previousRef.current = numericTarget
    }
  }, [canAnimate, numericTarget, durationMs])

  return canAnimate ? animatedValue : numericTarget
}

/**
 * Measures the `[data-slide-key="<activeKey>"]` button inside `containerRef`
 * and returns a pure CSS style object for a smooth gliding pill indicator.
 */
export function useSlidingIndicator(containerRef, activeKey, depSignature = '') {
  const [indicator, setIndicator] = useState({ left: 0, width: 0, ready: false })

  useEffect(() => {
    if (typeof window === 'undefined') return undefined

    const measure = () => {
      const container = containerRef?.current
      if (!container) return
      const activeNode = container.querySelector(`[data-slide-key="${activeKey}"]`)
      if (!activeNode) {
        setIndicator((prev) => (prev.ready ? { ...prev, ready: false } : prev))
        return
      }
      const left = activeNode.offsetLeft
      const width = activeNode.offsetWidth
      if (width > 0) {
        setIndicator((prev) =>
          prev.left === left && prev.width === width && prev.ready
            ? prev
            : { left, width, ready: true },
        )
      }
    }

    const frameId = window.requestAnimationFrame(measure)
    window.addEventListener('resize', measure)
    return () => {
      window.cancelAnimationFrame(frameId)
      window.removeEventListener('resize', measure)
    }
  }, [containerRef, activeKey, depSignature])

  return indicator.ready
    ? {
        width: `${indicator.width}px`,
        transform: `translateX(${indicator.left}px)`,
        opacity: 1,
      }
    : { opacity: 0 }
}
