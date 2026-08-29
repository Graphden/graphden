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
//
// It also owns `announce()`: the editor changes a lot of state without
// moving focus (a fn is selected, a branch switched, a lens toggled), and
// none of that reaches a screen reader on its own. The focus primitives
// live one layer down in web/runtime/graphden-popover.js, which the
// standalone runtime bundle shares.

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

// ── Announcements ───────────────────────────────────────────────────────────

const ANNOUNCER_ID = 'gd-a11y-announcer';
const ASSERTIVE_ANNOUNCER_ID = 'gd-a11y-announcer-assertive';

let _lastAnnouncement = '';

function ensureAnnouncer(id, live, role) {
  let el = document.getElementById(id);
  if (el) return el;
  el = document.createElement('div');
  el.id = id;
  el.className = 'visually-hidden';
  el.setAttribute('role', role);
  el.setAttribute('aria-live', live);
  // Read the whole message on change rather than diffing the text — the
  // messages are short sentences, not incremental logs.
  el.setAttribute('aria-atomic', 'true');
  document.body.appendChild(el);
  return el;
}

/**
 * Say something to a screen reader without moving focus.
 *
 * `polite` (default) waits for the current utterance to finish; `assertive`
 * interrupts and is for things the user must hear before they act again —
 * a failed write, a destructive confirmation.
 *
 * Repeating the exact same string is a no-op for most screen readers (the
 * region's text did not change), so an identical consecutive message gets a
 * zero-width suffix to force it through.
 */
function announce(message, opts) {
  const text = (message == null ? '' : String(message)).trim();
  if (!text) return;
  const assertive = !!opts?.assertive;
  const el = assertive
    ? ensureAnnouncer(ASSERTIVE_ANNOUNCER_ID, 'assertive', 'alert')
    : ensureAnnouncer(ANNOUNCER_ID, 'polite', 'status');
  const payload = text === _lastAnnouncement ? text + '​' : text;
  _lastAnnouncement = text;
  // Clear first: a live region that already holds text sometimes drops an
  // update written in the same tick.
  el.textContent = '';
  requestAnimationFrame(() => { el.textContent = payload; });
}

window.prefersReducedMotion = prefersReducedMotion;
window.scrollIntoViewMotionSafe = scrollIntoViewMotionSafe;
window.gdAnnounce = announce;
