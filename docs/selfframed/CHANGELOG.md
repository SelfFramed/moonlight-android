# Changelog: Moonlight E-Ink (SelfFramed-Fork)

Format: neueste Version oben. Upstream-Änderungen stehen im Upstream-Repo; hier nur Fork-Änderungen und welche
Upstream-Version übernommen wurde.

## 12.2-sf.6 (14.10.2026)

Rückmeldung Martin (14.10.2026): Die Einrichtung des Mikrofons war zu umständlich (Code aus einer Datei abtippen,
Mikrofon in jeder Anwendung auswählen).

- Koppeln wie Bluetooth: Das erste Strg+Alt+Shift+M koppelt von selbst. Oben steht ein Code, am PC fragt ein Fenster mit
  demselben Code, ein Klick auf „Ja“, fertig. Kein Code zum Abtippen mehr (ECDH mit Festlegung vorab, 6-stelliger
  Vergleichscode).
- Nach der Android-Berechtigung geht es von selbst weiter, ohne zweites M.
- Solange das Mikrofon an ist, ist es am PC das Standard-Mikrofon; danach kommt das vorherige zurück. Anwendungen auf
  „Standard“ brauchen keine Umstellung.
- Kennt der PC das Telefon nicht mehr, koppelt das nächste M neu.
- Einstellungen: „Mikrofon zum PC“ zeigt die Kopplungen und löscht sie auf Wunsch (statt „Mikrofon-Kopplungscode“).
- Protokoll Version 2 (Geräte-ID im Paketkopf); der PC-Empfänger ist aktualisiert und hat die alte Code-Datei gelöscht.

## 12.2-sf.5 (13.10.2026)

Wunsch Martin (13.10.2026): Das Mikrofon geht in Moonlight nicht. Das ist dort nicht umgesetzt; entschieden wurde Weg A.

- Mikrofon zum PC: Strg+Alt+Shift+M schickt das Telefon-Mikrofon mit Echo-Unterdrückung verschlüsselt an den PC. Dort
  steht es als „Mikrofon (Steam Streaming Microphone)“ allen Anwendungen zur Verfügung. Solange es an ist, zeigt die
  Plakette „Mikro an“ das an. Bei jedem Stream-Start ist es aus.
- Einstellung „Mikrofon-Kopplungscode“ (Ton); fragt beim Eintragen nach dem Mikrofon-Zugriff.
- PC: Empfänger `tools/sf/mic/receiver.py`, startet bei der Anmeldung. Die Wiedergabeseite des Steam-Mikrofons ist
  aktiviert.
- Akku- und Mikrofon-Plakette teilen sich eine Statusspalte oben in der Mitte und überdecken sich nicht.

## 12.2-sf.4 (13.10.2026)

Wunsch Martin (13.10.2026): eine Akkuwarnung, weil im Stream nichts vom Telefon-Akku zu sehen ist.

- Akkuwarnung im Stream, oben in der Mitte und im E-Ink-Stil: bei 15 % ein kurzer Hinweis, ab 7 % eine bleibende Warnung
  mit dickem Rahmen, bis der Akku wirklich lädt; dann kurz „Lädt“. Erkennt auch „angeschlossen, lädt aber nicht“, etwa
  ein schwaches Lapdock.
- Strg+Alt+Shift+B: Akkustand auf Abruf; blendet die bleibende Warnung bis zum nächsten Prozentpunkt aus.
- Einstellungen: Akkuwarnung an/aus, erste Warnung (10/15/20/25 %), bleibende Warnung (3/5/7/10 %).
- Kein zusätzlicher Eingriff in Upstream-Dateien.

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
