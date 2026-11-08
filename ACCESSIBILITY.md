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
  `contentDescription = null`; icon buttons (about, refresh, theme, alerts,
  sort, favorite star, clear/close search, back) have labels and keep their
  Button role. The search field has a persistent label, the alerts row is one
  switch with its title and hint as the name, the sort menu exposes the active
  order as selected, and the about sheet link rows say that they open the
  browser. Label/value pairs on the detail screen and notice cards are merged
  into one element.
- **Screen structure.** The detail screen announces itself through a pane
  title and carries the station name as a heading; the nearby block's title
  is a heading (`ui/ElevatorDetailScreen.kt`, `ui/NearbySection.kt`).
- **No drag-only interaction for assistive technology.** Favorites can be
  reordered by long-press drag, and the rows also offer "Nach oben
  bewegen"/"Nach unten bewegen" as custom accessibility actions; the new
  position is announced ("… an Position 2 von 5") through a polite live region
  (`ui/ContentScreen.kt`, `FavoritesContent`). Pull-to-refresh has a refresh
  button as an alternative.
- **Live regions** announce the refresh error (`RefreshError`), the search
  error, the "Status wird aktualisiert …" banner for the not-yet-refreshed
  search results (`ui/SearchResultsContent.kt`) and the nearby locating and
  its result ("3 Stationen in der Nähe gefunden", `ui/NearbySection.kt`).
  While the favorites refresh, the refresh button stays in place, disabled,
  with the state "Wird aktualisiert", so TalkBack focus is not lost. A manual
  refresh always answers ("Aktualisiert, keine Änderung" or the change); a
  routine poll speaks only when a status really changed, in the words of the
  disruption notification (`DisruptionAlert` rules, `ui/MonitorViewModel.kt`).
- **Back from the detail screen** the favorites list keeps its scroll
  position, and the opened row gets the focus back for TalkBack and keyboard
  users (`ui/ContentScreen.kt`).
- **Voice Access.** The map and Street View snippets are one button each whose
  accessible name contains the visible capsule text ("In Karten öffnen",
  "Street View öffnen"), so speaking what is on screen works
  (`ui/MapSnippet.kt`, `ui/StreetViewSnippet.kt`). The Street View button
  only exists once a panorama is confirmed; without the Google Maps app it
  opens the panorama in the browser.
- **Font scaling.** All text uses `sp` through the Material 3 type scale, in
  the app and in the widgets. Nearby rows and widget texts allow two lines for
  names and status; recent-search chips and the map snippet have minimum, not
  fixed, heights; the detail screen's station name wraps as a headline below
  the app bar; the about sheet and the detail screen scroll.
- **Target size.** Material icon buttons and text buttons use the 48 dp
  minimum, including the "Einstellungen öffnen" and battery-settings actions;
  the about sheet link rows are at least 48 dp tall. The pinned action above
  the search field, the nearby opt-in button and the recent-search chips
  (including their remove button) are at least 44 dp tall on purpose (2.5.5,
  `ui/ContentScreen.kt` `PinnedAction`, `ui/NearbySection.kt`,
  `ui/RecentsContent.kt`).
- **Contrast.** The "Creme-Lila" palette lives in `core/.../Palette.kt` as hex
  values; `PaletteTest` checks it on every CI run: the tint (buttons, links,
  star), the network badges, the status, secondary, error and primary text at
  7:1 (AAA), status symbols at 3:1 against every ground. Computed ratios (screen
  background / row surface / high surface):

  | Pair | Light | Dark |
  |---|---|---|
  | Primary text (`onSurface`) | 17.3 / 18.5 / 15.7 | 17.3 / 15.1 / 13.2 |
  | Tint as text (primary) | 8.5 / 9.1 / 7.7 | 11.2 / 9.8 / 8.6 |
  | Secondary text (`onSurfaceVariant`, `outline`) | 7.8 / 8.3 / 7.1 | 9.3 / 8.1 / 7.1 |
  | Status text "working" | 7.8 / 8.4 / 7.1 | 11.5 / 10.1 / 8.8 |
  | Status text "unknown" | 7.8 / 8.3 / 7.1 | 11.4 / 10.0 / 8.7 |
  | Status text "broken" | 7.8 / 8.3 / 7.1 | 9.2 / 8.1 / 7.1 |
  | Error text (palette, = "broken") | 7.8 / 8.3 / 7.1 | 9.2 / 8.1 / 7.1 |
  | Status symbols (non-text) | 3.2 to 4.7 | 5.4 to 11.5 |
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
  `contentDescription`, never show a false all-clear, and draw only palette
  colours (`widget/WidgetColors.kt`), not the wallpaper-derived dynamic
  colours, so every pair on the tile is covered by `PaletteTest`.
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

- Back from a favorite's detail screen, the opened row is meant to get the
  focus back (`ui/ContentScreen.kt` `FavoritesContent`). Whether TalkBack
  follows that focus in touch mode is unverified on a device. Back from a
  search result, focus is not restored. 2.4.3.

**Input and targets**

- Reordering favorites without assistive technology requires a long-press
  drag; there is no single-pointer or keyboard alternative outside the
  TalkBack/Switch Access custom actions (`ui/ContentScreen.kt`
  `FavoritesContent`). 2.5.7, 2.1.1.
- Focus indication relies on Material defaults; hardware-keyboard and Switch
  Access navigation have not been tested. 2.4.7, 2.4.11, 2.4.13.

**Text size and layout**

- The fixes for large font and display sizes (chips, detail headline, map
  capsules, two-line widget texts) are not yet verified on a device at the
  largest settings in portrait and landscape. 1.4.4, 1.4.10.

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
