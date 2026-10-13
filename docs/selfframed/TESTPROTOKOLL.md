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
- [ ] B5 Strg+Alt+Shift+P: Bedienfeld. Tippen im Bedienfeld kommt nicht am Host an. Danach am Host prüfen: kein
      Strg/Alt/Shift hängt (z. B. in einem Texteditor tippen oder klicken).
- [ ] B6 Nach Strg+Alt+Shift+P und Loslassen: Maus frei, Kurve per Maus ziehen; Esc schließt und die Maus wirkt wieder
      im Stream. Am Host hängt keine Taste.
- [ ] B6b Hinweis beim Stream-Start: bei den ersten 3 Streams sichtbar, danach nicht mehr (außer mit der Einstellung).
- [ ] B6a Eine Taste (z. B. W in einem Spiel) gedrückt halten, Bedienfeld öffnen, Taste loslassen: am Host bleibt sie nicht hängen.
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

## E. Updates

- [ ] E1 Einstellungen → Updates (SelfFramed): „Installiert: <Version>“ stimmt; „Jetzt nach Updates suchen“ meldet
      „aktuell“ (solange kein neueres reguläres Release existiert).
- [ ] E2 Beim nächsten regulären Release: App öffnen → Update-Dialog → Installieren → (einmalig „Aus dieser Quelle
      zulassen“) → Androids Bestätigung → neue Version läuft, Kopplung und Presets sind erhalten.

## F. Ton

- [ ] F1 Einstellungen → Ton (SelfFramed) → „Audio-Puffer“ steht auf „Automatisch (empfohlen)“.
- [ ] F2 Leistungsanzeige an, 10 Minuten Musik oder Video am PC: Die Zeile „Ton: …“ ist sichtbar, „Aussetzer“ bleibt bei
      0 oder steigt nur am Anfang, nichts knackst.
- [ ] F3 Zum Vergleich „Wie Moonlight“: Das alte Verhalten ist wieder da. Danach zurück auf „Automatisch“.
- [ ] F4 Das Ausgabegerät in der Zeile stimmt (Lautsprecher, Kopfhörer, Bluetooth oder USB).
- [ ] F5 Bei einem Video wirkt der Ton nicht spürbar vor oder hinter dem Bild (E-Ink-Verzögerung beachten).
- [ ] F6 (Debug-Build, optional) `adb shell am start -n com.selfframed.moonlight.debug/com.limelight.selfframed.audio.AudioBufferTestActivity`:
      30 s Testton mit 80 ms Pause alle 5 s; „Aussetzer“ 0 bis 1.

## Ergebnisse

| Datum | Version | Gerät/ROM | A | B | C | D | Notizen |
|---|---|---|---|---|---|---|---|
| 12.10.2026 | 12.2-sf.1 (Debug) | Emulator API 36, x86_64, SwiftShader | A1–A4 bestanden (automatisch), A5–A8 siehe unten | – | – | – | `tools/sf/verify-gpu-stage.py`: 7 Presets × 16 Graustufen pixelgenau gegen die Original-JS-Mathematik, maximale Abweichung 0. Alle Wechsel als Neuzeichnen eines stehenden Bildes (statischer Desktop). Einblendung sichtbar. Kein Stream möglich (kein Host im Emulator). |
| 12.10.2026 | 12.2-sf.1 → 12.2-sf.2 (Release, Testserver) | Emulator API 36 | – | – | – | – | Update-Funktion durchgängig: Dialog, einmalige Erlaubnis, Download, Prüfungen, Bestätigung, installiert (versionCode 31502). Play Protect fragte einmal (ohne Scan installiert). Lehre: Pythons HTTP/1.0-Testserver bricht im Emulator große Downloads ab, deshalb ein Keep-Alive-Testserver. |
| 12.10.2026 | 12.2-sf.3 (Debug) | Emulator API 36 | – | – | – | – | F6 bestanden: Die Wiedergabe startet nach dem Vorfüllen, Verzögerung 37–59 ms bei Ziel 60, 0 Aussetzer trotz 80 ms Pause alle 5 s, 2 Pakete ergänzt, Zeile „speaker, 48 kHz, normal path“. Ohne Audiogerät (`-no-audio`) legt Android keinen AudioTrack an; das ist eine Grenze des Emulators. Der Emulator puffert im Audiotreiber zusätzlich, die Aussetzerzahl ist deshalb nicht aufs Telefon übertragbar. |
| 13.10.2026 | 12.2-sf.3 | Moto G100 | – | – | – | – | F2 teilweise: Martin: vorher ein „Lagfestival“, jetzt nach 2 Minuten „flawless“. Offen: längerer Test (F2 mit 10 Minuten), Werte der Zeile „Ton“, F3–F5. |
| 13.10.2026 | 12.2-sf.2 (vermutlich) | Moto G100 | – | – | – | – | Martin: „am Ton hat sich gar nichts geändert“. Erwartet: Der Audio-Puffer kam erst mit 12.2-sf.3, die zu dem Zeitpunkt noch nicht veröffentlicht war. GitHub zählte zwei Downloads von sf.2. Ton-Test (F) mit sf.3 offen. |
| 12.10.2026 | 12.2-sf.1 | Moto G100 | – | B2–B5 bestanden (Kürzel, Bedienfeld) | offen | offen | Martin: Kürzel „wunderbar“. Befunde: Maus im Bedienfeld nicht nutzbar (blieb eingefangen); Start-Hinweis bei jedem Stream störend. Beides in 12.2-sf.2 geändert. |

Die automatische Prüfung (`python tools/sf/verify-gpu-stage.py <serial> <appId> <Ausgabeordner>`) deckt A1–A4 ab und
läuft auch gegen das echte Telefon (per adb). Den Decoder-Pfad (MediaCodec → Bildstufe) kann nur Teil B am Gerät prüfen.
