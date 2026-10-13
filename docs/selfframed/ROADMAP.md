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
- [ ] **Gerätetest, Rest** nach [TESTPROTOKOLL.md](TESTPROTOKOLL.md): B6–D, F2–F5 (Ton über längere Zeit),
  G (Akku), Sehtest der Presets.

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
