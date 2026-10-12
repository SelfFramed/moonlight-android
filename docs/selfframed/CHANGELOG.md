# Changelog: Moonlight E-Ink (SelfFramed-Fork)

Format: neueste Version oben. Upstream-Änderungen stehen im Upstream-Repo; hier nur Fork-Änderungen und welche
Upstream-Version übernommen wurde.

## 12.2-sf.3 (12.10.2026)

Rückmeldung Martin (12.10.2026): Der Ton „laggt“, im offiziellen Moonlight wie im Fork.

- Eigener Audio-Puffer statt Upstreams 10 ms. Die Wiedergabe startet mit 60 ms Vorrat, und der Puffer wächst nach
  Aussetzern von selbst (bis 250 ms) und merkt sich den Wert. Gangunterschiede zwischen den Uhren von PC und Telefon
  werden mit kurzen Überblendungen ausgeglichen statt mit harten Schnitten. Der Decoder-Thread läuft mit Audio-Priorität.
- Neue Einstellung „Audio-Puffer“ (Kategorie „Ton (SelfFramed)“): Automatisch (Standard), 50, 100 oder 200 ms, oder „Wie
  Moonlight“.
- Leistungsanzeige: neue Zeile „Ton: …“ mit Verzögerung, Aussetzern, Ausgleich und Ausgabegerät.
- Ursachenanalyse: Host (UR44, 48 kHz, gleichmäßiger 10-ms-Takt) und WLAN (RTT im Median 2,7 ms) sind sauber. Upstreams
  Renderer verträgt aber keinen kurzen Hänger des Telefons, keine WLAN-Spitze und keine Uhrendrift; Simulation und
  Zahlen in `ARCHITEKTUR.md` §5b.

## 12.2-sf.2 (12.10.2026)

Erste Rückmeldung vom Gerät (Martin, 12.10.2026: Kürzel und Bedienfeld funktionieren):

- Maus im Bedienfeld: Nach Strg+Alt+Shift+P ist die Maus frei, sobald die Tasten losgelassen sind (wie Strg+Alt+Shift+Z),
  und wird beim Schließen wieder eingefangen.
- Hinweis beim Stream-Start nur noch bei den ersten 3 Streams; neue Einstellung „Hinweis beim Stream-Start immer
  zeigen“.

## 12.2-sf.1 (12.10.2026, Vorabversion: noch nicht am Gerät getestet)

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
- Eingebaute Update-Funktion: Die App findet neue Releases auf GitHub und installiert sie nach Bestätigung (Prüfsumme,
  Version und Signatur werden geprüft); keine Extra-App. Releases baut und signiert Claude auf Martins PC
  (`tools/sf/release-local.ps1`), der Schlüssel verlässt den PC nicht.
- Nach unabhängigem Review: Das Bedienfeld fasst den Maus-Einfang nicht mehr an (verhinderte hängende Modifikatoren am
  Host), verbraucht nur eigene Tasten, lässt Gamepads durch. Die GPU-Stufe verbindet sich erst zuletzt mit dem Fenster
  (sicherer Rückfall nach Zeitüberschreitung), wählt exakt 8-Bit, blockiert beim Schließen nicht, baut das Fenster nach
  Fehlern neu auf und meldet Ausfälle sichtbar. Testbild nach Bildschirm aus/an wieder sichtbar, Preset-Verwaltung
  liest beim Zurückkehren neu.
