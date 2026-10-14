# Bedienung: Moonlight E-Ink

Stand: 12.10.2026, Version 12.2-sf.1. Was der Fork kann und wie man ihn benutzt.

## 1. Installieren

- Die App heißt **Moonlight E-Ink** (`com.selfframed.moonlight`). Sie wird **neben** dem offiziellen Moonlight
  installiert; das offizielle bleibt als Rückfallebene.
- Einmalig: Rechner hinzufügen bzw. finden lassen und mit Sunshine per PIN koppeln, wie beim offiziellen Moonlight.
  Kopplung und Einstellungen werden nicht aus dem offiziellen Moonlight übernommen.
- Erstinstallation: auf dem Telefon https://github.com/SelfFramed/moonlight-android/releases öffnen und die APK
  `moonlight-eink-<version>-nonRoot-release.apk` antippen (Alternativen: [BUILD.md](BUILD.md)). Falls Google Play Protect
  nachfragt: „Weitere Details“ → „Ohne Scan installieren“ (oder scannen lassen).
- **Updates kommen danach von selbst:** Beim Öffnen fragt die App höchstens alle 6 Stunden auf GitHub nach einer neuen
  Version und bietet „Installieren“ an. Beim ersten Update leitet sie einmalig zu „Aus dieser Quelle zulassen“, danach
  reicht ein Tippen plus Androids Bestätigung. Von Hand: Einstellungen → Updates (SelfFramed) → „Jetzt nach Updates
  suchen“. Details: [RELEASE.md](RELEASE.md).

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
| Strg+Alt+Shift+**B** | Akkustand kurz anzeigen; ist die stehende Akkuwarnung da, sie bis zum nächsten Prozentpunkt ausblenden |
| Strg+Alt+Shift+**M** | Mikrofon zum PC an/aus (beim Einschalten 3 s „Mikro an“) |

Unverändert von Upstream: Strg+Alt+Shift+**Q** beendet den Stream, **Z** gibt Maus und Tastatur frei oder fängt sie
wieder ein, **C** blendet den Mauszeiger ein und aus.

Jeder Wechsel zeigt oben kurz eine Einblendung (schwarz auf weiß, z. B. „3 · Papierweiß (Text)“). Abschaltbar in den
Einstellungen. Der Wechsel wirkt sofort, auch wenn sich im Bild gerade nichts bewegt.

Beim Stream-Start erscheint der Hinweis mit aktivem Preset und Strg+Alt+Shift+P nur bei den ersten 3 Streams. Danach
nur, wenn „Hinweis beim Stream-Start immer zeigen“ eingeschaltet ist.

**Akkuwarnung (Telefon):** Im Stream siehst du die Statusleiste nicht. Deshalb warnt der Fork oben in der Mitte:

- bei 15 % 8 Sekunden lang: „Akku 15 % · bald laden“;
- ab 7 % bleibend, mit dickem Rahmen: „Akku 7 % · jetzt laden“. Die Zahl zählt mit, und die Warnung verschwindet erst,
  wenn der Akku wirklich lädt. Dann erscheint kurz „Lädt · 7 %“.
- Steckt das Telefon am Lapdock und der Akku sinkt trotzdem, steht darunter „angeschlossen, lädt aber nicht“.
- Jede Stufe meldet sich einmal pro Entladung. Startest du den Stream schon unter einer Schwelle, kommt die Warnung nach
  2–3 Sekunden.
- Strg+Alt+Shift+**B** zeigt jederzeit den Akkustand. Ist die bleibende Warnung im Weg, blendet B sie bis zum nächsten
  Prozentpunkt aus.

**Mikrofon zum PC:** Das Telefon-Mikrofon kommt am PC an, ohne dass du etwas einrichten musst.

- **Strg+Alt+Shift+M** im Stream. Beim allerersten Mal fragt Android nach dem Mikrofon-Zugriff (erlauben) und koppelt
  dann mit dem PC: Oben steht „Am PC bestätigen · Code 123 456“. Auf dem PC-Desktop, den du im Stream siehst, fragt
  ein Fenster mit demselben Code. Mit einem Klick auf „Zulassen“ ist das Mikrofon an. Danach reicht M allein.
- Beim Einschalten steht oben 3 Sekunden lang „Mikro an“, danach ist das Bild wieder frei. Das Mikrofon bleibt an, bis
  du M drückst („Mikrofon aus“) oder der Stream endet.
- Solange es an ist, ist es am PC das Standard-Mikrofon. Teams, Discord oder ein
  Diktierprogramm mit der Einstellung „Standard“ hören es also ohne Umstellen. Beim Ausschalten (M) kommt dein
  vorheriges Standard-Mikrofon zurück, z. B. die UR44.
- Bei jedem Stream-Start ist es aus, beim Stream-Ende geht es aus.
- Über den Telefonlautsprecher zurückkommender Stream-Ton wird von Androids Echo-Unterdrückung herausgefiltert. Mit
  Kopfhörern am Telefon gibt es gar kein Echo.
- Kopplungen löschen: am Telefon unter Einstellungen → Ton (SelfFramed) → „Mikrofon zum PC“; am PC in
  `C:\Users\mpp\.selfframed\mic\devices.json`.

## 3. Bild-Bedienfeld (Strg+Alt+Shift+P)

Das Bedienfeld liegt rechts über dem laufenden Stream. Jede Änderung wirkt live und wird automatisch gespeichert; es
gibt keinen Speichern-Knopf. Die Tasten, die das Bedienfeld benutzt, kommen nicht beim Host an; alles andere (z. B.
gehaltene Tasten, Gamepad) läuft normal weiter, sodass am Host nichts hängen bleibt. Schließen mit **Esc** (oder wieder
Strg+Alt+Shift+P).

**Maus:** Sobald du nach Strg+Alt+Shift+P alle Tasten losgelassen hast, ist die Maus frei und wirkt im Bedienfeld
(Punkte ziehen, Regler, Knöpfe); Bewegungen gehen dann nicht an den Host. Beim Schließen fängt der Stream die Maus wieder
ein. Das ist dasselbe Verfahren wie bei Moonlights Strg+Alt+Shift+Z, deshalb bleibt am Host keine Taste hängen.

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
| Hinweis beim Stream-Start immer zeigen | sonst nur bei den ersten 3 Streams |
| Akkuwarnung im Stream | an (Standard) oder aus; braucht „Bildanpassung im Stream“ |
| Erste Warnung | 10, 15 (Standard), 20 oder 25 %: kurzer Hinweis |
| Bleibende Warnung | 3, 5, 7 (Standard) oder 10 %: bleibt, bis der Akku lädt |
| Presets verwalten | Testbild, Export, Import, Startwerte |
| Tastenkürzel | Übersicht |
| Updates (SelfFramed) | automatische Update-Suche an/aus, „Jetzt nach Updates suchen“ (zeigt die installierte Version) |

In der Kategorie „Ton (SelfFramed)“:

| Einstellung | Wirkung |
|---|---|
| Mikrofon zum PC | zeigt, mit wie vielen PCs das Telefon gekoppelt ist; Tippen löscht die Kopplungen. Einrichten ist nicht nötig (im Stream Strg+Alt+Shift+M). Braucht „Bildanpassung im Stream“. |
| Audio-Puffer | **Automatisch (empfohlen):** startet mit 60 ms Vorrat, wächst nach Aussetzern von selbst (bis 250 ms) und merkt sich den Wert für den nächsten Stream. **50/100/200 ms:** feste Größe. **Wie Moonlight:** der minimale Puffer des offiziellen Moonlight (geringste Latenz, kann knacksen). |

## 8. Leistungsanzeige

Ist in den Moonlight-Einstellungen die Leistungsanzeige an, steht darunter eine Zeile wie
„Bildstufe: 0,6 ms/frame, 60 fps, 0 skipped · 3 · Papierweiß (Text)“: die Zeit für Abholen, Zeichnen und Übergabe pro
Bild und wie viele Bilder zugunsten eines neueren übersprungen wurden. Damit lässt sich der Mehraufwand am Gerät
nachprüfen.

Darunter steht eine Zeile wie „Ton: 58 ms (Ziel 60) · Aussetzer 0 · Ausgleich −1/+0 · Lautsprecher, 48 kHz, schnell“.
Sie zeigt:

- die aktuelle Verzögerung im Puffer und das Ziel;
- wie oft der Ton leer lief;
- wie viele Pakete zum Ausgleich unhörbar überblendet gekürzt oder ergänzt wurden;
- wohin der Ton geht (Lautsprecher, Kopfhörer, Bluetooth, USB, HDMI/DP), mit welcher Ausgaberate und ob Android den
  schnellen Pfad gewährt hat.

## 9. Wenn etwas nicht geht

| Symptom | Ursache und Abhilfe |
|---|---|
| Einblendung „Bildanpassung nicht verfügbar: …“ | Die GPU-Stufe konnte nicht starten; der Stream läuft ohne sie. Grund notieren (steht auch im Log) und melden. |
| „HDR-Stream: Bildanpassung ist aus“ | HDR ist in den Moonlight-Einstellungen an; für E-Ink ausschalten. |
| Kürzel wirken nicht | Alle drei Modifikatoren (Strg, Alt, Shift) halten; ist das Bedienfeld offen, wirken die Tasten dort. |
| Steile Kurven zeigen Blöcke/Bänder | Kurven verstärken Kompressionsartefakte. Bitrate erhöhen oder die Kurve sanfter machen. |
| Ton knackst oder setzt aus | Leistungsanzeige ansehen. Steigt „Aussetzer“, wächst der Puffer bei „Automatisch“ von selbst; sonst „Audio-Puffer“ höher stellen. Mit Bluetooth-Kopfhörern hilft 5-GHz-WLAN, weil sich 2,4-GHz-WLAN das Funkband mit Bluetooth teilt. |
| Mikrofon kommt am PC nicht an | In der Anwendung „Mikrofon (Steam Streaming Microphone)“ wählen. Läuft der Empfänger? `%USERPROFILE%\.selfframed\mic\receiver.log` zeigt „microphone on“ beim Einschalten; steht dort nichts, läuft er nicht. Sonst `DIAGNOSE.md` §5. |
| Ton kommt zu spät | „Audio-Puffer“ auf 50 ms. Auf E-Ink erscheint das Bild ohnehin später als der Ton. |
| Alles kaputt | Einstellungen → Bildanpassung im Stream aus und Audio-Puffer „Wie Moonlight“, oder das offizielle Moonlight nehmen. |
