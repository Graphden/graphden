# Lesson 18 — Working without the mouse

**Goal**: by the end of this lesson you can do everything
[lesson 17](17-explorer-and-inspector.md) just showed you — find a
function, read it in the Inspector, move around its graph — without
touching the mouse, and you know how to make the editor readable if
small text is hard to see.

**Concepts introduced**: the leader key (`Space`) and the which-key
menu, the shortcut cheatsheet (`?`), the roving tab stop, walking the
graph along its edges, the skip link, and how the editor responds to
your browser's font-size and reduced-motion settings.

## The idea in one paragraph

There are two kinds of key in the editor. A **bare key** is one you
press on its own — there are only four (`/`, `+`, `-`, `?`), because
the letter keys belong to graph navigation. Everything else lives
behind the **leader**: press `Space` and a menu appears listing what
the next key does. You never have to memorise anything — press
`Space` and read.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=18)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

## Try it: find a function without touching the mouse

1. Press `/`. The Explorer's filter field takes focus.
2. Type part of a function name, e.g. `web`.
3. Press `Escape` to leave the field, then `Tab` until the Explorer
   tree has focus.

The tree is one tab stop, not hundreds: `Tab` puts you *in* it, and
from there the arrows move:

| Key | In the Explorer tree |
|-----|----------------------|
| `↑` `↓` | previous / next row |
| `→` | expand a namespace, or step into it |
| `←` | collapse it, or step out to its parent |
| `Enter` | open the function on the canvas |
| `Home` `End` | first / last row |

Open a function with `Enter`. The tree rebuilds itself when you do —
but your place in it does not move.

## Walking the graph

Press `Space`, then `g`, then `g` — the keyboard moves into the
canvas. Now the arrows follow the **wiring**, not the screen:

| Key | On the canvas |
|-----|---------------|
| `→` or `l` | into an argument — the thing this function is built from |
| `←` or `h` | back to the consumer — the function that uses this one |
| `↑` `↓` or `k` `j` | between arguments of the same consumer |
| `Enter` | open this card in the inspector, and step into its rows |
| `↑` `↓` (inside a card) | between the card's rows — its ancestors and arguments |
| `.` or `m` (on a row) | that row's actions, the ⋯ menu |
| `Escape` | back out — first to the card, then to the canvas |
| `Shift`+arrows | move the card itself, in grid steps |

This is worth pausing on. `→` does not mean "the card drawn to the
right" — it means "an argument of this function". You are walking the
composition, which is what the picture is *of*. If a card is
off-screen, the view pans to it.

At the canvas level (after `Escape`) the arrows pan, `+` and `-`
zoom, and `Space g f` fits the whole graph on screen.

## Dialogs

Every popover and dialog behaves the same way: opening one moves the
keyboard into it, `Tab` cycles inside it rather than escaping to the
page behind, and `Escape` closes it and puts the keyboard back on
whatever opened it. You can open the function picker, change your
mind, press `Escape`, and continue from exactly where you were.

The account button's menu is a menu in the keyboard sense too: it
opens with focus on the first entry, `↑` `↓` walk the entries, and
`Escape` puts you back on the button.

## The other surfaces

Settings, Organization and Platform are behind the leader as well:
`Space v` opens the **Surfaces** group — `s` for Settings, `o` for
Organization, `b` straight back to Build. A surface behaves like a
big dialog: opening one moves the keyboard into its section list,
everything underneath leaves the tab order, and `Escape` returns you
to the editor.

## Seeing everything: `?`

Press `?` for the full list of shortcuts. It is generated from the
same registry the `Space` menu uses, so it always matches what is
actually bound — including keys added by later versions.

## If the text is too small

The editor is sized in relative units, so **your browser's font-size
setting works**: raise it (in Chrome, Settings → Appearance → Font
size; in Firefox, Settings → Fonts & Colors) and everything scales —
sidebar, inspector, and the graph cards along with the names inside
them. Page zoom (`Ctrl`/`Cmd` `+`) works too and scales images as
well; the font-size setting is the better choice when you only want
larger *text*.

If your operating system asks for **increased contrast**, the editor
obliges: muted text darkens (or brightens, in the dark theme), hairline
borders become real lines, and the focus ring gets heavier. Windows
High Contrast mode works too — selection is drawn as an outline there,
since backgrounds are repainted by the system.

If you have "reduce motion" turned on in your operating system, the
editor drops its animations: cards jump straight to their new
positions instead of gliding, and the decorative pulses stop. Progress
spinners keep turning — they are how you know something is still
running.

## Screen readers

The editor announces the things that change without moving your
focus: which function you selected, where you moved on the canvas
(including how many arguments and consumers a node has, since that
wiring is not visible any other way), and each step of the
interactive tutorial.

The first `Tab` on a fresh page offers **Skip to graph**, which jumps
past the Explorer tree — useful when the tree holds hundreds of rows
and you want the canvas.

## What you learned

- `Space` opens a menu of commands; `?` lists them all.
- `/` searches, `+`/`-` zoom — the only bare keys.
- The Explorer tree and the canvas are each one tab stop, navigated
  with arrows.
- On the canvas the arrows follow edges, so you walk the composition
  rather than the picture.
- The browser's font-size and reduce-motion settings are respected.

Next: [lesson 19](19-workspaces.md) narrows the Explorer to the
namespaces you actually work in — which, combined with `/` and the
arrow keys, is the fastest way around a large graph.
