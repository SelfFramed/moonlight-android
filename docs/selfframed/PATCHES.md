# Patch-Ledger: Eingriffe in Upstream-Dateien

Stand: 12.10.2026, Basis Upstream v12.2. Jede Zeile in Upstream-Dateien, die der Fork ändert, trägt den Marker
`[SelfFramed]`. Diese Liste beschreibt die **Absicht** jedes Eingriffs, damit er nach einem Upstream-Merge auch dann
sinngemäß wiederhergestellt werden kann, wenn sich der Code drumherum geändert hat.

Prüfung: `bash tools/sf/check-hooks.sh` (zählt die Marker-Zeilen gegen `tools/sf/hooks.txt`).
Wird hier etwas geändert, auch `hooks.txt` anpassen.

Grundregeln:

- Neuer Code kommt nach `app/src/main/java/com/limelight/selfframed/**`, Ressourcen heißen `sf_*`.
- In Upstream-Dateien nur kurze Einhängepunkte; voll qualifizierte Klassennamen statt neuer Imports, damit
  Import-Blöcke konfliktfrei bleiben.
- Upstream-Verhalten bleibt unverändert, wenn die Bildstufe in den Einstellungen aus ist (`sfGrading == null`) und der
  Audio-Puffer auf „Wie Moonlight“ steht (dann läuft Upstreams `AndroidAudioRenderer`).

| # | Datei | Stelle | Absicht | Bei Konflikt |
|---|---|---|---|---|
| 1 | `app/build.gradle` | letzte Zeile: `apply from: 'selfframed.gradle'` | Fork-Identität, Versionierung, Signierung und Testabhängigkeiten liegen in `app/selfframed.gradle` | Zeile ans Ende setzen. Nie Upstream-Zeilen in `build.gradle` ändern. |
| 2 | `.gitignore` | Block am Ende | `sf-signing.properties`, `*.jks`, `*.keystore` nie einchecken | Block ans Ende hängen |
| 3 | `.gitattributes` | ganze Datei (neu, Upstream hat keine) | `*.sh` immer mit LF | – |
| 4 | `MediaCodecDecoderRenderer.java` | Felder `sfOutputSurface`, `sfOnCleanup` | Alternative Decoder-Ausgabe und Freigabe-Hook | neben die übrigen Felder |
| 5 | `MediaCodecDecoderRenderer.java` | Methode `sfSetOutputSurface(...)` nach `setRenderTarget` | Game setzt die Ausgabe der Bildstufe | Methode erhalten |
| 6 | `MediaCodecDecoderRenderer.java` | `configureAndStartDecoder`: `configure(format, sfOutputSurface != null ? … : renderTarget.getSurface(), …)` | Decoder rendert in die SurfaceTexture der Bildstufe. **Kritisch:** Ruft Upstream `configure` künftig an anderer Stelle auf oder nutzt `setOutputSurface`, muss jede Stelle dieselbe Auswahl treffen. | alle `configure`/`setOutputSurface`-Aufrufe prüfen |
| 7 | `MediaCodecDecoderRenderer.java` | `cleanup()`: nach `videoDecoder.release()` → `sfOnCleanup.run()` | Bildstufe erst freigeben, wenn der Decoder ihre Surface losgelassen hat | nach jeder Decoder-Freigabe |
| 8 | `Game.java` | Feld `sfGrading` | Controller der Bildstufe | – |
| 9 | `Game.java` | Ende `onCreate`, vor `streamView.getHolder().addCallback(this)` | Controller erzeugen; braucht `willStreamHdr` (lokal in `onCreate`), den FrameLayout-Elternteil der StreamView und `grabbedInput`/`setInputGrabState` (StreamHost) | nach der HDR-Entscheidung und vor dem Surface-Callback |
| 10 | `Game.java` | `surfaceChanged`, vor `decoderRenderer.setRenderTarget(holder)` | Bildstufe starten, Decoder umleiten | muss vor `conn.start` laufen |
| 11 | `Game.java` | `surfaceDestroyed`, am Anfang | GL-Fenster zerstören, bevor die Surface verschwindet (synchron) | vor jedem Code, der die Surface freigibt |
| 12 | `Game.java` | `handleSpecialKeys`: Zweig `specialKeyCode == androidKeyCode` | Gleiches Bild-Kürzel erneut drücken wirkt erneut (F = Vorher/Nachher) | – |
| 13 | `Game.java` | `handleSpecialKeys`: Zweig „warten auf Modifikatoren“ | weitere Bild-Kürzel, solange Strg+Alt+Shift gehalten werden; eine anstehende Upstream-Kombination (Z/Q/C) behält ihren `specialKeyCode` | vor `return down;` |
| 14 | `Game.java` | `handleSpecialKeys`: `default` im Kombinations-`switch` | Bild-Kürzel erkennen und sofort ausführen; Rest der Kombination wie Upstream verschlucken | Upstream-Kürzel (Z, Q, C) haben Vorrang |
| 15 | `Game.java` | Anfang `handleKeyDown` | offenes Bedienfeld bekommt Tasten zuerst | ganz oben, vor allem anderen |
| 16 | `Game.java` | Anfang `handleKeyUp` | dito für Key-Up | ganz oben |
| 17 | `Game.java` | `onPerfUpdate` | Zeilen „Bildstufe: … ms/Bild“ und „Ton: …“ an die Leistungsanzeige hängen | – |
| 18 | `Game.java` | Ende `onDestroy` | Presets speichern, Stufe freigeben (falls `cleanup()` nicht lief) | – |
| 19 | `res/xml/preferences.xml` | erste `PreferenceCategory` `sf_category_grading`, danach `sf_category_audio` und `sf_category_update` | Einstellungen: Hauptschalter, Einblendung, Akkuwarnung, Presets verwalten, Kürzel-Übersicht; Audio-Puffer; Update-Suche | alle drei Kategorien oben halten |
| 20 | `AndroidManifest.xml` | zwei `<activity>` und ein `<receiver>` am Ende von `<application>`, `REQUEST_INSTALL_PACKAGES` und `RECORD_AUDIO` (Mikrofon zum PC) bei den Berechtigungen | `GradingPresetsActivity` (nicht exportiert), `GradingTestPatternActivity` (exportiert, für `adb am start` im Gerätetest), `UpdateStatusReceiver` (Ergebnis der Installations-Sitzung) | – |
| 23 | `Game.java` | `handleSpecialKeys`: `default` im `switch (specialKeyCode)` der Aktion „alle Modifikatoren oben“ | Ende einer Bild-Kürzel-Kombination melden; der Controller gibt dann (250 ms später) die Maus fürs Bedienfeld frei | muss in diesem Zweig bleiben, sonst hängen Modifikatoren am Host |
| 24 | `Game.java` | `surfaceChanged`: erstes Argument von `conn.start(...)` | Audio-Renderer aus `SfAudio.createRenderer` (Jitter-Puffer; Upstreams `AndroidAudioRenderer` als Einstellung „Wie Moonlight“) | Upstreams Import von `AndroidAudioRenderer` bleibt stehen, auch wenn Game ihn nicht mehr direkt nutzt |
| 22 | `PcView.java` | `onResume`, nach `UiHelper.showDecoderCrashDialog(this)` | Update-Prüfung auf dem Startbildschirm (gedrosselt, nie im Stream) bzw. nach der Erlaubnis „Aus dieser Quelle zulassen“ weitermachen | – |
| 21 | `README.md` | Kasten ganz oben | Hinweis auf den Fork, Links zur Doku; Upstream-Text bleibt unverändert darunter | Kasten oben halten |

## Was bei Upstream-Änderungen besonders zu prüfen ist

- **Decoder-Pfad** (`MediaCodecDecoderRenderer`): neue `configure`-Aufrufe, `setOutputSurface`, Wechsel auf
  `MediaCodec`-Callbacks oder ein neuer Renderer (z. B. GL/Vulkan in Upstream selbst). Dann `ARCHITEKTUR.md` §4 neu
  durchdenken und den Gerätetest vollständig fahren.
- **Frame-Pacing** (`releaseOutputBuffer`-Zeitstempel, neue Pacing-Modi): `GradingController.toRendererPacing()`
  ergänzen.
- **Tastenbehandlung** (`handleSpecialKeys`, neue Upstream-Kürzel): Kollisionen mit `GradingHotkeys` prüfen. Upstream hat
  Vorrang, dann weicht das Bild-Kürzel aus, und `BEDIENUNG.md` wird angepasst.
- **Surface-Lebenszyklus** (`surfaceChanged`/`surfaceDestroyed`, PiP, Mehrfenster): Reihenfolge der Punkte 10/11 halten.
- **Ton** (`AndroidAudioRenderer`, Interface `AudioRenderer`, `conn.start`, in moonlight-common-c `AudioStream.c` und
  `LiGetPendingAudioDuration`): Änderungen an Upstreams Renderer (Puffergröße, Low-Latency-Logik, Effekte) in
  `SfAudioRenderer` nachziehen; `JitterBufferSimTest` neu bewerten.
