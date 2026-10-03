# Google Play store listing

Source of truth for the Play Console "Default store listing" (Default – German – de-DE). Edit here first, then paste into the Console — the Console is not versioned.

## App name (30 chars max)

```
Hissi - Aufzugstatus
```

## Short description (80 chars max)

```
Aufzüge in Berlin & Brandenburg im Blick: Live-Status für deine Stationen.
```

## Full description (4000 chars max)

Android-only claims: no watch mentions (Wear OS is not built). Widgets, launcher shortcuts and disruption alerts exist and are listed.

```
Hissi zeigt dir, ob die Aufzüge an deinen Stationen funktionieren – bevor du vor einer defekten Tür stehst.

Die App überwacht Aufzüge an U-Bahn-, S-Bahn- und Regionalbahnhöfen in Berlin und Brandenburg. Die Statusdaten kommen von transit.accessibility.cloud, das die offiziellen Störungsmeldungen der Betreiber bündelt und laufend aktualisiert.

FUNKTIONEN

• Favoriten: Speichere die Aufzüge deiner täglichen Wege und sieh ihren Status auf einen Blick.
• Suche: Finde jede Station nach Name – gruppiert nach Region, Station und Netz (U-Bahn, S-Bahn, Regionalverkehr).
• In der Nähe: Zeigt auf Wunsch die nächstgelegenen Stationen samt Aufzugstatus. Dein Standort bleibt dabei auf dem Gerät.
• Details: Störungsmeldung, Datenstand, Karte und Straßenansicht zu jedem Aufzug.
• Widgets: Der Status deiner Favoriten und die Stationen in der Nähe direkt auf dem Startbildschirm.
• Störungsalarme: Auf Wunsch meldet sich Hissi, wenn ein Favorit ausfällt oder wieder läuft.
• Kein Konto, keine Werbung, kein Tracking.

Ehrlich bei Unsicherheit: Liefert eine Quelle keinen Status, zeigt Hissi „Status unbekannt“ – niemals ein falsches „in Betrieb“.

Gedacht für alle, die auf Aufzüge angewiesen sind – ob mit Rollstuhl, Rollator, Kinderwagen oder schwerem Gepäck.

Datenquelle: transit.accessibility.cloud
```

Note on "Kein Tracking": the app requests Google Maps/Street View snippets, a Google request carrying the station's coordinates (not the user's). If that ever feels overstated, shorten the bullet to "Kein Konto, keine Werbung."

## Visual assets

- App icon 512×512: `play/icon-512.png` — from the design package (the launcher composition, full-bleed; Play masks the corners itself).
- Feature graphic 1024×500: `play/feature-graphic.png` — from the design package (lockup + tagline on the cream ground).
- Phone screenshots: `play/screenshots/` — 4 shots at 1080×1920 (favorites dark, search results, favorites light, welcome sheet), taken on the Pixel_4 emulator (per-app locale de-DE, Creme-Lila palette, 2026-10-03) and pillarboxed from 1080×2280 to the 9:16 ratio Play requires, background matched to the app ground (`#FBF3E4` light, `#120E17` dark).
