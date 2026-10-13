# CLAUDE.md: SelfFramed-Fork von Moonlight Android

Dieses Repo ist Martins Fork von `moonlight-stream/moonlight-android` für sein E-Ink-Lapdock: Moto G100 als
Stream-Client, 13,3″-E-Ink-Panel (1600×1200, 16 Graustufen) am Glider-Board. Der Fork fügt eine GPU-Bildstufe hinzu
(Gradationskurven, Presets, Kürzel, Bedienfeld), dazu einen Audio-Puffer gegen Aussetzer, eine Akkuwarnung im Stream und
eine eingebaute Update-Funktion. Er soll dauerhaft mit Upstream zusammenführbar bleiben. Claude pflegt ihn: Martins
Wünsche einbauen, Upstream-Releases übernehmen.

Aktueller Stand und Offenes: `docs/selfframed/ROADMAP.md` (Abschnitt „Stand“) und `TESTPROTOKOLL.md` (Ergebnisse).

## Zuerst lesen

1. `docs/selfframed/AUFGABENSTELLUNG.md`: Auftrag, Steelman, Randbedingungen (verbindlich, mit Nachträgen)
   und `docs/selfframed/ENTSCHEIDUNGEN.md`: was gilt, seit wann, warum (nicht ohne Martin neu aufrollen)
2. `docs/selfframed/ARCHITEKTUR.md`: Pipeline, Threads, Lebenszyklus
3. `docs/selfframed/PATCHES.md`: jeder Eingriff in Upstream-Dateien mit Absicht
4. Je nach Aufgabe: `UPSTREAM_SYNC.md`, `BUILD.md` (auch Emulator, §8), `RELEASE.md`, `BEDIENUNG.md`, `PRESETS.md`,
   `TESTPROTOKOLL.md`, `DIAGNOSE.md` (Ton, Bild, Netz, welche Version), `ROADMAP.md`, `CHANGELOG.md` (alle in
   `docs/selfframed/`)

## Regeln

- **Upstream-Dateien nur über markierte Einhängepunkte** (`[SelfFramed]`), so klein wie möglich, voll qualifizierte
  Klassennamen statt neuer Imports. Jede Änderung an Upstream-Dateien in `PATCHES.md` und `tools/sf/hooks.txt` nachtragen;
  `bash tools/sf/check-hooks.sh` muss grün sein.
- Neuer Code nach `app/src/main/java/com/limelight/selfframed/**`, Ressourcen `sf_*`, Build-Einstellungen in
  `app/selfframed.gradle` / `app/selfframed.properties`, nie in `app/build.gradle`.
- Kurvenmathematik ist ein 1:1-Port von Martins `game-curves.js`. Wer sie ändert, erzeugt die Referenz neu
  (`node tools/sf/reference/make-curve-fixture.cjs`) und begründet die Abweichung.
- applicationId bleibt `com.selfframed.moonlight`; nie die offizielle ID verwenden (ausdrücklicher Wunsch von Upstream).
- Doku auf Deutsch, Code-Kommentare auf Englisch, Commit-Nachrichten auf Englisch mit Präfix `SelfFramed:`.
- E-Ink-Gestaltung für jede Oberfläche: weiß/schwarz, Rahmen statt Flächen, keine Bedeutung über Farbe, keine
  Transparenz und keine Animation.

## Martins Rechner

- Dieser PC ist sein Sunshine-Host; er streamt ihn oft **während** wir arbeiten. Builds immer mit niedriger Priorität:
  `powershell -File tools/sf/build-lowprio.ps1 -Tasks "testNonRootDebugUnitTest assembleNonRootDebug" -Wait`.
  Nie mehrere schwere Jobs parallel; Emulatoren nur einzeln, headless und nur, wenn nicht schon einer läuft.
- Nie einen zweiten Moonlight-Stream gegen seinen Sunshine-Host starten, ohne zu fragen.
- JDK: Android-Studio-JBR (`C:\Program Files\Android\Android Studio\jbr`), SDK `%LOCALAPPDATA%\Android\Sdk`; NDK und
  Plattform installiert AGP selbst.
- Release-Schlüssel: `C:\Users\mpp\.selfframed\keys\moonlight-release.jks` (+ `.properties` mit Passwort); lokal
  verdrahtet über `sf-signing.properties` (gitignored). Nie committen, nie ausgeben.
- Releases: `powershell -File tools\sf\release-local.ps1 -DryRun`, dann ohne `-DryRun` (Zustimmung siehe unten).
  Der Schlüssel bleibt lokal (Entscheidung 12.10.2026); die App aktualisiert sich selbst über GitHub-Releases
  (`docs/selfframed/RELEASE.md`).

## Git und GitHub

- Repo: https://github.com/SelfFramed/moonlight-android (öffentlicher Fork, Standardbranch `main`, Issues und Actions an;
  angelegt 12.10.2026).
- Remotes: `upstream` = moonlight-stream (Push absichtlich gesperrt), `origin` =
  `git@github-selfframed:SelfFramed/moonlight-android.git` (SSH-Alias des Kontos **SelfFramed**; `git push` geht direkt).
- gh-CLI: aktives Konto ist `krausality` und bleibt es (andere Projekte hängen daran). `SelfFramed` ist als zweites Konto
  angemeldet. Für GitHub-API-Aufrufe im Namen von SelfFramed pro Befehl
  `GH_TOKEN=$(gh auth token --user SelfFramed) gh … --repo SelfFramed/moonlight-android` verwenden, nie `gh auth switch`
  dauerhaft umstellen.
- Branches: `master` = Upstream-Spiegel (nur Fast-Forward), `main` = Fork. Upstream-Releases per Merge
  (`tools/sf/sync-upstream.sh`), nie per Rebase.
- **Pushen, Taggen und Veröffentlichen nur mit Martins ausdrücklicher Zustimmung.** Dauerhaft erteilt (13.10.2026):
  Korrekturen, die auf Martins Rückmeldungen zurückgehen, veröffentlicht Claude direkt (nach Tests und Probelauf) und
  sagt es danach. Größere neue Funktionen weiterhin vorher abstimmen. Nichts auf sein Telefon installieren ohne
  Zustimmung.

## Prüfen

- JVM-Tests: `testNonRootDebugUnitTest` (Kurven gegen das Original-JS, LUT, Presets, Import, Audio-Puffer samt
  Zeitsimulation `JitterBufferSimTest`).
- Audio-Puffer ohne Stream (nur Debug-Build): `adb shell am start -n <appId>/com.limelight.selfframed.audio.AudioBufferTestActivity --ei amp 0`
  (Log-Tag `SfAudioTest`). Im Emulator nur mit Audio und stummgeschaltet (`-no-audio` hat kein Ausgabegerät); `amp 0`
  ist ein stummer Testton.
- Akkuwarnung ohne echten Akku: Testbild öffnen, dann `adb shell dumpsys battery unplug` / `set status 3` /
  `set level <n>` (Laden: `set ac 1`, `set status 2`), am Ende `dumpsys battery reset`. Emulator mit `-memory 4096`
  starten; mit 2 GB lagert er aus.
- GPU-Stufe ohne Stream: `GradingTestPatternActivity` (Testbild); auf Emulator oder Gerät per
  `adb shell am start -n <appId>/com.limelight.selfframed.grading.GradingTestPatternActivity`.
- GPU-Stufe pixelgenau (Emulator): `tools/sf/verify-gpu-stage.py`. Emulator-Vorgehen und Fallen: `BUILD.md` §8.
- Update-Funktion lokal: `RELEASE.md`, „Update-Funktion lokal testen“ (`tools/sf/update-test-server.py`).
- Mikrofon zum PC:
  - Protokolltests PC: `python -m unittest discover -s tools/sf/mic -p "test_*.py"`.
  - Ende-zu-Ende ohne Telefon: Empfänger läuft (Autostart), dann `python tools/sf/mic/send-test-tone.py` und parallel
    `python tools/sf/mic/measure-virtual-mic.py`.
  - Android-Seite im Emulator (mit Audio, stumm): `pm grant … RECORD_AUDIO`, dann `MicSendTestActivity`. Den Code nie
    ausgeben; per `$(head -1 ~/.selfframed/mic/pairing.txt)` übergeben.
  - Der Empfänger ist eine PC-Komponente (`tools/sf/mic/receiver.py`). Nach Änderungen neu starten:
    `install-autostart.ps1 -Remove`, dann `-StartNow`.
- Vor jeder Freigabe: `docs/selfframed/TESTPROTOKOLL.md` am echten Gerät. Ergebnisse dort eintragen; Ungeprüftes als
  offen markieren.

## Werkzeug-Fallen auf diesem PC

- Python-Patchskripte mit Windows-Pfaden nie als Bash-Heredoc schreiben. Backslashes werden zu Escapes: `\U` bricht ab,
  `\a` wird still zum Steuerzeichen. Stattdessen das Skript mit dem Write-Werkzeug in den Scratchpad schreiben und
  ausführen; im Code Raw-Strings oder `chr(92)` verwenden. Ersetzungen mit `assert s.count(old) == 1` absichern.
- PowerShell 5.1: `>` zerstört Binärdateien (PNG; dafür Git Bash). Fehlerausgaben nativer Programme werden zu
  Fehlerobjekten; in Skripten deshalb `$ErrorActionPreference = 'Continue'` und `$LASTEXITCODE` prüfen.
- Keine rekursiven Dateisuchen über `%LOCALAPPDATA%` oder `%ProgramData%`: Sie dauern Minuten und belasten die Platte,
  während Martin streamt. Gezielt suchen.
- Port 8765 ist von einem anderen Dienst belegt; Testserver laufen auf 47911.
- Das Sunshine-Log wirkt im Explorer leer (0 Byte, weil geöffnet): `tools/sf/diag/sunshine-log.ps1` liest es.
- adb immer mit `-s emulator-5554`; nie Martins Telefon ansprechen ohne Zustimmung.

## Arbeitsweise mit Martin

Bei größeren Aufträgen zuerst den Intent erschließen, steelmannen und als Aufgabenstellung ausschreiben (sichtbar im
Ergebnis), dann ausführen. Er schreibt verdichtet; seine Korrekturen sind maßgeblich. Konfidenzen ehrlich markieren.
Seine Vorgaben sind Impulse, keine Spezifikation: begründet abweichen, wenn es seinem Ziel besser dient, und die
Abweichung nennen. Bei Rückmeldungen vom Gerät zuerst klären, welche Version er hat (`DIAGNOSE.md` §1).
