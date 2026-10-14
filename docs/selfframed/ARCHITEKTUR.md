# Architektur der Bildstufe

Stand: 12.10.2026, Fork-Version 12.2-sf.1. Zielgruppe: Martin und jede künftige Claude-Sitzung, die den Fork pflegt.

## 1. Überblick

```
Sunshine (Host) ──Netz──▶ moonlight-common-c ──▶ MediaCodec (HW-Decoder)
                                                    │  rendert in
                                                    ▼
                                      Surface(SurfaceTexture)   ◀── neu: GradingRenderer
                                                    │  externe OES-Textur
                                                    ▼
                                GLES2-Shader: [Graustufen-Mischung] → LUT je Kanal → Sättigung
                                                    │  eglSwapBuffers (+ Zeitstempel)
                                                    ▼
                                      SurfaceView (StreamView) ──▶ SurfaceFlinger ──▶ Glider ──▶ E-Ink
```

Ohne Fork rendert MediaCodec direkt in die SurfaceView. Der Fork schiebt genau eine GPU-Stufe dazwischen. Ist sie
abgeschaltet oder scheitert sie, läuft alles wie bei Upstream.

## 2. Warum im Client und warum so

| Entscheidung | Begründung | Verworfen |
|---|---|---|
| Korrektur im **Client** | Die Kurven gleichen das Panel aus, und das Panel hängt am Telefon. Ein Ort für alle Hosts (Windows, Bazzite, Pi) und alle Apps. | Host-seitig (ReShade, dwm_lut, Sunshine-Filter): pro Host und teils pro App, auf dem Pi gar nicht machbar. |
| **SurfaceTexture + GLES2** | Läuft ab API 21, volle Kontrolle über Shader, Pacing und Neuzeichnen. Kosten: ein Vollbild-Durchgang mit 4 Texturzugriffen pro Pixel; bei 1600×1200 auf dem Adreno 650 geschätzt unter 1 ms. | TextureView + RenderEffect/AGSL: erst ab API 33, eine Frame-Stufe mehr Latenz, weniger Kontrolle. SurfaceControl-Farbmatrix: nur linear, keine Kurven, versteckte API. |
| **1D-LUT (256 Texel je Kanal)** | Gamma, Kurven, Helligkeit und Kontrast sind punktweise je Kanal und werden zu einer Tabelle zusammengebacken. Ein Preset-Wechsel ist nur ein 1-KB-Upload. | Alles im Shader rechnen: teurer, Kurven bräuchten Spline-Auswertung pro Pixel. 3D-LUT: unnötig, solange keine farbtonabhängige Mischung gebraucht wird (siehe Roadmap). |
| **Mathe = Userscript** | `ToneCurve` ist ein 1:1-Port von `curveFunction()` (monotone kubische Hermite-Interpolation) aus `game-curves.js`; die Reihenfolge entspricht der CSS/SVG-Filterkette. Martins Werte wirken gleich. | Eigene Spline-Variante: hätte vertraute Werte verändert. |

Reihenfolge pro Pixel (`GradingLut`):

1. Graustufen-Mischung (optional): `grau = r·wR + g·wG + b·wB`, Gewichte normiert. Standard: BT.601 = Glider `rgb2y.v`.
2. Gamma: `v^(1/gamma)` (gamma > 1 hellt auf).
3. Gesamtkurve, danach die Kanalkurve (R/G/B).
4. Helligkeit `v·b`, Kontrast `(v−0.5)·k+0.5`. Jeder Schritt wird auf [0,1] begrenzt, wie bei SVG-/CSS-Filtern.
5. Sättigung mit den Gewichten der CSS-Funktion `saturate()` (0.213/0.715/0.072).

Die Schritte 2–4 stehen in der LUT, 1 und 5 als Uniforms im Shader. Ist ein Preset neutral, nutzt der Renderer einen
Durchreich-Shader ohne LUT.

## 3. Klassen

| Klasse | Rolle | Thread |
|---|---|---|
| `grading/ToneCurve` | unveränderliche Kurve, Validierung wie `validateCurve()` | beliebig |
| `grading/GradingPreset` | ein Preset (veränderlich), JSON lesen und schreiben, Wertebereiche | UI |
| `grading/GradingLut` | unveränderlicher, gebackener Schnappschuss für die GPU | erzeugt in UI, gelesen in GL |
| `grading/PresetBundle` | 9 Plätze, aktiver Platz, Filterschalter, Import/Export (reines Java, JVM-getestet) | UI |
| `grading/PresetStore` | Persistenz in eigener SharedPreferences-Datei `sf_grading` | UI |
| `grading/BuiltinPresets` | Startwerte für E-Ink | – |
| `grading/GradingRenderer` | EGL/GLES-Stufe, eigener HandlerThread `SF Grading GL` | GL |
| `grading/GradingController` | Klebstoff für `Game`: Lebenszyklus, Hotkeys, Einblendung, Bedienfeld, Speichern | UI |
| `grading/GradingHotkeys` | Tastentabelle (Strg+Alt+Shift + …) | – |
| `grading/GradingEditorHost`, `GradingPanel` | Vertrag zwischen Controller und Bedienfeld | UI |
| `ui/GradingPanelView`, `ui/CurveEditorView` | Bedienfeld mit Kurveneditor | UI |
| `grading/GradingPresetsActivity` | Sichern, Laden, Zurücksetzen (Datei, SAF) | UI |
| `grading/GradingTestPatternActivity` | Testbild durch die echte GPU-Stufe, ohne Stream | UI + GL |

## 4. Lebenszyklus und Threads

```
Game.onCreate ─▶ GradingController.create()          (null, wenn in den Einstellungen aus)
Game.surfaceChanged (1. Mal)
   └▶ controller.attachOutput(holder, w, h, pacing)
         └▶ GradingRenderer.create(): GL-Thread starten, EGL+Shader+SurfaceTexture anlegen (wartet ≤ 3 s)
         ◀─ Surface für den Decoder (oder null → direkter Pfad)
   └▶ decoderRenderer.sfSetOutputSurface(surface, releaseHook)
   └▶ conn.start(...)                                  (Upstream)
MediaCodecDecoderRenderer.configureAndStartDecoder: configure(format, sfOutputSurface ?: SurfaceView)
   … pro Bild: releaseOutputBuffer(index, ts) → onFrameAvailable (GL-Thread) → render()
Game.surfaceDestroyed ─▶ controller.detachOutput()  (synchron: EGL-Fenster zerstören, ≤ 2 s)
NvConnection.stop ─▶ … ─▶ decoderRenderer.cleanup(): videoDecoder.release(); dann releaseHook → renderer.release()
Game.onDestroy ─▶ controller.onDestroy()  (speichert, gibt die Stufe frei, falls cleanup() nicht lief)
```

- **Zeichnen:** `onFrameAvailable` zählt Bilder; ein einziger Render-Auftrag holt alle anstehenden Bilder ab
  (`updateTexImage` n-mal) und zeichnet nur das neueste. Hängt der GL-Thread kurz, wächst die Latenz nicht an.
- **Preset-Wechsel:** `setLut()` legt die neue LUT ab und plant ein Neuzeichnen des letzten Bildes. Das ist wichtig bei
  statischem Desktop, wo Sunshine kaum neue Bilder schickt.
- **Pacing:** Der Zeitstempel, den der Decoder an `releaseOutputBuffer` gibt, wird mit `eglPresentationTimeANDROID`
  an SurfaceFlinger weitergereicht. „Niedrigste Latenz“ nutzt Swap-Intervall 0: neuere Bilder ersetzen ältere.
  „Ausgewogen“ behält Intervall 1. „FPS begrenzen“ und „Max. Glätte“ verwerfen nie und verwenden keine Zeitstempel.
- **Rückfall:** GL-Fehler bei der Einrichtung → Einblendung „Bildanpassung nicht verfügbar: …“, Decoder rendert direkt.
  Die Einrichtung läuft komplett auf einem 1×1-Pbuffer; das EGL-Fenster auf der SurfaceView entsteht als letzter
  Schritt. So bleibt nach einer Zeitüberschreitung nichts mit der Surface verbunden, und der Decoder kann sie übernehmen.
  HDR-Stream → Stufe aus (E-Ink braucht kein HDR; Tonemapping wäre ein eigenes Projekt).
- **Fehler im Betrieb:** `EGL_BAD_SURFACE`/`EGL_BAD_NATIVE_WINDOW` beim Swap → Fenster bis zu dreimal neu anlegen;
  Kontextverlust oder erschöpfte Versuche → Einblendung „Bildanpassung nicht verfügbar: …“ (das Bild steht dann; Stream
  neu starten oder die Stufe in den Einstellungen abschalten).
- **Konfiguration:** exakt 8/8/8-Bit (EGL würde sonst tiefere Formate wie 10-Bit zuerst liefern); Swap-Intervall 1 nur
  bei „FPS begrenzen“/„Max. Glätte“, sonst 0, damit `surfaceDestroyed` nie auf einen hängenden Swap warten muss.

## 5. Eingabe

- Hotkeys hängen an Moonlights eigenem Schema Strg+Alt+Shift+Taste (`Game.handleSpecialKeys`). Upstream belegt Z, Q
  und C. Die Bild-Kürzel wirken sofort beim Drücken, solange die Modifikatoren gehalten werden. Erneutes Drücken von F
  schaltet erneut um (Vorher/Nachher), Auto-Repeat wird ignoriert. Wie bei Upstream sieht der Host diese Tasten nie.
- Bedienfeld offen: `Game.handleKeyDown/Up` gibt zuerst an `GradingController.handleKeyEvent`. Das Bedienfeld bekommt
  jedes Key-Down außer Modifikatoren, Strg+Alt+Shift-Kombinationen und Gamepad-Tasten, dazu nur die passenden Key-Ups.
  Alles andere läuft zum Host weiter; Tasten, die vor dem Öffnen gedrückt waren, werden dort normal losgelassen.
- Maus: Öffnet Strg+Alt+Shift+P das Bedienfeld, gibt der Controller die Eingabe frei (`setInputGrabState(false)`),
  aber erst wenn alle Modifikatoren losgelassen sind, plus 250 ms. Dafür gibt es den Hook im `default`-Zweig der
  Upstream-Aktion „alle Modifikatoren oben“, also dieselbe Stelle und Verzögerung wie Upstreams Z. So erreicht das letzte
  Key-Up den Host noch. Eine sofortige Freigabe hatte im Review hängende Modifikatoren am Host erzeugt (12.10.2026).
  Beim Schließen wird die Eingabe wieder eingefangen, sofern das Bedienfeld sie freigegeben hat. Gerätetest 12.10.2026:
  Ohne Freigabe fand Martin keinen Weg, die Maus im Bedienfeld zu nutzen; daher diese Lösung.
- Bild-Kürzel, solange Strg+Alt+Shift gehalten werden, überschreiben eine anstehende Upstream-Kombination (Z/Q/C) nicht.
- Ist die Stufe nicht aktiv (HDR, Fehler), sagen die Kürzel per Einblendung warum, statt Wechsel vorzutäuschen.

## 5a. Selbst-Update (`selfframed/update/`)

- `UpdateChecker` (Startbildschirm, gedrosselt auf 6 h, nur Release-Builds) fragt
  `BuildConfig.SF_UPDATE_API` = GitHub `releases/latest` ab. `ReleaseInfo` akzeptiert nur reguläre Releases mit Fork-Tag
  `v<x>-sf.<n>` und genau einer passenden APK; `VersionNames` vergleicht Versionen (beides JVM-getestet).
- `UpdateInstaller` lädt die APK in den Cache und prüft SHA-256 (GitHub-`digest`), Paketname, höhere `versionCode` und das
  Signatur-Zertifikat gegen die installierte App. Danach übergibt er an `PackageInstaller` (Session, keine
  FileProvider-Abhängigkeit). `UpdateStatusReceiver` zeigt Androids Bestätigung bzw. meldet Fehler.
- Die Sicherheit hängt am Signaturschlüssel: Android installiert nur Updates mit demselben Zertifikat.

## 5b. Ton (`selfframed/audio/`)

Ausgangslage (Messung 12.10.2026, Martin: „das Audio laggt“, im offiziellen Moonlight wie im Fork): Der Host liefert
sauber. Die UR44 läuft mit 48 kHz, Windows gibt alle 10 ms genau 10 ms Ton ab (Spitze 10,7 ms), und Sunshine kodiert Opus
mit 48 kHz ohne Umrechnung. Das WLAN zum Telefon hat eine RTT von 2,7 ms im Median und 5,3 ms bei p99. Upstreams
`AndroidAudioRenderer` ist auf kleinste Latenz gebaut:

- 2 Pakete (10 ms) Puffer, Wiedergabe ab dem ersten Paket;
- der Decoder-Thread läuft mit normaler Priorität;
- sobald 40 ms warten, werden Pakete hart verworfen.

Ein kurzer Hänger des Decoder-Threads, eine WLAN-Spitze oder auseinanderlaufende Uhren von PC und Telefon werden damit
hörbar.

- `SfAudio` (Einstieg für Game) wählt nach der Einstellung „Audio-Puffer“ (`sf_audio_buffer`: `auto`, `50`, `100`, `200`,
  `upstream`) den Renderer und liefert die Zeile für die Leistungsanzeige. „Automatisch“ startet mit dem Ziel, mit dem
  der letzte Stream endete (beim ersten Stream 60 ms). War der letzte Stream ohne Aussetzer, startet er 10 ms tiefer,
  mindestens bei 40 ms.
- `SfAudioRenderer` richtet den AudioTrack wie Upstream ein (`USAGE_GAME`, schneller Pfad, wenn die Ausgaberate passt
  und keine Effekte gewünscht sind), aber mit bis zu 250 ms Kapazität. Die wirksame Größe (`setBufferSizeInFrames`) ist
  das Ziel. Die Wiedergabe startet erst, wenn das Ziel gefüllt ist. Der Decoder-Thread läuft mit Audio-Priorität. Jede
  Ausnahme schaltet auf Upstreams Verhalten zurück: schreiben, solange weniger als 40 ms warten.
- `JitterBuffer` und `JitterPolicy` (reines Java) halten ein Paket zurück, damit Korrekturen überblendet werden können.
  Verzögerung = Rahmen im Track + Pakete, die vor dem Decoder warten.
  - Aussetzer (der Track lief leer): Das Ziel steigt um 20 ms, höchstens einmal pro Sekunde, bis 250 ms.
  - Uhrendrift: Verlässt der gleitende Mittelwert (2 s) das Band „Ziel ± max(20 ms, Ziel/4)“, fällt ein Paket weg (MERGE)
    oder kommt eins dazu (INSERT). Beides wird überblendet, höchstens viermal pro Sekunde.
  - Rückstau nach einem langen Aussetzer (mehr als das Ziel wartet): Pakete werden übersprungen, bis noch ein Viertel des
    Ziels wartet; dann wird überblendet angeschlossen.
- `PcmBlend`: lineare Überblendung über ein Paket (5 ms), Einblenden nach einem Leerlauf.
- Warum mehr Puffer hier nichts kostet: Das E-Ink-Bild erscheint ohnehin 100 bis 300 ms nach dem PC-Bild. Ton mit 60 bis
  120 ms Puffer liegt näher am Bild als Upstreams 10 ms.

Simulation (`JitterBufferSimTest`: echter Puffer gegen ein Zeitmodell Host → Netz → Decoder-Thread → Ausgabe, daneben
ein Modell von Upstreams Renderer):

| Szenario | SelfFramed (Automatisch) | Upstream-Modell |
|---|---|---|
| gemessene Werte (PC-Takt 10 ms, WLAN-Jitter 0–2 ms) | 0 Aussetzer | 0 Aussetzer |
| Decoder-Thread hängt 10–40 ms, etwa 1/s | 0 | 81 Aussetzer, 215 verworfene Pakete in 110 s |
| WLAN-Spitzen 30–90 ms, etwa 1/s | 0 (Ziel wächst auf 120 ms) | 205 Aussetzer, 1457 verworfen in 240 s |
| Telefon-Uhr 200 ppm schneller, 10 min | 0; 20 Pakete überblendet ergänzt | 24 Aussetzer |
| Telefon-Uhr 200 ppm langsamer, 10 min | 0; 19 Pakete überblendet gekürzt | 15 Pakete hart verworfen |
| Ausgabe nimmt 20 ms am Stück (wie Bluetooth) | 0 | 0 |

Gerätetest 13.10.2026 (12.2-sf.3, Moto G100): Martin hörte vorher ein „Lagfestival“, jetzt nach 2 Minuten „flawless“.

Unter den gemessenen Bedingungen ist auch Upstream fehlerfrei. Die Aussetzer am Gerät müssen also aus Hängern, Spitzen
oder Drift kommen. Welche Ursache es ist, zeigt am Gerät die Zeile „Ton“ der Leistungsanzeige.

Weitere Tests: `JitterPolicyTest` und `PcmBlendTest`. Debug-Builds enthalten außerdem `AudioBufferTestActivity`
(`app/src/debug/`): Testton mit 80 ms Pause alle 5 s, Ausgabe im Bildschirm und im Log `SfAudioTest`.

## 5c. Akku (`selfframed/battery/`)

Im Vollbild-Stream auf dem Lapdock sind Statusleiste und Androids Akkuwarnungen nicht zu sehen. Deshalb warnt der Fork
selbst, innerhalb des Streams.

- `BatteryWatch` (reines Java, `BatteryWatchTest`) trifft die Entscheidungen.
  - Bereiche: normal, niedrig (≤ erste Schwelle, Standard 15 %) und kritisch (≤ zweite Schwelle, Standard 7 %).
  - Ohne Laden wird gewarnt, sobald der Bereich schlechter wird als der in dieser Entladung zuletzt gewarnte: „niedrig“
    als kurzer Hinweis, „kritisch“ als stehender.
  - Laden heißt Akkustatus „lädt“ oder „voll“, nicht nur „eingesteckt“. Es beendet die Entladung, setzt beide Stufen
    zurück und entfernt die stehende Warnung. Eine kurze Bestätigung „Lädt“ gibt es nur, wenn vorher gewarnt wurde.
  - Ausblenden (Strg+Alt+Shift+B) gilt bis zum nächsten Prozentpunkt.
- `BatteryWarner` (Android) liest `ACTION_BATTERY_CHANGED` (sticky, registriert in der Stream-Activity) und zeigt eine
  Plakette oben in der Mitte, im Stil der Einblendungen: schwarz auf weiß, ohne Transparenz und Animation. Stehend hat
  sie einen dicken Rahmen statt einer Farbe.
  - Kurzer Hinweis 8 s, Bestätigung und Abruf 4 s.
  - Bei „eingesteckt, lädt aber nicht“ steht das in einer zweiten Zeile.
  - Die erste Prüfung nach dem Start wartet 2,5 s, damit der Start-Hinweis zuerst lesbar ist.
  - Der Text wird nur bei Änderungen neu gesetzt, weil jede Änderung auf E-Ink einen Bildwechsel kostet.
- Eigentümer ist `GradingController`, der Stream-Controller des Forks (Einblendungen, Kürzel, Bedienfeld). Es gibt also
  keinen zusätzlichen Eingriff in Upstream-Dateien. Folge: Mit „Bildanpassung im Stream“ aus gibt es auch keine
  Akkuwarnung. Das Kürzel B ist `GradingHotkeys.ACTION_BATTERY` und wirkt auch, wenn die GPU-Stufe nicht läuft.
- Prüfen ohne echten Akku: `adb shell dumpsys battery unplug`, `… set status 3`, `… set level 15` usw., am Ende
  `… reset`. Das geht auf dem Testbild (`GradingTestPatternActivity`) wie im Stream.

## 5d. Mikrofon zum PC (`selfframed/mic/`, `tools/sf/mic/`)

Moonlight und Sunshine haben keinen Rückkanal für Ton (Analyse: `ROADMAP.md`). Der Fork schickt das Mikrofon deshalb
über einen eigenen, verschlüsselten Seitenkanal an den PC. Dort landet es in einem virtuellen Mikrofon, und solange es
an ist, ist dieses der Windows-Standard. Version 2 (12.2-sf.6) koppelt wie Bluetooth: kein Code zum Abtippen.

- **Telefon:**
  - `MicControl` gehört dem `GradingController` und hängt am Kürzel Strg+Alt+Shift+M. Das Mikrofon ist bei jedem
    Stream-Start aus. Fehlt die Android-Berechtigung, fragt Android, und die App macht danach von selbst weiter
    (Abfrage alle 0,5 s, höchstens 60 s).
  - Ist der PC noch nicht gekoppelt, startet `MicPairer`. Die Statusspalte zeigt „Am PC bestätigen · Code 123 456“.
    Nach „Ja“ am PC landet die Kopplung in `MicPairings` (SharedPreferences `sf_mic`, je PC-UUID: Geräte-ID und
    Audio-Schlüssel). Dann folgt „Mikro an“.
  - `MicSender` nimmt mit `VOICE_COMMUNICATION` auf (48 kHz mono, Echo- und Rauschunterdrückung sowie Pegelregelung,
    soweit vorhanden) und sendet alle 10 ms per UDP an den Stream-Host (`Game.EXTRA_HOST`), Port 48100.
  - Meldet der PC „unbekanntes Gerät“, weil seine Kopplung gelöscht wurde, löscht die App ihre Kopplung. Das nächste M
    koppelt neu.
- **Kopplung** (`MicPairing` und `tools/sf/mic/sfmic.py`; Nachrichten HELLO, KEY, REVEAL, RESULT, UNKNOWN):
  - ECDH auf P-256. Das Telefon legt sich zuerst auf seinen Schlüssel fest (Hash in HELLO, Schlüssel erst in REVEAL).
    So kann ein Angreifer dazwischen keine Schlüssel mit gleichem Code suchen.
  - Daraus ergeben sich HKDF-SHA256, der Audio-Schlüssel und ein Bestätigungsschlüssel. Der sechsstellige Code ist
    SHA-256 über beide Schlüssel.
  - Die Entscheidung des PCs trägt einen HMAC-Tag, den nur hat, wer denselben Schlüssel besitzt.
- **Audio:** 24 Byte Kopf (Magic „SM“, Version 2, Flags, Geräte-ID, Sitzung, Sendezeit, Folgenummer), dann
  AES-128-GCM über 10 ms PCM, mit dem Kopf als Zusatzdaten. Ein Paket hat 1000 Byte und bleibt unter Tailscales
  1280-Byte-MTU. Nonce: Sitzung plus Folgenummer.
- **Prüfwerte:** Für feste Testschlüssel sind öffentliche Schlüssel, Audio- und Bestätigungsschlüssel, Code, Tag und
  ein Audiopaket in beiden Sprachen Byte für Byte gleich (`MicPairingTest`, `test_sfmic.py`).
- **PC:** `tools/sf/mic/receiver.py` (Python mit `sounddevice` und `cryptography`) startet ohne Fenster per Autostart
  (`install-autostart.ps1`).
  - Kopplung: Er beantwortet die Kopplung und fragt mit demselben Code über `confirm_dialog.py` (eigener Prozess,
    tkinter). Das Fenster ist groß, schwarz auf weiß, steht mittig auf dem Bildschirm mit dem Mauszeiger (im Stream:
    dem übertragenen) und wird jede Sekunde nach oben gelegt.
    - Nur ein Klick auf „Zulassen“ zählt; es gibt keine Taste dafür und keinen Fokusraub.
    - Nach 110 s ohne Antwort schließt es sich, also bevor das Telefon nach 120 s aufgibt.
    - Gekoppelte Telefone stehen in `%USERPROFILE%\.selfframed\mic\devices.json`.
    - Warum kein Windows-Meldungsfenster: Windows ignoriert „immer oben“ (`MB_TOPMOST`) still bei Fenstern eines
      Hintergrundprozesses. Am 14.10.2026 lag die Frage deshalb hinter Martins Fenstern.
  - Annahme: nur Pakete gekoppelter Geräte, mit Sendezeit innerhalb von 2 Minuten und steigenden Folgenummern.
  - Puffer: Ziel 40 ms. Über 150 ms Rückstand kürzt er auf das Ziel; läuft er leer, baut er neu auf.
  - Ausgabe: Er spielt in die Wiedergabeseite von „Steam Streaming Microphone“ (WASAPI mit Formatwandlung).
  - Standardgerät: Solange Ton kommt, ist „Mikrofon (Steam Streaming Microphone)“ der Windows-Standard in allen drei
    Rollen (`winaudio.py`, Core-Audio-COM über ctypes). Danach stellt er den vorherigen Standard wieder her, nach
    einem Absturz beim nächsten Start (`default-switched.json`). Ausschalten: `--keep-default`.
  - Testbetrieb: `--state-dir` und `--auto-accept`, nur gemeinsam.
- **Warum so:** Kein Sunshine-Eintrag, keine Adminrechte, kein Fremdtreiber. Das Steam-Mikrofon war schon installiert
  und reicht Ton verlustfrei durch. Die Bestätigung am PC ist die einzige Handlung, einmal pro Telefon und PC.
- **Tests:**
  - JVM: `MicPairingTest`.
  - PC: `test_sfmic.py`. Ende-zu-Ende mit einem Test-Empfänger (`--state-dir`, `--auto-accept`, `--keep-default`),
    `send-test-tone.py` (koppelt wie ein Telefon) und `measure-virtual-mic.py`.
  - Debug-Builds: `MicSendTestActivity` (koppeln und senden).

## 6. Einhängepunkte in Upstream-Dateien

Vollständige Liste mit Zweck: [PATCHES.md](PATCHES.md). Alle sind mit `[SelfFramed]` markiert;
`tools/sf/check-hooks.sh` zählt sie.

## 7. Erweiterungspunkte

- **Automatische Belichtung** (Roadmap): Im GL-Thread wird alle n Bilder die OES-Textur in ein kleines FBO
  (z. B. 64×48) gerendert, `glReadPixels` liefert daraus ein Histogramm. Daraus entstehen Schwarz- und Weißpunkt sowie
  ein Mitten-Gamma mit zeitlicher Glättung, plus eine Belichtungskorrektur ±EV als Nutzerregler. Das Ergebnis ist eine
  dynamische `GradingLut`, über `setLut()` eingespeist. Die Architektur trägt das ohne Umbau.
- **Weitere Pixel-Schritte** (Schärfen, farbtonbasierte Graustufen): zusätzlicher Shader-Pfad in `GradingRenderer`,
  Parameter in `GradingPreset` (JSON ist abwärtskompatibel, unbekannte Felder werden ignoriert).
