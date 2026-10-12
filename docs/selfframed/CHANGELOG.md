# Changelog: Moonlight E-Ink (SelfFramed-Fork)

Format: neueste Version oben. Upstream-Änderungen stehen im Upstream-Repo; hier nur Fork-Änderungen und welche
Upstream-Version übernommen wurde.

## 12.2-sf.1 (in Arbeit, noch nicht auf dem Gerät getestet)

Basis: Upstream **v12.2**.

- Fork-Identität: `com.selfframed.moonlight`, App-Name „Moonlight E-Ink“, läuft neben dem offiziellen Moonlight.
- GPU-Bildstufe zwischen Decoder und Anzeige: Gradationskurven (Gesamt/R/G/B), Gamma, Helligkeit, Kontrast,
  Sättigung, Graustufen-Mischung. Die Mathematik ist identisch mit `game-curves.js` und gegen das Original getestet.
- 9 Presets + Neutral mit E-Ink-Startwerten, automatisch gespeichert.
- Kürzel Strg+Alt+Shift + 1–9 / 0 / F / P / ← →, kontrastreiche Einblendung.
- Bild-Bedienfeld im Stream mit Kurveneditor (Tastatur und Maus).
- Testbild, Export/Import (auch Userscript-Zustand), Startwerte wiederherstellen.
- Einstellungen: Hauptschalter (aus = Upstream-Verhalten), Einblendung, Presets verwalten.
- Leistungsanzeige: Zeile „Bildstufe“.
- CI (GitHub Actions), wöchentlicher Upstream-Wächter, Sync-Skript, Hook-Prüfung, Runbooks.
- Nach unabhängigem Review: Das Bedienfeld fasst den Maus-Einfang nicht mehr an (verhinderte hängende Modifikatoren am
  Host), verbraucht nur eigene Tasten, lässt Gamepads durch. Die GPU-Stufe verbindet sich erst zuletzt mit dem Fenster
  (sicherer Rückfall nach Zeitüberschreitung), wählt exakt 8-Bit, blockiert beim Schließen nicht, baut das Fenster nach
  Fehlern neu auf und meldet Ausfälle sichtbar. Testbild nach Bildschirm aus/an wieder sichtbar, Preset-Verwaltung
  liest beim Zurückkehren neu.
