# Testprotokoll: Gerätetest am Lapdock

Stand: 12.10.2026. Für jede neue Version (eigene Änderungen oder Upstream-Übernahme) einmal durchgehen. Ergebnisse
unten eintragen. Was nicht geprüft wurde, als „offen“ markieren, nicht als bestanden.

## 0. Vorbereitung

- [ ] APK installiert (Version in Einstellungen → ganz unten bzw. `adb shell dumpsys package com.selfframed.moonlight | findstr versionName`).
- [ ] Mit Sunshine gekoppelt; offizielles Moonlight bleibt installiert.
- [ ] In den Moonlight-Einstellungen die Leistungsanzeige einschalten (für Teil C).

## A. Testbild (ohne Stream)

Einstellungen → E-Ink-Bild → Presets verwalten → Testbild öffnen
(oder `adb shell am start -n com.selfframed.moonlight/com.limelight.selfframed.grading.GradingTestPatternActivity`).

- [ ] A1 Testbild erscheint vollflächig, die 16 Stufen sind beschriftet.
- [ ] A2 Taste 3: Einblendung „3 · Papierweiß (Text)“, helle Stufen werden weiß, dunkle schwarz.
- [ ] A3 Taste 6: Bild invertiert. Taste 0: Original.
- [ ] A4 Taste F mehrfach: Vorher/Nachher wechselt sofort.
- [ ] A5 Taste P: Bedienfeld rechts, Kurve ausgewählt. Pfeiltasten verschieben den Punkt, das Bild ändert sich live.
- [ ] A6 Punkt einfügen (N), löschen (Entf), Kanal wechseln (C), Kanal zurücksetzen (R).
- [ ] A7 Regler Gamma/Helligkeit/Kontrast/Sättigung, Graustufen-Mischung an: Farbpaare unten werden unterscheidbar,
      wenn die Gewichte geändert werden.
- [ ] A8 Esc schließt das Bedienfeld, zweites Esc das Testbild. Erneut öffnen: Änderungen sind noch da.

## B. Stream: Funktion

- [ ] B1 Stream starten: Einblendung mit dem aktiven Preset (bzw. Hinweis auf Strg+Alt+Shift+P bei Neutral).
- [ ] B2 Strg+Alt+Shift+1 … 9, 0: Wechsel sofort, auch auf ruhigem Desktop. Kein Buchstabe, keine Ziffer kommt am Host an
      (z. B. in einem offenen Texteditor prüfen).
- [ ] B3 Strg+Alt+Shift gehalten, F mehrfach tippen: Vorher/Nachher wechselt bei jedem Tippen.
- [ ] B4 Strg+Alt+Shift+→ / ←: Preset weiter/zurück.
- [ ] B5 Strg+Alt+Shift+P: Bedienfeld, Mauszeiger frei; Kurve per Maus ziehen; Tippen im Bedienfeld kommt nicht am Host an.
- [ ] B6 Esc schließt; danach gehen Maus und Tastatur wieder an den Host.
- [ ] B7 Upstream-Kürzel funktionieren unverändert: Strg+Alt+Shift+Z, C, Q.
- [ ] B8 Stream beenden und neu starten: dasselbe Preset ist aktiv.
- [ ] B9 Einstellungen → Bildanpassung im Stream aus: Stream läuft wie im offiziellen Moonlight (keine Einblendung, keine
      Kürzel-Wirkung, Strg+Alt+Shift+1 geht an den Host).

## C. Stream: Leistung und Latenz

Gleiche Szene (z. B. Maus kreisen lassen, Video im Browser), 1600×1200, gleiche Bitrate:

| Messung | offizielles Moonlight | Moonlight E-Ink (Neutral) | Moonlight E-Ink (Preset 3) |
|---|---|---|---|
| Dekodier-Latenz (Leistungsanzeige) | | | |
| Bildstufe ms/frame | – | | |
| übersprungene Bilder | – | | |
| subjektives Eingabegefühl | | | |
| Akku: Prozent pro 30 min Stream | | | |

- [ ] C1 Bildstufe unter ~2 ms/frame.
- [ ] C2 Kein spürbarer Unterschied beim Eingabegefühl zum offiziellen Moonlight.
- [ ] C3 Alle Frame-Pacing-Modi einmal probiert (Einstellungen → Frame-Pacing): Bild läuft, keine Ruckler.

## D. Robustheit

- [ ] D1 Während des Streams Telefon kurz sperren und entsperren bzw. Moonlight verlassen und zurückkehren: Stream endet
      oder läuft weiter wie bei Upstream, kein Absturz.
- [ ] D2 Netz kurz trennen (WLAN aus/an): Verhalten wie bei Upstream.
- [ ] D3 Zehn Mal schnell zwischen Presets wechseln, während ein Video läuft: kein Hänger.
- [ ] D4 HDR in den Einstellungen an (falls der Host HDR kann): Einblendung „HDR-Stream: Bildanpassung ist aus“, Stream läuft.

## Ergebnisse

| Datum | Version | Gerät/ROM | A | B | C | D | Notizen |
|---|---|---|---|---|---|---|---|
| – | 12.2-sf.1 | Moto G100 | offen | offen | offen | offen | noch nicht auf dem Gerät getestet |
