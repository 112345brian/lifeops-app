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

**The actual, confirmed cause**: `BriefingContent`'s root `Column` used to
apply a flat `GlanceModifier.padding(12.dp)` unconditionally, regardless of
how much room the widget actually had. On the full 4x3 combo widget 12dp was
noticeable; on a single-stat preset's true 2x1/1x1 footprint (56–70dp total),
that same 12dp on all four sides ate a large fraction of the whole widget and
was directly causing content to look uncentered/clipped (confirmed
live-device: "clipped by an invisible border"). This is a real bug in code we
wrote, with a real fix: keep root padding size-aware and especially avoid
outer padding on solo widgets, whose tile/card children already own their
internal padding — see the `solo` handling in `BriefingContent`.

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

## Money widget design: finance instruments, not generic alert blocks

Do not render the standalone money widget as a plain red rectangle with only
the raw balance. That reads like a generic error tile, not a finance widget.
The useful convention from actual budgeting apps is "available/safe money +
context label", with warning color used as a status accent rather than the
entire surface:

- YNAB's mobile widget centers favorite budget categories and their balances:
  quick access to "category balances you use a lot" and category-specific
  available money.
- Rocket Money's widgets are explicitly framed around "Safe to Spend",
  upcoming bills, recent transactions, and spending. The primary small
  surface is "how much you can safely spend", not a raw account warning.
- PocketGuard's core product language is "safe-to-spend" / "what's in your
  pocket", again making the disposable amount the headline and the state the
  supporting context.

For LifeOps, the money preset should therefore feel like a compact budget
instrument: neutral/dark card, prominent amount, a short state label such as
`OVER`, `LOW`, or `LEFT`, and red/yellow/green as an accent/status signal.
Negative amounts should format conventionally (`-$160`, not `$-160`). Keep
the combined-widget money tile simpler, but avoid styling the solo money
widget as a full-bleed danger slab unless the product decision changes.

References checked 2026-07-14:
[YNAB widget guide](https://support.ynab.com/en_us/ynab-widget-for-mobile-a-guide-HJPEEQYR9),
[YNAB widgets announcement](https://www.ynab.com/blog/widgets-for-ynab-on-ios),
[Rocket Money widgets](https://help.rocketmoney.com/en/articles/9217610-rocket-money-widgets),
[PocketGuard](https://pocketguard.com/).

## Information density: reduce items per row before shrinking text

When a widget needs to show more datapoints than comfortably fit at normal
size, the fix is **fewer items per row, not smaller text**. This app hit
that exact mistake in `ComboGridContent`'s money/social/coursework row: all
three were crammed across one row (each ~1/3 of the left half's width,
~47dp), which forced their font down to 14sp/6sp/6sp — well below
`SoloStatCard`'s own 22sp/8sp/9sp defaults every solo widget uses at an
equal-or-smaller footprint. Confirmed 2026-07-15 ("the columns are really
skinny... it should be LEGIBLE") and fixed by capping stat tiles at 2 per
row, giving a 3rd its own full-width row, and switching back to
`SoloStatCard`'s plain defaults now that each tile has a real column's
worth of width instead of a forced third.

Real production widgets consistently make this same trade under space
pressure:

- Android's own smallest app-widget footprint shows exactly **one** metric;
  iOS's smallest shows **two** — neither platform tries to cram three
  metrics into a shrunk small size.
- Shopify's Android widget team hit a hard technical ceiling
  (`Binder` transaction buffer limit under Android 12's dynamic layouts) and
  responded by **removing rows**, not shrinking every row's text to fit more
  in the same space.
- Shopify's iOS widget team built a tiered hierarchy explicitly keyed to
  "how many metrics we are receiving" — small widgets show fewer metrics
  with minimal supporting info, medium/large progressively reveal more —
  rather than one fixed metric count rendered smaller or larger to fit
  whatever size was placed.

If a design calls for more datapoints than fit at a normal, legible size,
either the widget's declared size needs to grow (see the sizing-formula
tiers below — `combo_widget_info.xml`'s `minHeight` moved from the n=2 to
the n=3 tier for exactly this reason) or the datapoint count needs to drop,
not the font size.

References checked 2026-07-15:
[App Widget Design Guidelines — Android Developers](https://developer.android.com/design/ui/mobile/guides/widgets/style),
[Widget Layouts — Android Developers](https://developer.android.com/design/ui/mobile/guides/widgets/layouts),
[Lessons From Building Android Widgets — Shopify Engineering](https://shopify.engineering/lessons-building-android-widgets),
[Lessons From Building iOS Widgets — Shopify Engineering](https://shopify.engineering/lessons-building-ios-widgets).

The official minWidth/minHeight formula for a widget's default placed size
is `70 × n − 30` dp per grid cell (n=1→40dp, n=2→110dp, n=3→180dp) — this
already matches the values in our `*_widget_info.xml` files; if you're
choosing a new default size for a preset, use this formula rather than
guessing.

**Critical: these thresholds are a hard ceiling, not just a starting
point.** The formula isn't merely a *recommendation* — it's how the launcher
actually computes cell count from your declared dp value. Declare anything
at or above the *next* n's threshold and the launcher rounds UP to that many
cells, in both width and height independently. Confirmed live-device
(2026-07-14): `money_widget_info.xml` and `social_widget_info.xml` were both
set to `120dp` (copied from `gym_widget_info.xml` without rechecking against
the formula) — `120dp` is past the `n=2` threshold (`110dp`), so the widget
picker showed them as **"2x2"**, not "1x1", despite `targetCellWidth`/
`targetCellHeight` both declaring `1` (which the launcher doesn't honor
anyway, per the picker-footprint note above). Both were corrected to
`100dp` (a 10dp margin under `110dp`). If a preset genuinely needs more
room than a formula tier gives, you cannot "round up a little" and still
get that tier's cell count — you either fit under the next threshold or you
accept the next cell count.

**Exception where exceeding the tier IS worth it**: `gym_widget_info.xml`
stays at `120dp` (i.e., accepts 2x2, not a true 1x1) because the gym ring is
a fixed-pixel bitmap (see `GymRingIndicator`'s `SOLO_GYM_RING_SCALE`) that
clips instead of reflowing, unlike text which wraps/truncates gracefully.
That 120dp value came from live-device trial and error (70→96→112→120dp,
confirmed 2026-07-14) weighing "fits cleanly in 1 cell" against "doesn't
clip" and choosing not-clipping. Money and Social's content (text only) had
no such tradeoff to make, so there was no reason to exceed the ceiling for
them — that was simply a copy-paste mistake, not a deliberate choice.

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
