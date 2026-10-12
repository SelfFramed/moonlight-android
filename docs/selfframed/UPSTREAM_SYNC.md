# Upstream-Sync (Runbook)

Stand: 12.10.2026. Dieses Runbook beschreibt, wie eine neue Version von Moonlight Android (`moonlight-stream/moonlight-android`)
in den SelfFramed-Fork übernommen wird. Es richtet sich an Claude; Martin muss nur an den markierten Stellen zustimmen
(Push, Veröffentlichung, Installation auf dem Telefon).

Werkzeuge: `tools/sf/sync-upstream.sh` (lokaler Ablauf), `tools/sf/check-hooks.sh` mit `tools/sf/hooks.txt` (Prüfung der Eingriffe),
`.github/workflows/sf-upstream-watch.yml` (Upstream-Wächter), `tools/sf/build-lowprio.ps1` (Build auf Martins PC).

---

## 1. Grundsätze

| Begriff | Bedeutung |
|---|---|
| `upstream` | Remote `https://github.com/moonlight-stream/moonlight-android.git`; nur Fetch, die Push-URL ist absichtlich ungültig |
| `origin` | Remote `git@github-selfframed:SelfFramed/moonlight-android.git` (SSH-Alias für das Konto `SelfFramed`) |
| `master` | Spiegel von `upstream/master`. Nur Fast-Forward, nie ein Fork-Commit |
| `main` | Fork-Branch und Standardbranch auf GitHub. Alle SelfFramed-Änderungen liegen hier |
| `sync/upstream-<tag>` | Arbeitsbranch für genau eine Übernahme, z. B. `sync/upstream-v12.3` |

- Übernommen werden nur **Upstream-Release-Tags** nach dem Muster `^v[0-9]+(\.[0-9]+)*$` (z. B. `v12.3`, `v12.3.1`).
  Zwischenstände von `upstream/master` werden nicht übernommen.
- Fork-Tags heißen `v<upstream>-sf.<n>` (z. B. `v12.2-sf.1`). Sie passen nicht zum Muster und werden nie gemergt.
- Übernahme per **Merge** (`git merge --no-ff <tag>`), nie per Rebase. Danach ist der Upstream-Tag ein Vorfahre von `main`.
  Daran erkennen Wächter und Skript, dass die Version erledigt ist.
- Sync-PRs werden auf GitHub mit **„Create a merge commit“** gemergt. Squash oder Rebase zerstören die Vorfahren-Beziehung;
  der Wächter würde dieselbe Version dann jede Woche erneut anbieten.
- Eingriffe in Upstream-Dateien sind mit `[SelfFramed]` markiert und in `docs/selfframed/PATCHES.md` beschrieben
  (Datei, Stelle, Absicht). `tools/sf/hooks.txt` hält je Datei die Mindestzahl markierter Zeilen fest.

## 2. Auslöser

1. **Wächter-PR** „Upstream vX in main übernehmen“: Der Merge lief ohne Konflikte. Die Tabelle im PR zeigt das Ergebnis von
   Hook-Prüfung, Build und Unit-Tests. Weiter mit Abschnitt 4.
2. **Wächter-Issue** „Upstream vX: Merge-Konflikte“: Der Merge hatte Konflikte und wurde abgebrochen; es gibt keinen Branch.
   Weiter mit Abschnitt 5.
3. **Martin fragt** (oder will eine bestimmte Version): Abschnitt 5. Alternativ den Wächter von Hand starten:
   GitHub → Actions → „SelfFramed Upstream-Waechter“ → „Run workflow“, optional mit Tag.

Der Wächter läuft montags um 05:17 UTC. Er
- führt `master` per Fast-Forward auf `upstream/master` nach (nie mit Force),
- bestimmt den neuesten Upstream-Release-Tag (Versionssortierung),
- hört still auf, wenn der Tag schon in `main` steckt oder Branch bzw. PR dafür schon existieren,
- merged sonst auf `sync/upstream-<tag>`, setzt `sf.revision` auf 1, prüft die Hooks, baut und testet, pusht den Branch und öffnet einen PR,
- öffnet bei Konflikten ein Issue oder aktualisiert das vorhandene (es entsteht kein zweites).

Der Wächter-PR löst keinen eigenen CI-Lauf aus: Pushes und PRs mit `GITHUB_TOKEN` starten keine weiteren Workflows.
Deshalb baut und testet der Wächter selbst. Ein erneuter Build lässt sich über Actions → „SelfFramed Build“ → „Run workflow“
auf dem Sync-Branch anstoßen.

## 3. Einmalige Einrichtung

### Lokal (Martins PC, `C:\GITHUB\moonlight-android`)

```bash
git remote -v
# falls upstream fehlt:
git remote add upstream https://github.com/moonlight-stream/moonlight-android.git
git remote set-url --push upstream DISABLED_do_not_push_to_upstream
```

Die gh-CLI auf diesem PC ist als `krausality` angemeldet, nicht als `SelfFramed`. Wegen des Remotes `upstream` wählt gh ohne
Angabe außerdem das Upstream-Repository als Ziel. Deshalb bei jedem gh-Befehl `--repo SelfFramed/moonlight-android` angeben.

### GitHub (Martin, angemeldet als `SelfFramed`)

1. Fork von `moonlight-stream/moonlight-android` anlegen; Branch `main` pushen und unter Settings → General → Default branch
   als Standardbranch setzen. Nur dann laufen die zeitgesteuerten Workflows.
2. Actions-Tab öffnen und Workflows freigeben, falls GitHub beim Fork danach fragt.
3. Settings → Actions → General → Workflow permissions: **„Allow GitHub Actions to create and approve pull requests“**
   aktivieren. Ohne diese Einstellung kann der Wächter keinen PR öffnen.
4. Settings → General → Features: **Issues** aktivieren (bei Forks standardmäßig aus). Ohne Issues schlägt die Konfliktmeldung fehl.
5. Signatur-Secrets nach `RELEASE.md` anlegen (für den Wächter nicht nötig).

GitHub deaktiviert zeitgesteuerte Workflows in öffentlichen Repositories nach 60 Tagen ohne Aktivität. Dann den Wächter im
Actions-Tab wieder aktivieren.

## 4. Ablauf A: Der Wächter hat einen PR geöffnet

1. Tabelle im PR lesen. Sind Hook-Prüfung oder Build fehlgeschlagen, den Branch lokal holen und reparieren:
   ```bash
   git fetch origin
   git switch --track origin/sync/upstream-vX
   git submodule update --init --recursive
   ```
   Fehlende Hooks nach Abschnitt 6 neu einsetzen, committen, dann `bash tools/sf/sync-upstream.sh --continue`.
   Das Skript prüft erneut und gibt die nächsten Schritte aus.
2. Upstream-Änderungen lesen (Liste im PR). Für eingehängte Dateien gezielt:
   ```bash
   git diff "$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-sf.*' origin/main)" vX -- <datei>
   ```
   Betreffen sie Decoder, Surface oder Frame-Pacing: Abschnitt 7.
3. Prüfliste aus Abschnitt 8 abarbeiten. Das Debug-APK aus dem Wächter-Lauf (Artefakt) taugt für einen Schnelltest;
   für den Gerätetest siehe `BUILD.md` (Installation).
4. `PATCHES.md` und `CHANGELOG.md` auf dem Branch nachführen; nach Martins Zustimmung pushen.
5. PR mit „Create a merge commit“ mergen, Branch löschen.
6. Release nach `RELEASE.md` (Tag `v<upstream>-sf.1`).

## 5. Ablauf B: Konflikte oder manuelle Übernahme (lokal)

```bash
git switch main
git pull --ff-only origin main
git status                                   # muss sauber sein
bash tools/sf/sync-upstream.sh               # neuester Upstream-Release-Tag
bash tools/sf/sync-upstream.sh v12.3         # oder ein bestimmter Tag
```

Das Skript
- prüft: sauberer Arbeitsbaum, Branch `main`, Remote `upstream` vorhanden (sonst nennt es den Befehl), `main` nicht hinter `origin/main`;
- holt `upstream/master` und den Tag; endet mit „Nothing to do“, wenn der Tag schon in `main` steckt;
- nennt die Zahl der Upstream-Commits und die eingehängten Dateien (aus `hooks.txt`), die Upstream geändert hat;
- legt `sync/upstream-<tag>` an und merged mit `--no-ff` und der Nachricht „Merge upstream <tag> into SelfFramed main“;
- bei Erfolg: Hook-Prüfung, `sf.revision` auf 1 (eigener Commit), Ausgabe der nächsten Schritte;
- pusht nie.

Exit-Codes: 0 = fertig oder nichts zu tun, 1 = Fehler, 3 = Merge mit Konflikten angehalten.

**Bei Konflikten** zeigt das Skript je Datei, ob sie in `PATCHES.md` und `hooks.txt` vorkommt und wie viele `[SelfFramed]`-Zeilen
sie auf `main` hat. Dann:

1. Jede Datei nach Abschnitt 6 lösen. Hilfen:
   `git diff <datei>` (beide Seiten), `git show :2:<datei>` (Stand `main`), `git show :3:<datei>` (Stand Upstream).
2. Gelöste Dateien mit `git add <datei>` vormerken. Nicht selbst committen.
3. `bash tools/sf/sync-upstream.sh --continue`. Das Skript verweigert den Abschluss, solange ungelöste Dateien, nicht vorgemerkte
   Änderungen oder Konfliktmarker (`<<<<<<<`, `>>>>>>>`, `|||||||`) übrig sind oder die Hook-Prüfung scheitert. Sonst erstellt es den
   Merge-Commit (mit Liste der gelösten Dateien), setzt `sf.revision` auf 1 und gibt die nächsten Schritte aus.
4. Abbrechen: `bash tools/sf/sync-upstream.sh --abort` (bricht den Merge ab, wechselt auf `main`, löscht den Branch, wenn er
   keine eigenen Commits hat).

Danach Prüfliste (Abschnitt 8), Doku nachführen und integrieren. Pushen nur mit Martins Zustimmung:

```bash
git push -u origin sync/upstream-vX
gh pr create --repo SelfFramed/moonlight-android --base main --head sync/upstream-vX
#   im PR-Text: "Closes #<Issue-Nummer>", falls ein Wächter-Issue existiert
# oder ohne PR:
git switch main && git merge --ff-only sync/upstream-vX && git push origin main
```

## 6. Konfliktregeln

1. **Upstream gewinnt überall außerhalb der `[SelfFramed]`-Stellen.** Upstream-Code wird übernommen, wie er ist. Kein Festhalten
   am alten Upstream-Code, nur weil der Hook dagegen geschrieben wurde. Keine Nebenbei-Verbesserungen an Upstream-Code.
2. **Innerhalb der `[SelfFramed]`-Stellen wird nach Absicht neu eingesetzt, nicht zeilengetreu.** Die Absicht steht in `PATCHES.md`.
   Hat Upstream die Stelle umgebaut, die gleichwertige neue Stelle suchen und den Eingriff dort so klein wie möglich einsetzen.
   Logik gehört nach `com.limelight.selfframed.*`; in der Upstream-Datei bleibt nur der Aufruf.
3. **Jeder Eingriff bleibt markiert:** Java `// [SelfFramed] …`, XML `<!-- [SelfFramed] … -->`, Gradle `// [SelfFramed] …`.
   Zahl der markierten Zeilen und `hooks.txt` müssen zusammenpassen.
4. **Fork-eigene Dateien** (`docs/selfframed/`, `tools/sf/`, `.github/workflows/sf-*`, `CLAUDE.md`, `com/limelight/selfframed/**`,
   `sf_*`-Ressourcen, `app/selfframed.*`) kennt Upstream nicht; dort sind keine Konflikte zu erwarten. Legt Upstream eine Datei
   gleichen Namens an, wird die Fork-Datei umbenannt.
5. **Löscht oder verschiebt Upstream eine eingehängte Datei,** wandert der Eingriff an die neue Stelle; `hooks.txt` und `PATCHES.md`
   werden angepasst.
6. **Ein Eingriff wird nie stillschweigend weggelassen.** Lässt sich eine Funktion nicht mehr sinnvoll einhängen, wird Martin gefragt;
   ein bewusst aufgegebener Eingriff wird in `PATCHES.md` als entfernt vermerkt.
7. **Submodule** (`moonlight-common-c` mit `enet` und `nanors`) ändert der Fork nie. Ihre Zeiger kommen deshalb konfliktfrei mit Upstream.
   Nach dem Merge: `git submodule update --init --recursive`.
8. **Texte:** Fork-Texte stehen in `sf_strings.xml`; Upstream-Übersetzungen werden unverändert übernommen.

### Typische Konfliktstellen

Maßgeblich ist `PATCHES.md`; die Tabelle nennt die Stellen, an denen Upstream erfahrungsgemäß oft ändert.

| Datei und Stelle | Absicht des Eingriffs (laut Aufgabenstellung) | Worauf achten |
|---|---|---|
| `Game.java`, `handleSpecialKeys()` und Tasten-Callbacks | Fork-Kürzel Strg+Alt+Shift+Taste; bei offenem Bedienfeld keine Bedien-Tasten an den Host | Upstream ergänzt eigene Kürzel und ändert die Tastenverarbeitung. Prüfen, dass kein Upstream-Kürzel verdeckt wird |
| `Game.java`, `surfaceCreated()` / `surfaceChanged()` / `surfaceDestroyed()` | Decoder bekommt die Eingangs-Surface der GL-Stufe statt der Surface der `SurfaceView` | Reihenfolge von Start und Stopp, HDR-Umschaltung, Größenänderungen |
| `MediaCodecDecoderRenderer.java`, `configureAndStartDecoder()` und `cleanup()` | Ausgabe-Surface des Decoders an die GL-Stufe; Freigabe beim Ende | Änderungen an `configure()`, Low-Latency-Optionen, `releaseOutputBuffer`, Frame-Pacing (Abschnitt 7) |
| `res/xml/preferences.xml` | Hauptschalter und Einstieg in die Fork-Einstellungen | Upstream sortiert Kategorien um oder fügt Einträge ein; der Fork-Eintrag bleibt an seinem Platz |
| `AndroidManifest.xml` | Fork-Activities und -Komponenten (z. B. Dateizugriff für Preset-Export) | Neue Berechtigungen, `exported`-Attribute, Ziel-SDK-Änderungen |
| `app/build.gradle` | eine Zeile `apply from: 'selfframed.gradle'` am Dateiende | Upstream ändert Flavors, `applicationId`-Logik oder Build-Typen; dann `app/selfframed.gradle` anpassen |

## 7. Upstream-Änderungen an der Bildausgabe

Die Bildpipeline des Forks hängt zwischen Decoder und Anzeige (Decoder → SurfaceTexture → GL-Shader → Bildschirm). Änderungen
von Upstream in diesem Bereich können gegen die Annahmen des Forks laufen, auch wenn der Merge konfliktfrei war.

Anzeichen: Änderungen in `binding/video/MediaCodecDecoderRenderer.java`, in den Surface-Callbacks von `Game.java`, in `ui/StreamView.java`,
an Frame-Pacing-Optionen (Einstellungen und Choreographer-Logik), an HDR-Behandlung, an `decoder-errata.txt` oder an der Wahl der
Ausgabe-Surface.

Vorgehen:

1. Den Upstream-Diff dieser Dateien vollständig lesen:
   ```bash
   alt=$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-sf.*' main)
   git diff "$alt" vX -- app/src/main/java/com/limelight/binding/video/ \
       app/src/main/java/com/limelight/Game.java app/src/main/java/com/limelight/ui/ app/src/main/res/xml/preferences.xml
   ```
2. `docs/selfframed/ARCHITEKTUR.md` erneut lesen und jede Annahme gegen den neuen Code prüfen: Ausgabe-Surface, Zeitstempel
   werden durchgereicht, immer das neueste Bild, kein zusätzliches Kopieren, Rückfall auf den Originalpfad bei GL-Fehler oder HDR.
3. Frame-Pacing: Gibt Upstream Bilder anders frei (z. B. `releaseOutputBuffer` mit Zeitstempel, neue Pacing-Modi), prüfen, dass die
   GL-Stufe weiterhin jedes Bild mit korrektem Zeitstempel bekommt und keine Latenz hinzukommt.
4. Mit Hauptschalter **aus** muss sich die App exakt wie Upstream verhalten. Beide Zustände am Gerät testen.
5. Den Gerätetest in `docs/selfframed/TESTPROTOKOLL.md` vollständig durchführen, nicht nur stichprobenartig. Ergebnis und
   Auffälligkeiten (Latenz, Ruckeln, Schwarzbild) im PR festhalten. Ist das Telefon nicht erreichbar, bleibt der PR offen,
   bis Martin getestet hat.

## 8. Prüfliste vor dem Merge nach `main`

- [ ] `bash tools/sf/check-hooks.sh` meldet OK; Warnungen sind erklärt oder behoben
- [ ] Keine Konfliktmarker: `git grep -n -E '^(<<<<<<<|>>>>>>>)( |$)'` ist leer
- [ ] `git submodule update --init --recursive` ausgeführt
- [ ] Build und Unit-Tests grün: `powershell -ExecutionPolicy Bypass -File tools/sf/build-lowprio.ps1 -Wait` (oder CI)
- [ ] Bei Änderungen an der Bildausgabe: Abschnitt 7 erledigt
- [ ] Gerätetest nach `TESTPROTOKOLL.md` (mindestens Schnelltest; vollständig bei Änderungen an der Bildausgabe)
- [ ] `PATCHES.md` nachgeführt (geänderte Stellen, neue Methodennamen), `hooks.txt` passt
- [ ] `CHANGELOG.md`: Eintrag „Upstream vX übernommen“ mit den für Martin relevanten Upstream-Änderungen
- [ ] `sf.revision` steht auf 1
- [ ] Merge als Merge-Commit (kein Squash, kein Rebase)

## 9. Versionierung

Nach einer Upstream-Übernahme gilt `versionName = <upstream versionName>-sf.1` und `versionCode = <upstream versionCode> × 100 + 1`.
Skript und Wächter setzen `sf.revision` in `app/selfframed.properties` dafür automatisch auf 1, in einem eigenen Commit
„Reset sf.revision to 1 for upstream vX“. Da Upstream den versionCode mit jeder Version erhöht, steigt auch der Fork-versionCode
(Beispiel: 31502 → 31601). Das Release folgt `RELEASE.md`.

## 10. Sonderfälle

- **`master` weicht von Upstream ab:** Der Wächter warnt und lässt `master` unverändert. Ursache prüfen
  (`git log upstream/master..origin/master`). Fork-Commits gehören nach `main`. `master` darf nur mit Martins ausdrücklicher
  Zustimmung per Force zurückgesetzt werden.
- **Upstream ändert `.github/workflows/`:** `GITHUB_TOKEN` darf solche Commits nicht pushen. Spiegel-Push und Sync-Branch-Push
  schlagen dann fehl (Warnung bzw. Fehler im Lauf). Übernahme lokal nach Abschnitt 5; Push mit Martins Zugang.
  Prüfen, ob neue Upstream-Workflows im Fork laufen sollen. Wenn nicht, im Actions-Tab deaktivieren, statt die Dateien zu ändern.
- **Wächter-PR ohne Merge geschlossen:** Der Wächter bietet die Version nicht erneut an. Neuer Versuch lokal nach Abschnitt 5
  (vorher den alten Branch auf `origin` löschen, falls vorhanden).
- **Mehrere Upstream-Versionen auf einmal:** Nur den neuesten Tag mergen; die Änderungsliste umfasst alle dazwischen.
- **Upstream ändert die Build-Umgebung** (AGP, Gradle, NDK, compileSdk, nötige JDK-Version): `app/selfframed.gradle` prüfen,
  `BUILD.md` und bei Bedarf die JDK-Version in `.github/workflows/sf-build.yml` und `sf-upstream-watch.yml` anpassen.
- **Upstream-Tag liegt nicht auf `master`** (Hotfix-Branch): Der Tag wird trotzdem gemergt; `master` bleibt reiner Spiegel.
