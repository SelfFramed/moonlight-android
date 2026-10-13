# Roadmap

Stand: 12.10.2026. Reihenfolge = Vorschlag der Produktverantwortung (Claude). Martin entscheidet um.

## Stand (13.10.2026, 12.2-sf.4)

- [x] Fork-Fundament: eigene ID, Versionsschema, Signierung, CI, Upstream-Wächter, Runbooks
- [x] GPU-Bildstufe: Kurven (Gesamt/R/G/B), Gamma, Helligkeit, Kontrast, Sättigung, Graustufen-Mischung
- [x] 9 Presets + Neutral, Kürzel, Einblendung, Bedienfeld, Testbild, Export/Import
- [x] Repo `SelfFramed/moonlight-android`, lokale Releases, eingebaute Update-Funktion (Signatur-Secrets bewusst nicht)
- [x] 12.2-sf.2: Maus im Bedienfeld, Start-Hinweis nur 3-mal
- [x] 12.2-sf.3: Audio-Puffer (am Gerät bestätigt: „flawless“)
- [x] 12.2-sf.4: Akkuwarnung im Stream (am Gerät noch offen)
- [x] 12.2-sf.5: Mikrofon zum PC (Weg A; am Gerät noch offen, TESTPROTOKOLL H)
- [ ] **Gerätetest, Rest** nach [TESTPROTOKOLL.md](TESTPROTOKOLL.md): B6–D, F2–F5 (Ton über längere Zeit),
  G (Akku), Sehtest der Presets.

## Mikrofon zum PC: Analyse 13.10.2026, Weg A umgesetzt in 12.2-sf.5

Martin: „Mikrofon geht nicht in Moonlight.“ **Ursache: nicht umgesetzt.** Das gilt für das offizielle Moonlight und für
Sunshine; es ist kein Fehler und keine Einstellung.

- **Protokoll:** Es kennt nur Ton zum Client, keinen Rückkanal für Ton. Im Fork gibt es keine Aufnahme-Berechtigung,
  keine Aufnahme und keinen Sendeweg; die Funktionsmerkmale der Protokollbibliothek kennen Touch, Stift und Controller.
- **Anläufe bei Upstream, alle unfertig:**
  - Sunshine-PR #4078: im März 2026 wegen Inaktivität geschlossen.
  - moonlight-qt-PR #1648: offen; es fehlt laut einem Sunshine-Mitwirkenden der schwierigste Teil, das virtuelle
    Mikrofon unter Windows (ein Treiber).
  - moonlight-common-c-PR #123: offen seit Januar 2026. Das Protokoll stammt aus den Forks Sunshine-Foundation und
    Moonlight V+, die es mit dem Treiber VB-Cable im Einsatz haben.
- **Auf Martins PC:** Ein virtuelles Mikrofon gibt es schon. „Mikrofon (Steam Streaming Microphone)“ ist aktiv, seine
  Wiedergabeseite „Lautsprecher (Steam Streaming Microphone)“ ist aber deaktiviert. Einschalten geht in den
  Sound-Einstellungen über „Deaktivierte Geräte anzeigen“; prüfen mit `tools/sf/diag/virtual-mic-check.ps1`. Damit wäre
  kein neuer Treiber nötig.

**Vorschlag A: „Mikrofon zum PC“ im Fork.**

- **Telefon:** Es nimmt mit Echo-Unterdrückung auf (`VOICE_COMMUNICATION`) und schickt den Ton verschlüsselt per UDP an
  einen kleinen Empfänger auf dem PC.
- **PC:** Der Empfänger ist ein Python-Skript unter `tools/sf/`. Er spielt den Ton in das Steam-Mikrofon, das jede
  Anwendung als Eingang wählen kann. Sunshine startet und beendet ihn mit jedem Stream (`global_prep_cmd`).
- **Bedienung:** Strg+Alt+Shift+M schaltet das Mikrofon. Solange es an ist, steht eine Plakette „Mikro an“ im Bild,
  weil Androids grüner Punkt auf dem E-Ink-Panel nicht zu sehen ist. Standard ist aus.
- **Aufwand:** etwa ein Tag.
- **Am PC zu ändern, nur mit Martins Zustimmung:** die Gerätewiedergabe aktivieren, Python-Pakete installieren, einen
  Eintrag in Sunshine anlegen.

**Alternativen:**

- B: Sunshine durch Sunshine-Foundation ersetzen und das Protokoll aus PR #123 übernehmen. Das heißt Host-Wechsel,
  VB-Cable und ein Patch an der Protokollbibliothek, der das Zusammenführen mit Upstream erschwert. Neu bewerten, falls
  PR #123 bei Upstream angenommen wird.
- C: AudioRelay oder WO Mic. Fertig, aber eine Extra-App auf Telefon und PC, die jedes Mal gestartet werden muss.
- D: Das JBL-Headset direkt am PC. Es ist dort bereits gekoppelt und sofort nutzbar, aber nur in Bluetooth-Reichweite.
  Im Freisprechprofil sinkt der Ton auf Mono mit 16 kHz.

## Als Nächstes (klein, hoher Nutzen)

1. **Presets nach dem Gerätetest feinjustieren** und in `PRESETS.md` §4 festhalten.
2. **Preset je Host/App:** Beim Start eines Streams automatisch das Preset wählen, das zuletzt für diesen Rechner bzw.
   diese App genutzt wurde (z. B. Desktop → Papierweiß, Spiel X → Schatten aufhellen). Ansatz: Zuordnung
   `(Host-UUID, App-ID) → Platz` im `PresetStore`, gesetzt bei jedem Wechsel.
3. **Eigene Kürzel konfigurierbar:** Tabelle `GradingHotkeys` aus einer Einstellung lesen (Aufzeichnungsdialog „Taste
   drücken“). Auch für QMK-Tasten am eigenen Keyboard-PCB.
4. **Schärfen für Text** (Unscharf maskieren, 3×3, Stärke je Preset): ein zusätzlicher Shader-Pfad. Vorher
   prüfen, ob das Dithering des Glider davon profitiert oder Artefakte verstärkt.

## Mittelfristig

5. **Farbtonbasierte Graustufen-Mischung** im Stil der Lightroom-S/W-Mischung (Rot, Orange, Gelb, Grün, Aqua, Blau,
   Lila, Magenta). Technisch: 3D-LUT (z. B. 33³) statt 1D-LUT, in der GPU-Stufe direkt nutzbar.
6. **Kurvenvorschau mit Histogramm** im Bedienfeld (aus dem aktuellen Bild, siehe 7).

- **Ton, falls die Zeile „Ton“ am Gerät weiter Aussetzer zeigt:** verspätete Pakete mit Opus-PLC verdecken statt Stille
  (braucht einen kleinen JNI-Einstieg), oder AAudio statt AudioTrack.

## Fernziel: automatische Belichtung (wie a6500)

7. **Auto-Belichtung / Auto-Tonwert:**
   - Messen: Im GL-Thread wird alle n Bilder (z. B. 4) die Bildtextur in ein kleines FBO (64×48) gerendert, per
     `glReadPixels` gelesen und daraus ein Helligkeits-Histogramm gebildet. Das kostet wenige Mikrosekunden.
   - Regeln: Perzentile (z. B. 0,5 % und 99,5 %) als Schwarz- und Weißpunkt, Median gegen Zielhelligkeit als
     Mitten-Gamma. Mit zeitlicher Glättung (Belichtungsträgheit wie bei der Kamera, getrennt für Auf- und Abblenden) und
     Hysterese, damit E-Ink nicht flackert.
   - Bedienen: Modus je Preset (Aus / Auto / Auto + Korrektur), Belichtungskorrektur ±2 EV als Regler und Kürzel,
     Messfeldwahl (ganz, mittenbetont).
   - Einbau: `GradingRenderer` liefert das Histogramm, ein `AutoExposure`-Regler erzeugt pro Messung eine `GradingLut`
     und speist sie über `setLut()` ein. Die Architektur trägt das ohne Umbau (`ARCHITEKTUR.md` §7).
   - E-Ink-Besonderheit: Jede LUT-Änderung erzeugt am Panel einen Bildwechsel. Änderungen deshalb in Stufen und nur bei
     deutlicher Szenenänderung.

## Bewusst nicht geplant

- HDR-Tonemapping (E-Ink hat keinen HDR-Nutzen).
- Host-seitige Filter (widerspricht dem Grundsatz „Korrektur gehört zum Display“).
- Upstream-Beitrag des Features: möglich, aber nur mit Martins Zustimmung und nach Gerätetest.
