# Presets: Format, Startwerte, Einstellhilfe

Stand: 12.10.2026.

## 1. JSON-Format

Ein einzelnes Preset. Es ist eine Obermenge des gespeicherten Zustands der Userscripts `game-curves.js` /
`discord-stream-filter`; deren Zustand lässt sich direkt importieren:

```json
{
  "name": "Papierweiß (Text)",
  "brightness": 100,
  "contrast": 100,
  "saturation": 100,
  "gamma": 1.0,
  "curves": {
    "master": [[0, 0], [0.10, 0], [0.88, 1], [1, 1]],
    "r": [[0, 0], [1, 1]],
    "g": [[0, 0], [1, 1]],
    "b": [[0, 0], [1, 1]]
  },
  "mono": { "enabled": false, "r": 0.299, "g": 0.587, "b": 0.114 }
}
```

| Feld | Bereich | Bedeutung |
|---|---|---|
| `brightness` | 50–200 (%) | `v · b` |
| `contrast` | 50–200 (%) | `(v − 0,5) · k + 0,5` |
| `saturation` | 0–200 (%) | 0 = Grau, Gewichte wie CSS `saturate()` |
| `gamma` | 0,4–3,0 | `v^(1/gamma)`; > 1 hellt Schatten auf |
| `curves.*` | 2–32 Punkte `[x, y]` in [0,1] | erster x = 0, letzter x = 1, Abstand ≥ 0,015. Ungültige Kurven werden zur Identität (wie im Userscript). |
| `mono` | Gewichte 0–1 | Graustufen-Mischung vor allen anderen Schritten; Gewichte werden normiert |

Export aller Plätze: `{"format": "selfframed-moonlight-grading", "version": 1, "activeSlot": 3,
"filterEnabled": true, "slots": [9 × Preset]}`. Unbekannte Felder werden ignoriert, fehlende bekommen neutrale
Werte. Neue Felder bleiben dadurch abwärtskompatibel.

Reihenfolge der Verarbeitung: Mischung → Gamma → Gesamtkurve → Kanalkurve → Helligkeit → Kontrast → Sättigung (siehe
[ARCHITEKTUR.md](ARCHITEKTUR.md)).

## 2. Startwerte und Begründung

Hintergrund: Der Glider rechnet RGB mit BT.601-Gewichten in Grau um und rastert auf 16 Stufen (Dithering). Wo ein
Bildbereich zwischen zwei Stufen liegt, entsteht ein Dithermuster. Bei Text und Flächen wirkt das als Rauschen.

| Platz | Name | Werte | Warum |
|---|---|---|---|
| 1 | Film & Spiel | Gamma 1,25, Kontrast 105 % | Martins eigener „vorsichtiger Startpunkt für dunkle Streams“ aus dem discord-stream-filter-README |
| 2 | Schatten aufhellen | Gamma 1,6, Kontrast 105 % | 50 % Grau wird ≈ 65 %; Schwarz bleibt schwarz (ein angehobener Schwarzpunkt würde nur zu Dither-Rauschen) |
| 3 | Papierweiß (Text) | Kurve (0,0)(0,10;0)(0,88;1)(1,1) | wirkt wie Tonwertkorrektur mit Schwarzpunkt 10 % und Weißpunkt 88 % und weichen Schultern: Hintergründe werden reinweiß, Schrift schwarz, Mitten kontrastreicher |
| 4 | Hoher Kontrast | S-Kurve (0,25;0,12)(0,75;0,88) | mehr Mittenkontrast, Enden bleiben erhalten |
| 5 | Schwarzweiß-Schwelle | (0,40;0,03)(0,60;0,97) | fast harte Schwelle um 50 %: passt zu den schnellen Monochrom-Modi des Glider |
| 6 | Invertiert | (0,1)(1,0) | dunkle Oberflächen werden hell |
| 7–9 | Eigenes | neutral | frei |

Alle Startwerte sind Schätzungen. Was sich am Panel bewährt, wird hier mit Datum nachgetragen (Abschnitt 4).

## 3. Einstellhilfe für E-Ink

1. **Testbild** öffnen (Presets verwalten → Testbild öffnen). Die obere Leiste zeigt die 16 Panel-Stufen.
2. **Schwarz- und Weißpunkt zuerst:** Endpunkte der Gesamtkurve nach innen ziehen (bzw. die inneren Punkte wie bei
   „Papierweiß“), bis die hellsten Hintergrund-Grautöne als reines Weiß erscheinen und dunkle Schrift als reines
   Schwarz.
3. **Dann die Mitten:** ein Punkt in der Mitte hoch = heller; S-Form = mehr Kontrast.
4. **Zählen:** Wie viele der 16 Stufen bleiben unterscheidbar? Eine steile Kurve gewinnt Kontrast, kostet aber Stufen.
5. **Farben:** Bei Diagrammen und Spielen mit Farbcodes die Graustufen-Mischung testen (Farbpaare unten im Testbild).
6. **Gegenprobe im Stream** mit Strg+Alt+Shift+F (Vorher/Nachher).

## 4. Am Gerät bewährt

| Datum | Platz/Name | Werte | Beobachtung |
|---|---|---|---|
| – | – | – | noch kein Gerätetest |
