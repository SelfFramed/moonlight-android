# Release und Updates

Stand: 12.10.2026. Entschieden am 12.10.2026 (Martin: „ok“): Der Signaturschlüssel bleibt **nur auf Martins PC**. Releases
werden dort gebaut, signiert und auf GitHub veröffentlicht. Das Telefon aktualisiert sich über die **eingebaute
Update-Funktion** der App, ohne Extra-App. Veröffentlichen nur mit Martins Zustimmung; für Korrekturen nach seinen
Rückmeldungen hat er sie dauerhaft erteilt (13.10.2026).

## 1. Versionsschema

| Größe | Bildung | Beispiel |
|---|---|---|
| `versionName` | `<upstream versionName>-sf.<n>` | `12.2-sf.1` |
| `versionCode` | `<upstream versionCode> × 100 + n` | `315 × 100 + 1 = 31501` |
| Tag | `v` + versionName | `v12.2-sf.1` |
| `n` | `sf.revision` in `app/selfframed.properties`, 1 bis 99 | `sf.revision=1` |

- Nach einer Upstream-Übernahme ist `n = 1` (Sync-Skript und Wächter setzen das zurück, siehe `UPSTREAM_SYNC.md`).
- Jedes weitere Release auf derselben Upstream-Basis erhöht `n` um 1 (eigener Commit).
- Ein veröffentlichter Tag wird nie verschoben oder wiederverwendet. Android installiert nie eine kleinere `versionCode`
  über eine größere.

## 2. Ablauf (Claude, auf Martins PC)

1. `CHANGELOG.md`: Abschnitt `## <version>` mit den Änderungen. Der Text landet automatisch in den Release-Notizen.
2. Falls nötig `sf.revision` erhöhen und committen.
3. Probelauf: `powershell -File tools\sf\release-local.ps1 -DryRun`. Das Skript prüft Branch und Sauberkeit, baut mit
   niedriger Priorität (Unit-Tests und signiertes Release) und prüft Version und Signatur-Zertifikat. Es legt
   `build\sf-release\moonlight-eink-<version>-nonRoot-release.apk` und die Notizen ab.
4. Veröffentlichen (Korrekturen nach Martins Rückmeldungen direkt, Neues nach Absprache): `powershell -File tools\sf\release-local.ps1` (`-Prerelease` für
   Vorabversionen). Das Skript taggt, pusht `main` und den Tag und legt das GitHub-Release als Konto **SelfFramed** an
   (`GH_TOKEN` nur für diesen Aufruf; das aktive gh-Konto bleibt krausality).
5. Die CI baut den Tag noch einmal und prüft Tag = `v` + versionName. Ein eigenes Release legt sie nicht an, solange
   keine Signatur-Secrets hinterlegt sind.

## 3. Updates auf dem Telefon

- **Erstinstallation:** auf dem Telefon `https://github.com/SelfFramed/moonlight-android/releases` öffnen und die APK
  `moonlight-eink-<version>-nonRoot-release.apk` antippen (oder per adb, siehe `BUILD.md`). Einmalig mit Sunshine koppeln.
- **Danach:** Die App prüft beim Öffnen des Startbildschirms höchstens alle 6 Stunden
  `https://api.github.com/repos/SelfFramed/moonlight-android/releases/latest`. Ist ein neueres Release da, fragt sie:
  „Update verfügbar – Installieren / Später / Diese Version überspringen“.
  - Beim ersten Mal führt sie zu „Aus dieser Quelle zulassen“ (Android-Einstellung, einmalig). Zurück in der App geht es
    von selbst weiter.
  - Download mit Fortschritt. Dann prüft die App SHA-256 (von GitHub gemeldet), Paketname, höhere Version und das
    Signatur-Zertifikat (muss zur installierten App passen). Danach übergibt sie an Androids Installer, und Android fragt
    noch einmal: „Update“.
  - Auf Geräten mit Google-Diensten kann **Google Play Protect** fragen („App-Prüfung empfohlen“). Unter „Weitere Details“
    → „Ohne Scan installieren“, oder scannen lassen.
  - Bei Abbruch (z. B. Funkloch) bietet die App „Erneut versuchen“ an.
- Von Hand: Einstellungen → **Updates (SelfFramed)** → „Jetzt nach Updates suchen“. Dort lässt sich die automatische Suche
  auch abschalten.
- Vorabversionen (`-Prerelease`) und Entwürfe bietet die App nie an, nur das neueste reguläre Release.
- Debug-Builds (`com.selfframed.moonlight.debug`) suchen nicht nach Updates.

Geprüft am 12.10.2026 im Emulator (API 36): 12.2-sf.1 → 12.2-sf.2 komplett über die eingebaute Funktion, gegen einen
lokalen Testserver mit GitHub-gleicher Release-Beschreibung (`-Psf.updateApi=…`, siehe `app/selfframed.gradle`).
Gerät: Martin hat 12.2-sf.2 und 12.2-sf.3 so installiert. Welche Version er gerade hat: `DIAGNOSE.md` §1.

### Update-Funktion lokal testen (Emulator)

1. **Version A, älter:** Release-Build mit Test-Adresse. Debug-Builds suchen nicht nach Updates.
   `tools\sf\build-lowprio.ps1 -Tasks "assembleNonRootRelease -Psf.updateApi=http://10.0.2.2:47911/latest.json" -Wait`.
   Die APK in einen leeren Testordner kopieren.
2. **Version B, neuer, gleicher Schlüssel:** `sf.revision` lokal um 1 erhöhen (nicht committen) und normal
   `assembleNonRootRelease` bauen. Die APK als `moonlight-eink-<version>-nonRoot-release.apk` in denselben Ordner legen.
   Danach `sf.revision` zurücksetzen.
3. **Server starten:** `python -I tools/sf/update-test-server.py <Ordner>\moonlight-eink-<version>-nonRoot-release.apk`.
   Er schreibt `latest.json` und liefert auf 127.0.0.1:47911 mit Keep-Alive aus; der Emulator erreicht den PC als
   10.0.2.2. Port 8765 ist auf Martins PC belegt. Pythons Standard-Server (HTTP/1.0) bricht große Downloads im
   Emulator ab.
4. **Durchspielen:** A installieren (`adb -s emulator-5554 install …`), dann Einstellungen → Updates → „Jetzt nach
   Updates suchen“ → Installieren → einmalig „Aus dieser Quelle zulassen“ → Android bestätigt. Play Protect fragt
   eventuell nach; dann „Ohne Scan installieren“. Prüfen: `dumpsys package com.selfframed.moonlight | grep versionCode`.
5. **Aufräumen:** Server beenden (Strg+C), Testordner löschen, App im Emulator deinstallieren, Emulator beenden.

## 4. Signaturschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte App. Wer den Schlüssel
verliert, kann keine Updates mehr ausliefern: dann Neuinstallation und neue Kopplung.

| Was | Wo |
|---|---|
| Keystore | `C:\Users\mpp\.selfframed\keys\moonlight-release.jks` (PKCS12, RSA 4096, gültig bis 2126, Alias `selfframed-moonlight`), angelegt 12.10.2026 |
| Passwort | `C:\Users\mpp\.selfframed\keys\moonlight-release.properties` |
| Verdrahtung | `sf-signing.properties` im Repo-Wurzelordner (gitignoriert, Kopie der Datei oben) |
| Zertifikat SHA-256 | `c226922a62a12497f065d88e5f92a39f7743e4c851acf157e6e6405aa19c59df` (öffentlich, steht auch im Release-Skript) |

**Sicherung (eingerichtet 12.10.2026):** `C:\Users\mpp\.selfframed` steht in `restic-sources.txt` beider
`mpp backup`-Regime (Monthly und Quarterly; vorher `.bak_pre_selfframed_keys_<Zeit>` angelegt). Damit liegt der
Schlüssel AES-256-verschlüsselt und versioniert auf zwei Offline-Platten. Zusätzliche Verschlüsselung (`mpp encrypt`),
Post-Quanten-Verfahren oder ein Ausdruck sind bewusst nicht vorgesehen: Ein Verlust kostet nur Neuinstallation und
Neukopplung, ein Diebstahl allein ermöglicht kein Update aufs Telefon. Den Schlüssel nie ins Repo, in Issues, PRs oder
Chats geben.

Prüfen, ob ein APK mit diesem Schlüssel signiert ist:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs <apk>
```

Die Gradle-Seite (`app/selfframed.gradle`) sucht den Schlüssel in dieser Reihenfolge: Umgebungsvariablen
`SF_KEYSTORE_FILE`, `SF_KEYSTORE_PASSWORD`, `SF_KEY_ALIAS`, `SF_KEY_PASSWORD` → `sf-signing.properties` → unsigniert.

## 5. Spätere Alternative: Releases durch GitHub bauen lassen

Nur wenn Martin es ausdrücklich will. Dann kommen die vier Secrets ins Repo (Settings → Secrets and variables → Actions):
`SF_KEYSTORE_BASE64` (Base64 der `.jks`), `SF_KEYSTORE_PASSWORD`, `SF_KEY_ALIAS`, `SF_KEY_PASSWORD`. `sf-build.yml`
erzeugt dann bei jedem `v*-sf.*`-Tag selbst ein signiertes Release, ohne dass der PC laufen muss. Risiko: Wer das Konto
SelfFramed übernimmt, kann Updates ausstellen. Zwei-Faktor-Anmeldung ist dafür Pflicht.

Externe Update-Apps (z. B. Obtainium) funktionieren mit denselben Releases, werden aber nicht gebraucht.
