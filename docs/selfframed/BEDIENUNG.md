# Bedienung: Moonlight E-Ink

Stand: 12.10.2026, Version 12.2-sf.1. Was der Fork kann und wie man ihn benutzt.

## 1. Installieren

- Die App heißt **Moonlight E-Ink** (`com.selfframed.moonlight`). Sie wird **neben** dem offiziellen Moonlight
  installiert; das offizielle bleibt als Rückfallebene.
- Einmalig: Rechner hinzufügen bzw. finden lassen und mit Sunshine per PIN koppeln, wie beim offiziellen Moonlight.
  Kopplung und Einstellungen werden nicht aus dem offiziellen Moonlight übernommen.
- Wege aufs Telefon: siehe [BUILD.md](BUILD.md) (adb, Datei) und [RELEASE.md](RELEASE.md) (Updates per Obtainium).

## 2. Im Stream: Tastenkürzel

Alle Kürzel laufen über **Strg+Alt+Shift** (Moonlights Schema für Client-Kürzel; der Host sieht die Taste nicht).

| Kürzel | Wirkung |
|---|---|
| Strg+Alt+Shift+**1** … **9** | Preset 1 … 9 aktivieren (schaltet den Filter ein) |
| Strg+Alt+Shift+**0** | Neutral: Originalbild |
| Strg+Alt+Shift+**F** | Vorher/Nachher: Filter aus und an, das Preset bleibt. Bei gehaltenem Strg+Alt+Shift mehrfach F tippen zum Vergleichen |
| Strg+Alt+Shift+**P** | Bild-Bedienfeld öffnen/schließen |
| Strg+Alt+Shift+**→** / **Bild↓** | nächstes Preset (Reihenfolge: Neutral, 1 … 9) |
| Strg+Alt+Shift+**←** / **Bild↑** | voriges Preset |

Unverändert von Upstream: Strg+Alt+Shift+**Q** beendet den Stream, **Z** gibt Maus und Tastatur frei oder fängt sie
wieder ein, **C** blendet den Mauszeiger ein und aus.

Jeder Wechsel zeigt oben kurz eine Einblendung (schwarz auf weiß, z. B. „3 · Papierweiß (Text)“). Abschaltbar in den
Einstellungen. Der Wechsel wirkt sofort, auch wenn sich im Bild gerade nichts bewegt.

## 3. Bild-Bedienfeld (Strg+Alt+Shift+P)

Das Bedienfeld liegt rechts über dem laufenden Stream. Jede Änderung wirkt live und wird automatisch gespeichert; es
gibt keinen Speichern-Knopf. Die Tasten, die das Bedienfeld benutzt, kommen nicht beim Host an; alles andere (z. B.
gehaltene Tasten, Gamepad) läuft normal weiter, sodass am Host nichts hängen bleibt. Schließen mit **Esc** (oder wieder
Strg+Alt+Shift+P).

**Maus:** Das Bedienfeld lässt Moonlights Maus-Einfang unverändert. Für die Maus wie gewohnt **Strg+Alt+Shift+Z**
drücken (Maus frei), Punkte ziehen, danach erneut Strg+Alt+Shift+Z.

**Kurve (im Mittelpunkt, beim Öffnen ausgewählt)**

| Taste | Wirkung |
|---|---|
| ← → ↑ ↓ | gewählten Punkt verschieben (Shift: große Schritte) |
| `,` `.` oder Bild↑ Bild↓ | vorigen/nächsten Punkt wählen |
| `+`, Einfg oder N | Punkt einfügen (zwischen gewähltem und nächstem Punkt, auf der Kurve) |
| Entf, Rücktaste oder `-` | gewählten Punkt löschen (Endpunkte bleiben) |
| C | Kanal wechseln: Gesamt → Rot → Grün → Blau |
| R | aktuellen Kanal zurücksetzen |
| Maus | Klick setzt Punkt, Ziehen verschiebt, Doppelklick löscht |

Links liegen die Schatten, rechts die Lichter, nach oben wird es heller. Die Endpunkte bleiben links und rechts,
lassen sich aber nach oben und unten ziehen (Schwarz- und Weißpunkt).

**Weitere Zeilen** (Tab bzw. ↑/↓ wechselt die Zeile; in der Kurve verlässt Tab sie):
Preset-Auswahl (←/→), Filter an/aus, Helligkeit, Kontrast, Gamma, Sättigung, Graustufen-Mischung mit Gewichten R/G/B,
dazu Kanal zurücksetzen, Preset zurücksetzen, Umbenennen und Schließen. Regler: ←/→, mit Shift in großen Schritten.

Überall im Bedienfeld gilt: **1–9** wählt ein Preset, **0** Neutral, **F** Vorher/Nachher.

## 4. Presets

Neun Plätze plus „Neutral“. Das zuletzt gewählte Preset gilt beim nächsten Stream wieder. Startwerte (Begründung und
Feinheiten: [PRESETS.md](PRESETS.md)):

| Platz | Name | Idee |
|---|---|---|
| 1 | Film & Spiel | Gamma 1,25, Kontrast 105 % (dein Startpunkt aus dem discord-stream-filter) |
| 2 | Schatten aufhellen | Gamma 1,6: für dunkle Spiele, Filme, dunkle Oberflächen |
| 3 | Papierweiß (Text) | helle Grautöne werden weiß (kein Dither-Rauschen im Hintergrund), dunkle schwarz |
| 4 | Hoher Kontrast | S-Kurve |
| 5 | Schwarzweiß-Schwelle | fast harte Schwelle für knackigen Text |
| 6 | Invertiert | dunkle Oberflächen werden hell |
| 7–9 | Eigenes 7–9 | frei (neutral) |

Die Startwerte sind begründete Schätzungen, keine Messungen. Am Panel einstellen, am besten mit dem Testbild.

**Graustufen-Mischung:** Das Panel zeigt nur Helligkeit. Farben gleicher Helligkeit (Braun neben Blau, Rot neben Grün)
sehen dort gleich aus. Mit eingeschalteter Mischung legst du selbst fest, wie stark Rot, Grün und Blau ins Grau
eingehen. Die Startgewichte 0,299/0,587/0,114 entsprechen der Umrechnung des Glider, ändern also zunächst nichts.

## 5. Testbild

Einstellungen → E-Ink-Bild → Presets verwalten → **Testbild öffnen**. Es zeigt:

- die 16 Graustufen, wie das Panel sie darstellt
- einen weichen Verlauf und einen Schattenverlauf 0–64
- Text in mehreren Grautönen, hell auf dunkel und klein
- Farbpaare gleicher Helligkeit

Bedienfeld und Kürzel funktionieren dort genauso, hier auch ohne Strg+Alt+Shift. So lassen sich Presets am Panel in
Ruhe einstellen, ohne Stream. **Esc** schließt erst das Bedienfeld, dann das Testbild.

## 6. Sichern, Laden, Zurücksetzen

Einstellungen → E-Ink-Bild → **Presets verwalten**:

- **Exportieren:** alle neun Plätze, aktiver Platz, Filterschalter als JSON-Datei.
- **Importieren:** ein kompletter Export, oder ein einzelnes Preset. Ein einzelnes Preset landet im aktiven Platz, bei
  Neutral in Platz 7. Der gespeicherte Zustand deiner Userscripts `game-curves.js` / `discord-stream-filter`
  (localStorage) wird direkt verstanden.
- **Startwerte wiederherstellen:** ersetzt alle neun Plätze.

## 7. Einstellungen (Kategorie „E-Ink-Bild (SelfFramed)“, ganz oben)

| Einstellung | Wirkung |
|---|---|
| Bildanpassung im Stream | Hauptschalter. Aus = exakt das Verhalten des offiziellen Moonlight (kein GPU-Schritt). |
| Preset-Wechsel einblenden | die kurze Einblendung bei Wechseln |
| Presets verwalten | Testbild, Export, Import, Startwerte |
| Tastenkürzel | Übersicht |

## 8. Leistungsanzeige

Ist in den Moonlight-Einstellungen die Leistungsanzeige an, steht darunter eine Zeile wie
„Bildstufe: 0,6 ms/frame, 60 fps, 0 skipped · 3 · Papierweiß (Text)“: die Zeit für Abholen, Zeichnen und Übergabe pro
Bild und wie viele Bilder zugunsten eines neueren übersprungen wurden. Damit lässt sich der Mehraufwand am Gerät
nachprüfen.

## 9. Wenn etwas nicht geht

| Symptom | Ursache und Abhilfe |
|---|---|
| Einblendung „Bildanpassung nicht verfügbar: …“ | Die GPU-Stufe konnte nicht starten; der Stream läuft ohne sie. Grund notieren (steht auch im Log) und melden. |
| „HDR-Stream: Bildanpassung ist aus“ | HDR ist in den Moonlight-Einstellungen an; für E-Ink ausschalten. |
| Kürzel wirken nicht | Alle drei Modifikatoren (Strg, Alt, Shift) halten; ist das Bedienfeld offen, wirken die Tasten dort. |
| Steile Kurven zeigen Blöcke/Bänder | Kurven verstärken Kompressionsartefakte. Bitrate erhöhen oder die Kurve sanfter machen. |
| Alles kaputt | Einstellungen → Bildanpassung im Stream aus, oder das offizielle Moonlight nehmen. |
