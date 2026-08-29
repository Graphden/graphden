// Editor A11y — shared accessibility primitives.
//
// Loaded second (right after editor-state.js) because the modules that
// need these run early: the graph tween in editor-graph-model.js asks
// whether it may animate at all before its first frame.
//
// CSS handles the declarative half of reduced-motion (a
// `@media (prefers-reduced-motion: reduce)` block at the end of
// editor-styles.css neutralises transitions and decorative keyframes).
// This module covers what CSS cannot reach: motion driven from JS —
// the requestAnimationFrame position tween and smooth scrollIntoView.

const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/**
 * True when the user has asked the platform for less motion.
 *
 * Queried live rather than cached at boot: the OS setting can be
 * toggled while the page is open, and matchMedia reflects that
 * immediately. The call is cheap enough to make per-animation.
 */
function prefersReducedMotion() {
  try {
    return window.matchMedia(REDUCED_MOTION_QUERY).matches;
  } catch (_) {
    // matchMedia is absent in some test harnesses — motion is the
    // safe default there, since nothing is actually rendered.
    return false;
  }
}

/**
 * `scrollIntoView` options with `behavior` chosen for the current
 * motion preference. Pass the options you want; the behavior key is
 * overwritten with 'auto' (instant) when reduced motion is on.
 */
function scrollIntoViewMotionSafe(el, options = {}) {
  if (!el || typeof el.scrollIntoView !== 'function') return;
  el.scrollIntoView({
    ...options,
    behavior: prefersReducedMotion() ? 'auto' : (options.behavior || 'auto'),
  });
}

window.prefersReducedMotion = prefersReducedMotion;
window.scrollIntoViewMotionSafe = scrollIntoViewMotionSafe;
