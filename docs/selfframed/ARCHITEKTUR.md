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
  HDR-Stream → Stufe aus (E-Ink braucht kein HDR; Tonemapping wäre ein eigenes Projekt).

## 5. Eingabe

- Hotkeys hängen an Moonlights eigenem Schema Strg+Alt+Shift+Taste (`Game.handleSpecialKeys`). Upstream belegt Z, Q
  und C. Die Bild-Kürzel wirken sofort beim Drücken, solange die Modifikatoren gehalten werden. Erneutes Drücken von F
  schaltet erneut um (Vorher/Nachher), Auto-Repeat wird ignoriert. Wie bei Upstream sieht der Host diese Tasten nie.
- Bedienfeld offen: `Game.handleKeyDown/Up` gibt zuerst an `GradingController.handleKeyEvent`. Das Bedienfeld bekommt
  alle Tasten außer Modifikatoren und Strg+Alt+Shift-Kombinationen. Die Maus wird freigegeben (wie Strg+Alt+Shift+Z)
  und beim Schließen wiederhergestellt.

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
