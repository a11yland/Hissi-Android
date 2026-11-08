# Accessibility

Hissi shows whether the elevators at Berlin and Brandenburg stations are
working. It is built for people who depend on those elevators: wheelchair
users, people with walkers, parents with strollers, travellers with heavy
luggage. Accessibility is the purpose of the product, not an add-on.

## Accessibility priorities

**Target state:** WCAG 2.2 Level AA is the required minimum for every change.
Level AAA is a bonus goal: meet AAA success criteria wherever feasible (for
example enhanced contrast of 7:1 for text, 1.4.6; target size of at least 44 by
44 dp, 2.5.5; no timing, 2.2.3; visible focus not obscured at all, 2.4.12), but
missing AAA alone does not block a release. Conformance has not been formally
audited yet; the Known barriers section lists where the project falls short of
this target.

What the code already does:

- **Status is never colour alone.** Every status has its own shape (check
  circle, warning triangle, question mark; `core/.../StatusIcon.kt`,
  `ui/StatusUi.kt`) and a status word ("In Betrieb", "Außer Betrieb", "Status
  unbekannt"). An unknown status is never shown as working
  (`core/.../ElevatorSummary.kt`), and stale data gets its own warning
  ("Status möglicherweise veraltet").
- **TalkBack.** Elevator rows are one element each, with the status spoken
  first (`ui/ElevatorRow.kt`). Station headers spell out the networks ("U-Bahn,
  S-Bahn, …") instead of the badge letters and are marked as headings
  (`ui/SearchResultsContent.kt`). Screen headlines, region headers and the
  about sheet sections are headings. Decorative icons have
  `contentDescription = null`; icon buttons (refresh, theme, alerts, sort,
  clear/close search, back) have labels. Label/value pairs on the detail
  screen and notice cards are merged into one element.
- **No drag-only interaction for assistive technology.** Favorites can be
  reordered by long-press drag, and the rows also offer "Nach oben
  bewegen"/"Nach unten bewegen" as custom accessibility actions; the new
  position is announced ("… an Position 2 von 5") through a polite live region
  (`ui/ContentScreen.kt`, `FavoritesContent`). Pull-to-refresh has a refresh
  button as an alternative.
- **Live regions** announce the refresh error (`RefreshError`), the search
  error and the "Status wird aktualisiert …" banner for the not-yet-refreshed
  search results (`ui/SearchResultsContent.kt`).
- **Voice Access.** The map and Street View snippets are one button each whose
  accessible name contains the visible capsule text ("In Karten öffnen",
  "Street View öffnen"), so speaking what is on screen works
  (`ui/MapSnippet.kt`, `ui/StreetViewSnippet.kt`).
- **Font scaling.** All text uses `sp` through the Material 3 type scale, in
  the app and in the widgets. Nearby rows allow two lines for name and status;
  the about sheet and the detail screen scroll.
- **Target size.** Material icon buttons use the 48 dp minimum. The pinned
  action above the search field, the nearby opt-in button and the recent-search
  chips (including their remove button) are 44 dp tall on purpose (2.5.5,
  `ui/ContentScreen.kt` `PinnedAction`, `ui/NearbySection.kt`,
  `ui/RecentsContent.kt`).
- **Contrast.** The "Creme-Lila" palette lives in `core/.../Palette.kt` as hex
  values; `PaletteTest` checks it on every CI run: the tint (buttons, links,
  star) and the network badges at 7:1, status and secondary text at 4.5:1,
  status symbols at 3:1 against every ground. Computed ratios (screen
  background / row surface / high surface):

  | Pair | Light | Dark |
  |---|---|---|
  | Primary text (`onSurface`) | 17.3 / 18.5 / 15.7 | 17.3 / 15.1 / 13.2 |
  | Tint as text (primary) | 8.5 / 9.1 / 7.7 | 11.2 / 9.8 / 8.6 |
  | Secondary text (`onSurfaceVariant`, `outline`) | 7.0 / 7.5 / 6.4 | 9.0 / 7.9 / 6.9 |
  | Status text "working" | 5.9 / 6.3 / 5.3 | 11.5 / 10.1 / 8.8 |
  | Status text "unknown" | 6.2 / 6.6 / 5.6 | 11.4 / 10.0 / 8.7 |
  | Status text "broken" | 6.2 / 6.6 / 5.6 | 7.1 / 6.2 / 5.4 |
  | Status symbols (non-text) | 3.2 to 4.7 | 5.4 to 11.5 |
  | Error text (Material default, not in the palette) | 5.9 / 6.3 / 5.4 | 11.2 / 9.8 / 8.5 |
  | Label on filled button | 8.5 | 9.1 |
  | Tint on its tonal fill | about 7.0 | 8.9 |

  White on the network badges: U-Bahn 9.4, S-Bahn 7.4, Regional 7.4, access
  7.5. Separators (1.2 to 1.5) are decorative only.
- **Dark theme.** Light and dark palettes follow the system setting, with a
  session-only in-app toggle (`MainActivity.kt`). Colour-picking code reads
  the resolved theme (`LocalDarkTheme`), so the WCAG pairs never mix.
- **Motion.** The only animations are short fades and a height reveal of the
  map and Street View snippets. There is no app-specific reduced-motion
  handling; they are standard Compose animations.
- **No time limits.** Status refreshes on resume and every 30 minutes; nothing
  expires or requires a response within a time limit.
- **Orientation.** Not locked; the detail screen has its own landscape
  layout.
- **Language.** German (source) and English string resources, per-app
  language on Android 13+ (`res/xml/locales_config.xml`).
- **Widgets.** Both Glance widgets (`widget/HissiWidget.kt`,
  `widget/NearbyWidget.kt`) give every state one spoken sentence via
  `contentDescription`, never show a false all-clear, and use the palette's
  status text colours.
- **Permissions in context.** Location and notification prompts only appear
  after a tap on the matching control, never on launch.

Not present: a Wear OS module (see #5), Compose UI tests, Accessibility Test
Framework checks, Android lint in CI.

## Supported environments

- Android 8.0 (API 26) to Android 16 (target API 36), phones in portrait and
  landscape.
- Home-screen widgets (Glance) and disruption-alert notifications.
- Light and dark theme; German and English.
- Assistive technology the code is designed for: TalkBack, Switch Access,
  Voice Access, system font size and display size. None of them is covered by
  automated tests yet; checks are manual.
- Not supported: Wear OS (#5), tablets/foldables with a dedicated layout,
  hardware-keyboard navigation as a tested path.

## Known barriers

All open items are tracked as [issues with the `a11y` label](https://github.com/a11yland/Hissi-Android/issues?q=is%3Aissue+is%3Aopen+label%3Aa11y).

Unverified items are marked as such; please report what you find.

**Screen reader and semantics**

- The app bar logo button that opens "Über Hissi" has no accessible name: the
  `IconButton` wraps `AppIcon`, whose image has `contentDescription = null`.
  The string `about_open` exists but is unused (`ui/ContentScreen.kt`
  `navigationIcon`, `ui/AboutSheet.kt` `AppIcon`). 4.1.2, 1.1.1.
- The favorite star applies `clearAndSetSemantics` to the `IconButton`, which
  also clears the click action and Button role the button sets itself. The
  favorite toggle is therefore likely not exposed as an actionable button to
  TalkBack, Switch Access and Voice Access (`ui/ElevatorRow.kt`
  `FavoriteStar`). Needs verification with TalkBack. 4.1.2.
- The disruption-alerts `Switch` has no label of its own; the title and hint
  next to it are a separate element (`ui/AlertsSection.kt`). 4.1.2, 1.3.1.
- The sort menu shows the active order only through a check icon with no
  description; the selected state is not exposed (`ui/ContentScreen.kt`
  `SortMenu`). 1.3.1, 4.1.2.
- The search field has only a placeholder ("Station finden"), no persistent
  label (`ui/ContentScreen.kt` `OutlinedTextField`). 3.3.2, 2.5.3.
- "Einstellungen öffnen" links (nearby denied, alerts hints) and the about
  sheet link rows are clickable text/rows without a role (`ui/NearbySection.kt`,
  `ui/AlertsSection.kt` `SettingsHint`, `ui/AboutSheet.kt` `LinkRow`). The link
  rows do not say that they open the browser. 4.1.2, 2.4.4.
- While Street View has no coverage, its snippet is kept at 1 dp height, but
  its overlay still carries a label, Button role and click action, so TalkBack
  can focus an invisible "Straßenansicht …" button (`ui/StreetViewSnippet.kt`,
  `ui/ElevatorDetailScreen.kt`). Without the Google Maps app the tap does
  nothing and gives no feedback. 1.3.2, 4.1.2.
- The detail screen has no heading (the station name is the app bar title)
  and no announced screen change when it opens or closes; focus placement
  after the switch is unverified (`ui/ElevatorDetailScreen.kt`,
  `ui/ContentScreen.kt`). 1.3.1, 2.4.6, 2.4.3.
- The nearby block's "In der Nähe" title is not a heading, and its
  "Standort wird ermittelt …" and "Status wird aktualisiert …" states are not
  live regions, unlike the search banner (`ui/NearbySection.kt`). While the
  favorites refresh, the refresh button is replaced by an unlabelled spinner,
  so focus on it is lost (`ui/ContentScreen.kt`). 4.1.3, 1.3.1, 2.4.3.

**Input and targets**

- Reordering favorites without assistive technology requires a long-press
  drag; there is no single-pointer or keyboard alternative outside the
  TalkBack/Switch Access custom actions (`ui/ContentScreen.kt`
  `FavoritesContent`). 2.5.7, 2.1.1.
- The "Einstellungen öffnen" and battery-settings texts are bare `clickable`
  text of about 20 dp height, below the 24 dp minimum (`ui/NearbySection.kt`,
  `ui/AlertsSection.kt` `SettingsHint`). The about sheet link rows are about
  36 dp (`ui/AboutSheet.kt` `LinkRow`). 2.5.8, 2.5.5.
- Focus indication relies on Material defaults; hardware-keyboard and Switch
  Access navigation have not been tested. 2.4.7, 2.4.11, 2.4.13.

**Text size and layout**

- Recent-search chips have a fixed `height(44.dp)` with `bodyLarge` text, so
  the label clips at large font sizes; `heightIn(min = 44.dp)` would keep the
  minimum (`ui/RecentsContent.kt`). 1.4.4.
- The detail app bar title is `maxLines = 1` without ellipsis; long station
  names are cut at large font sizes (`ui/ElevatorDetailScreen.kt`). 1.4.4,
  1.4.10.
- Widgets use fixed 11 to 14 sp text with `maxLines = 1` for names and status
  lines (`widget/HissiWidget.kt`, `widget/NearbyWidget.kt`). 1.4.4.
- The map snippet is a fixed 160 dp; the location pill and the "In Karten
  öffnen" capsule can collide at large font sizes on narrow phones
  (`ui/MapSnippet.kt`). Unverified. 1.4.4.

**Contrast (AA met, AAA bonus goal)**

- Light status text reaches 5.3 to 6.6:1 and dark "broken" text 5.4 to 7.1:1;
  secondary text on the high surface is 6.4:1 (light) and 6.9:1 (dark). AA is
  met and tested, AAA (7:1) is not (`core/.../Palette.kt`). 1.4.6.
- The error colour is the Material default, not part of the palette and not
  covered by `PaletteTest` (light 5.4 to 6.3:1) (`MainActivity.kt`). 1.4.6.
- Widget text in `GlanceTheme.colors.onSurface`/`onSurfaceVariant` (dynamic
  colour on Android 12+) is drawn on the fixed palette ground; that contrast
  is not checked (`widget/HissiWidget.kt`, `widget/NearbyWidget.kt`). 1.4.3.

**Process**

- No automated accessibility checks: CI runs `./gradlew test
  :app:assembleDebug` only, without Compose UI tests, Accessibility Test
  Framework or Android lint (`.github/workflows/ci.yml`).

Related issues: #4 (OpenStreetMap as the map default) touches the map
snippet; #5 (Wear OS) would need its own accessibility review. Closed #1 (the
search field could not be left) is fixed by the close button and Back
handling in `ui/ContentScreen.kt`.

## Contributor expectations

- Every change that touches UI meets WCAG 2.2 AA; aim for AAA where feasible.
- Check new or changed screens with TalkBack, at the largest font size and
  display size, in light and dark theme, and in landscape. Note in the pull
  request what you tested.
- Give every interactive element an accessible name, role and state. Use
  `contentDescription = null` only for decorative images. When you use
  `clearAndSetSemantics`, re-add `role` and `onClick` if the element is
  actionable.
- Never let colour carry information alone; pair it with shape or text.
- New colours go into `core/.../Palette.kt` with a `PaletteTest` assertion
  (text 4.5:1 minimum, 7:1 preferred; non-text 3:1).
- Text uses the Material type scale (`sp`). Avoid fixed heights and
  `maxLines = 1` on text that can grow; use `heightIn(min = …)`.
- Interactive targets are at least 24 dp (AA), preferably 44 dp or more.
- Gestures (drag, long press, pull) need a single-tap or accessibility-action
  alternative.
- Status changes that appear without focus moving (errors, loading, results)
  are announced through a live region.
- Keep `ACCESSIBILITY.md` current: add new gaps to Known barriers and remove
  fixed ones in the same pull request.

## Reporting process

Report barriers at https://github.com/a11yland/Hissi-Android/issues.
**Every accessibility issue must have the `a11y` label** so it can be found
and triaged. If you cannot set labels yourself, start the title with "a11y:"
and a maintainer will add the label.

Helpful details: device and Android version, Hissi version (shown in "Über
Hissi"), assistive technology and its version (for example TalkBack, Switch
Access, Voice Access), font and display size, theme, the screen or widget,
steps to reproduce, what you expected and what happened. The WCAG success
criterion is welcome but not required.

No GitHub account? Write to the e-mail address in [PRIVACY.md](PRIVACY.md)
(Kobe24LAL@gmx.de); a maintainer will file the issue.

## Ownership

The a11yland maintainers (Kobe) own accessibility for this repository: they
triage `a11y` issues, review UI pull requests against this document and decide
release blockers. An AA barrier in a core flow (favorites, search, status,
detail, widgets, alerts) is treated as a bug with priority over new features.

## Maintenance

- Review this document with every pull request that changes UI, and before
  every release.
- Re-check the Known barriers list when an `a11y` issue is opened or closed.
- Revisit the target when WCAG or the Android accessibility guidance changes.
- Behaviour shared with the iOS app (palette, status vocabulary, spoken
  labels) is kept in step with
  [Hissi-iOS](https://github.com/a11yland/Hissi-iOS).

Last reviewed: 2026-11-08.
