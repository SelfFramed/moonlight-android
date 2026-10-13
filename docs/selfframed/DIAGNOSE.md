# Diagnose: Version, Ton, Bild, Netz

Stand: 13.10.2026. Die Werkzeuge liegen in `tools/sf/diag/`. Alle lesen nur und erzeugen kaum Last; Martin kann
währenddessen streamen.

## 1. Welche Version läuft auf dem Telefon?

- Direkt: Einstellungen → Updates (SelfFramed) → „Installiert: …“, oder Martin fragen.
- Ohne Rückfrage, als Indiz: die Download-Zähler der Releases. Die App lädt die APK von GitHub; CI und eigene Prüfungen
  laden nichts herunter.
  ```bash
  gh api repos/SelfFramed/moonlight-android/releases --jq '.[] | "\(.tag_name): \(.assets[0].download_count)"'
  ```
- Die App sucht nur alle 6 Stunden selbst. Nach einem Release Martin auf „Jetzt nach Updates suchen“ hinweisen.
- Lehre vom 13.10.2026: „Am Ton hat sich nichts geändert“ bezog sich auf 12.2-sf.2, weil 12.2-sf.3 noch nicht
  veröffentlicht war. Bei „keine Änderung“ zuerst die Version klären.

## 2. Ton knackst, setzt aus oder „laggt“

Reihenfolge: Client, dann Host, dann Netz.

1. **Client:** Leistungsanzeige an (Erweiterte Einstellungen → „Performance Overlay aktivieren“), dann die Zeile
   „Ton: …“ ansehen. Sie zeigt Verzögerung, Ziel, Aussetzer, Ausgleich und das Ausgabegerät (`BEDIENUNG.md` §8).
   - „Aussetzer“ steigt: Die Pakete kommen unregelmäßig an. Bei „Automatisch“ wächst der Puffer von selbst.
   - „Aussetzer“ bleibt bei 0, und trotzdem stört etwas: Dann liegt es am Ausgabeweg (z. B. Bluetooth über 2,4-GHz-WLAN)
     oder am Host.
   - Zum Vergleich „Audio-Puffer“ auf „Wie Moonlight“ stellen.
2. **Host:**
   - `powershell -ExecutionPolicy Bypass -File tools\sf\diag\sunshine-log.ps1` zeigt Sitzungen und Audioformat
     („Audio mixer format …“, „Opus initialized …“) und zählt Warnungen. Solange Sunshine läuft, zeigt der Explorer das
     Log mit 0 Byte; das Skript liest es trotzdem.
   - `tools\sf\diag\audio-loopback-probe.ps1` zeigt Standardgerät, Format und Geräteperiode, und wie gleichmäßig Windows
     den Ton an Sunshine liefert. Es spielt nichts ab.
   - `tools\sf\diag\audio-endpoints.ps1` listet alle Wiedergabegeräte mit Format.
   - `tools\sf\diag\virtual-mic-check.ps1` zeigt Wiedergabe- und Aufnahmegeräte mit Zustand und prüft, ob ein
     virtuelles Mikrofon (Standard: Steam Streaming Microphone) Ton durchreicht. Hintergrund: `ROADMAP.md`, Mikrofon.
3. **Netz:** `tools\sf\diag\net-jitter.ps1 -Target <Telefon-IP>`. Das Telefon findet man mit
   `Get-NetNeighbor -AddressFamily IPv4 -State Reachable`; Android nutzt eine zufällige MAC.
4. **Simulation:** In `JitterBufferSimTest` ein Szenario mit den gemessenen Werten anlegen. Dann zeigt sich, ob der
   Puffer das Muster verkraftet.

Referenzwerte vom 12.10.2026:

| Messgröße | Wert |
|---|---|
| Standardgerät | UR44 mit 48 kHz |
| Windows-Loopback | alle 10 ms genau 10 ms Ton (Spitze 10,7 ms) |
| Sunshine | 48 kHz ohne Umrechnung, Opus 512 kbps |
| WLAN, RTT | Median 2,7 ms, p99 5,3 ms |

Ursache damals war Upstreams Audiopuffer (`ARCHITEKTUR.md` §5b), behoben in 12.2-sf.3.

## 3. Bekannte Meldungen im Sunshine-Log

| Meldung | Bedeutung |
|---|---|
| `Failed to install Steam audio drivers: 259` | Kommt bei jeder Sitzung. Sunshine nimmt dann das Windows-Standardgerät auf. Harmlos. |
| `"RegQueryValueExW" failed when getting size` (code 2) | Viele Zeilen bei jedem Sitzungsstart. Harmlos. |
| `WSASendMsg() failed: 10055` | WSAENOBUFS: kein Sendepuffer frei. Einmaliger Ausbruch am 08.10.2026 um 11:30 (764-mal), seither nicht wieder. Häuft es sich, Netzwerkadapter, Treiber und Last prüfen. |
| `Windows is currently blocking DRM-protected content` | Geschützte Inhalte bleiben im Stream schwarz. Erwartet. |

## 4. Bild

- Leistungsanzeige, Zeile „Bildstufe: … ms/frame …“. Steht dort „Bildanpassung nicht verfügbar: <Grund>“, den Grund
  melden.
- Ohne Stream: das Testbild (`GradingTestPatternActivity`). Im Emulator prüft `tools/sf/verify-gpu-stage.py` pixelgenau
  gegen die Original-Mathematik.

## 5. Mikrofon zum PC

1. **Kommt etwas an?** In `%USERPROFILE%\.selfframed\mic\receiver.log`:
   - „pairing request … accepted“: Die Kopplung hat geklappt.
   - „session … from …“ und „microphone on“: Der Ton kommt an.
   - „rejected … damaged or forged“: Das Paket war beschädigt oder gefälscht.
   - „clock difference or replay“: Die Uhrzeiten von Telefon und PC weichen um mehr als 2 Minuten ab.
   - Nichts davon: Der Empfänger läuft nicht. Starten mit `tools\sf\mic\install-autostart.ps1 -StartNow`.
2. **Kopplung hängt bei „Am PC bestätigen“:** Der Dialog ist eventuell hinter einem Vollbildfenster. Alt+Tab oder die
   Taskleiste („SelfFramed: Mikrofon koppeln“).
3. **Hört die Anwendung etwas?** Sie sollte auf „Standard“ stehen; solange das Mikrofon an ist, ist es dann das
   Steam-Mikrofon.
   - Messen: `python tools/sf/mic/measure-virtual-mic.py`.
   - Gegentest ohne Telefon: einen Test-Empfänger mit `--state-dir`, `--auto-accept`, `--keep-default` und
     `--port 48101` starten, dann `python tools/sf/mic/send-test-tone.py --port 48101`.
4. **Ist das Gerät aktiv?** `tools\sf\diag\virtual-mic-check.ps1`. Ist die Wiedergabeseite deaktiviert (zum Beispiel
   nach einem Steam-Update), in den Sound-Einstellungen wieder aktivieren.
5. **Standard-Mikrofon falsch:** Nach einem Absturz stellt der Empfänger beim nächsten Start den alten Standard wieder
   her (`default-switched.json`). Von Hand geht das in den Windows-Soundeinstellungen.
6. **Telefon:** In logcat stehen `SelfFramed mic: sending to …` (Ziel, Echo- und Rauschunterdrückung) und am Ende die
   Zahl der gesendeten Pakete; `pairing failed` nennt den Grund.

## 6. Logs vom Telefon

Nur mit Martins Zustimmung und per adb (`BUILD.md` §7):
`adb logcat -s com.limelight.LimeLog:I`. Darin stehen unter anderem:

- `SelfFramed audio: …`: Puffer beim Start, Aussetzer und Ausgleich am Stream-Ende;
- `SelfFramed battery: …`: Schwellen;
- `SelfFramed mic: …`: Ziel, Effekte, gesendete Pakete;
- `SelfFramed grading: …`: Bildstufe.
