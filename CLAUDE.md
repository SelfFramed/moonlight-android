# CLAUDE.md: SelfFramed-Fork von Moonlight Android

Dieses Repo ist Martins Fork von `moonlight-stream/moonlight-android` für sein E-Ink-Lapdock: Moto G100 als
Stream-Client, 13,3″-E-Ink-Panel (1600×1200, 16 Graustufen) am Glider-Board. Der Fork fügt eine GPU-Bildstufe hinzu
(Gradationskurven, Presets, Kürzel, Bedienfeld) und soll dauerhaft mit Upstream zusammenführbar bleiben. Claude pflegt
ihn: Martins Wünsche einbauen, Upstream-Releases übernehmen.

## Zuerst lesen

1. `docs/selfframed/AUFGABENSTELLUNG.md`: Auftrag, Steelman, Randbedingungen (verbindlich)
2. `docs/selfframed/ARCHITEKTUR.md`: Pipeline, Threads, Lebenszyklus
3. `docs/selfframed/PATCHES.md`: jeder Eingriff in Upstream-Dateien mit Absicht
4. Je nach Aufgabe: `UPSTREAM_SYNC.md`, `BUILD.md`, `RELEASE.md`, `BEDIENUNG.md`, `PRESETS.md`, `TESTPROTOKOLL.md`,
   `ROADMAP.md`, `CHANGELOG.md` (alle in `docs/selfframed/`)

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
- **Pushen, Taggen und Veröffentlichen nur mit Martins ausdrücklicher Zustimmung.** Nichts auf sein Telefon installieren
  ohne Zustimmung.

## Prüfen

- JVM-Tests: `testNonRootDebugUnitTest` (Kurven gegen das Original-JS, LUT, Presets, Import).
- GPU-Stufe ohne Stream: `GradingTestPatternActivity` (Testbild); auf Emulator oder Gerät per
  `adb shell am start -n <appId>/com.limelight.selfframed.grading.GradingTestPatternActivity`.
- Vor jeder Freigabe: `docs/selfframed/TESTPROTOKOLL.md` am echten Gerät. Ergebnisse dort eintragen; Ungeprüftes als
  offen markieren.

## Arbeitsweise mit Martin

Bei größeren Aufträgen zuerst den Intent erschließen, steelmannen und als Aufgabenstellung ausschreiben (sichtbar im
Ergebnis), dann ausführen. Er schreibt verdichtet; seine Korrekturen sind maßgeblich. Konfidenzen ehrlich markieren.
