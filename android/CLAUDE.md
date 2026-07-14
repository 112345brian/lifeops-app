# Android app conventions (Jetpack Glance widgets)

Conventions and gotchas learned the hard way while building the LifeOps
widgets. Read this before touching `BriefingWidget.kt`, `WidgetConfigActivity.kt`,
or any `res/xml/*_widget_info.xml`.

## Sizing: one composable, branch on LocalSize — not duplicated per-bucket composables

Google's own guidance ([Build UI with Glance](https://developer.android.com/develop/ui/compose/glance/build-ui))
is a **single composable** that reads `LocalSize.current` and uses inline
`if` checks to show/hide/resize pieces — not separate composables per size
tier with independently hand-tuned constants. We violated this once
(`WeatherSection` + `CompactWeatherTile`, two near-identical copies) and it
caused repeated bugs where a fix landed in one copy and was forgotten in the
other. See `WeatherCard` for the current correct pattern: one function,
`LocalSize.current.height < MEDIUM_SIZE.height` picks between
`BASE_WEATHER_*`/`COMPACT_WEATHER_*` constant sets inline.

If you're about to write a second version of an existing section for a
different size, stop — parameterize the existing one instead.

## SizeMode.Exact vs SizeMode.Responsive

The docs recommend `SizeMode.Responsive` (pre-declared sizes, Glance
pre-renders and caches each) and call out `Exact` as a fallback for when
Responsive can't work. `BriefingWidget` deliberately uses `SizeMode.Exact`
(see the comment at its declaration) because this app supports **continuous**
drag-resize and needs the widget's true placed height to scale how many
"Up next" tasks fit — a fixed Responsive snapshot set can't do that. This is
an intentional, justified exception, not an oversight — don't "fix" it back
to Responsive without re-solving that problem.

## Glance vs Compose: two different rendering engines, verify both

`WidgetConfigActivity.kt`'s setup-screen preview is **plain Jetpack Compose**
(`Row`/`Column`/`Text` from `androidx.compose.*`). The real widget is
**Glance**, which compiles down to `RemoteViews` (`androidx.glance.*`). They
look similar in code but are genuinely different runtimes with different
layout quirks:

- A `Row` containing a multi-line `Column` positioned beside a large `Text`
  aligned correctly in the Compose preview but broke badly in the real
  Glance-rendered widget (mixed-height nested Row/Column children didn't
  align the way Compose's layout engine does). If you need two lines of text
  next to a big number, use two flat, same-height rows stacked in a
  `Column`, not a `Row` with an uneven-height `Column` child.
- Glance's `TextStyle` has **no `baselineShift`/superscript support** at
  all. The closest available approximation of a raised unit ("°F" next to a
  big temperature) is `Alignment.Top` on a two-item `Row` — not a real
  typographic superscript.
- A fix verified only in the Compose preview is not verified for the real
  widget. Always check both, or at minimum flag which one you tested.

## Widget-info XML: default placed size vs resize floor are separate knobs

Each `res/xml/*_widget_info.xml` has two independent size concepts — mixing
them up caused repeated "why is this the wrong size" cycles:

- `minWidth`/`minHeight` + `targetCellWidth`/`targetCellHeight` — the
  **default size when freshly placed**. This is what a user sees immediately
  after dragging the widget onto their home screen, before touching it.
- `minResizeWidth`/`minResizeHeight` — the **smallest the user can drag it
  down to** afterward. Independent of the default placed size.

Samsung's launcher (confirmed via live-device testing) does **not** honor
`targetCellWidth`/`targetCellHeight` for picker footprint — it recomputes
the footprint from `minWidth`/`minHeight` directly against its own grid cell
size. Two widgets with the same `targetCellWidth` can render at different
column counts in the picker if their `minWidth` values round differently.
Match `minWidth` exactly across presets you want to look consistent, don't
just match `targetCellWidth`.

## Padding: the flat-margin problem is OUR code, not a hidden system default

**The actual, confirmed cause**: `BriefingContent`'s root `Column` applies a
flat `GlanceModifier.padding(12.dp)` unconditionally, regardless of how much
room the widget actually has. On the full 4x3 combo widget 12dp is
negligible; on a single-stat preset's true 2x1/1x1 footprint (56–70dp
total), that same 12dp on all four sides eats a large fraction of the whole
widget and was directly causing content to look uncentered/clipped
(confirmed live-device: "clipped by an invisible border"). This is a real
bug in code we wrote, with a real fix: make the padding size-aware (smaller
when the widget's sole content is a single solo tile) rather than a flat
constant — see the `solo` handling in `BriefingContent`.

**A dead end, corrected here so it doesn't get re-investigated**:
`appWidgetPadding` / `appWidgetInnerRadius` / `appWidgetRadius` are **not**
real Android framework attributes with a silent system-applied default.
They're a custom `AppWidgetAttrs` `<declare-styleable>` from Google's own
widget project template (see [Updating your widget for Android 12](https://medium.com/androiddevelopers/updating-your-widget-for-android-12-92e7de87424c)) —
an app must declare this styleable itself in `attrs.xml` AND write code to
read it (`obtainStyledAttributes()` or equivalent) before it does anything
at all. We have never declared or consumed it, so it is not silently active
in either direction — there is no hidden default to "tighten" here. Glance's
own `cornerRadius()`/`background()` modifiers, or the
`androidx.glance.appwidget.components.Scaffold` component (which this
project deliberately doesn't use), are the two real levers for corner-safe
padding in a Glance widget specifically.

The official minWidth/minHeight formula for a widget's default placed size
is `70 × n − 30` dp per grid cell (n=1→40dp, n=2→110dp, n=3→180dp) — this
already matches the values in our `*_widget_info.xml` files; if you're
choosing a new default size for a preset, use this formula rather than
guessing.

Also don't fully trust the declared XML size at render time: Samsung's
launcher doesn't always grant a widget exactly what `minWidth`/`minHeight`
declared (confirmed live-device testing, same root cause as the picker
footprint issue above). If a section needs to size itself precisely (e.g. a
bitmap-rendered ring, not just text that reflows), read `LocalSize.current`
directly inside that composable and clamp to it, rather than assuming the
XML's declared size is what's actually available.

## `android:widgetFeatures="reconfigurable"` (API 31+)

Without this flag, some launchers (confirmed on Samsung One UI) never
surface a reconfigure/settings option on long-press after initial
placement — only "remove". All seven `*_widget_info.xml` files here declare
`android:widgetFeatures="reconfigurable"`; keep it on any new preset. Ignored
below API 31 (this app's `minSdk` is 26), no downside to always declaring
it. Source: [Enable users to configure app widgets](https://developer.android.com/develop/ui/compose/glance/configuration).

This flag change only takes effect for a widget's *next placement* — an
already-placed instance won't retroactively gain the option; it has to be
removed and re-added.

## Config screen must explicitly call `update()`

Saving a widget's config does not implicitly trigger a redraw. Per the
[official Glance configuration guide](https://developer.android.com/develop/ui/compose/glance/configuration),
the configure Activity must call `GlanceAppWidget().update(context, glanceId)`
itself after persisting state — `WidgetConfigActivity.saveAndFinish()`
already does this correctly. Don't remove it, and if you add a new
persistence path, make sure it calls `update()` too.

## Reinstalling the app does NOT force a placed widget to redraw

`adb install -r` does not guarantee an already-placed Glance widget
re-composes with the new code. If you've changed widget-rendering code and
a live device doesn't show the change, that's not necessarily a bug in the
change — it may just be a stale, previously-rendered `RemoteViews` still
being shown. To force a real, verifiable fresh render when testing:

1. Best: remove the placed widget and re-add it (guaranteed fresh
   `provideGlance`/`provideContent` call, and picks up any `res/xml`
   changes like `widgetFeatures` or `minWidth` too).
2. If reconfiguring in place: open the widget's config screen and tap
   **Save** (even without changing anything) — that explicitly calls
   `update()`, which forces a real recomposition.
3. A plain `am force-stop` + relaunching the app's main activity does
   **not** reliably trigger a widget redraw — the widget is hosted by the
   launcher process, not the app's own process, and relaunching the app's
   `LauncherActivity` doesn't touch Glance's update pipeline at all.
4. `am broadcast -a android.appwidget.action.APPWIDGET_UPDATE` from `adb
   shell` is blocked by a `SecurityException` (protected broadcast, not
   sendable from an unprivileged shell caller) — don't bother trying it.

## XML comments in `*_widget_info.xml`: no literal `--`

Standard XML rule, easy to trip over in long prose comments: a comment body
cannot contain the two-character sequence `--` anywhere, only at the very
start/end (`<!--`/`-->`). Breaks the Gradle resource-parsing step with
`The string "--" is not permitted within comments`. Use `:` or reword
instead of an em-dash-style `--` when writing comments in these files.
