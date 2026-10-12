# Release

Stand: 12.10.2026. Wie eine Version von Moonlight E-Ink veröffentlicht wird, wie der Signaturschlüssel verwaltet wird und wie das
Telefon Updates automatisch bekommt. Veröffentlichen (Tag pushen) nur mit Martins Zustimmung.

---

## 1. Versionsschema

| Größe | Bildung | Beispiel |
|---|---|---|
| `versionName` | `<upstream versionName>-sf.<n>` | `12.2-sf.1` |
| `versionCode` | `<upstream versionCode> × 100 + n` | `315 × 100 + 1 = 31501` |
| Tag | `v` + versionName | `v12.2-sf.1` |
| `n` | `sf.revision` in `app/selfframed.properties`, 1 bis 99 | `sf.revision=1` |

Regeln:

- Nach einer Upstream-Übernahme ist `n = 1`; Sync-Skript und Wächter setzen den Wert automatisch zurück (`UPSTREAM_SYNC.md`).
- Jedes weitere Release auf derselben Upstream-Basis erhöht `n` um 1.
- Ein veröffentlichter Tag wird nie verschoben oder wiederverwendet.
- Der versionCode steigt immer. Android installiert keine kleinere Version über eine größere, Obtainium bietet sie nicht an.
- Fork-Tags (`v…-sf.…`) passen nie zum Muster der Upstream-Tags (`^v[0-9]+(\.[0-9]+)*$`) und werden deshalb nie als Upstream-Version gemergt.

## 2. Ablauf

Voraussetzungen: `main` ist sauber und aktuell, die CI auf `main` ist grün, der Gerätetest (`TESTPROTOKOLL.md`) ist bestanden,
`CHANGELOG.md` hat einen Eintrag für die Version, Martin hat zugestimmt.

1. **Nummer bestimmen.** Upstream-Basis und `n` ablesen:
   ```bash
   grep -E 'versionName "|sf.revision' app/build.gradle app/selfframed.properties
   git tag -l 'v*-sf.*'
   ```
   Existiert `v<upstream>-sf.<n>` schon, `sf.revision` in `app/selfframed.properties` um 1 erhöhen und committen:
   ```bash
   git commit -m "Release v12.2-sf.2" -- app/selfframed.properties
   ```
2. **Taggen** (annotiert):
   ```bash
   git tag -a v12.2-sf.2 -m "Moonlight E-Ink 12.2-sf.2"
   ```
3. **Pushen**, erst den Branch, dann den Tag:
   ```bash
   git push origin main
   git push origin v12.2-sf.2
   ```
4. **CI** („SelfFramed Build“) läuft für den Tag: Unit-Tests, Debug- und Release-Build. Sie prüft außerdem, dass der Tag `v` + versionName
   entspricht. Danach erzeugt sie das GitHub-Release „Moonlight E-Ink v12.2-sf.2“ mit dem APK `moonlight-eink-12.2-sf.2-nonRoot-release.apk`
   und einem Text, der auf `BEDIENUNG.md` verweist.
   - Ohne Signatur-Secrets hängt nur das Debug-APK am Release; dieses ist als Vorabversion markiert und für Obtainium unsichtbar.
5. **Prüfen:** Release-Seite öffnen, APK vorhanden, Text stimmt. Obtainium meldet das Update (Abschnitt 4).

Passt der Tag nicht zum versionName, bricht die CI ab, bevor ein Release entsteht. Dann den Tag lokal und auf GitHub löschen, korrigieren
und neu taggen:

```bash
git tag -d v12.2-sf.2
git push origin :refs/tags/v12.2-sf.2
```

Release erneut erzeugen, ohne neu zu taggen (z. B. nachdem die Secrets nachgetragen wurden): Actions → „SelfFramed Build“ →
„Run workflow“ → bei „Use workflow from“ den Tag wählen.

Lokaler Release-Build (optional, mit `sf-signing.properties`):

```powershell
.\tools\sf\build-lowprio.ps1 -Tasks "assembleNonRootRelease" -Wait
```

## 3. Signaturschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte App. Alle Releases werden deshalb
mit genau einem Schlüssel signiert, auf Dauer.

### Ablage

| Was | Wo |
|---|---|
| Keystore | `%USERPROFILE%\.selfframed\keys\moonlight-release.jks` (also `C:\Users\mpp\.selfframed\keys\moonlight-release.jks`), außerhalb des Repos. Pfad vorläufig, wird bei der Einrichtung bestätigt |
| Lokale Konfiguration | `sf-signing.properties` im Repo-Wurzelordner, gitignoriert (prüfen: `git check-ignore -v sf-signing.properties`) |
| CI | vier Repository-Secrets (siehe unten) |
| Passwörter | KeePassXC |

Die Gradle-Seite (`app/selfframed.gradle`) sucht den Schlüssel in dieser Reihenfolge:

1. Umgebungsvariablen `SF_KEYSTORE_FILE`, `SF_KEYSTORE_PASSWORD`, `SF_KEY_ALIAS`, `SF_KEY_PASSWORD` (so arbeitet die CI);
2. sonst `sf-signing.properties`;
3. sonst bleibt der Release-Build unsigniert.

`sf-signing.properties`:

```properties
storeFile=C:/Users/mpp/.selfframed/keys/moonlight-release.jks
storePassword=<Passwort>
keyAlias=selfframed-moonlight
keyPassword=<Passwort>
```

Im Pfad Schrägstriche `/` verwenden; ein Backslash ist in `.properties`-Dateien ein Escape-Zeichen. Relative Pfade gelten ab dem
Repo-Wurzelordner.

### Erzeugen (einmalig, durch Martin oder mit seiner Zustimmung)

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.selfframed\keys" | Out-Null
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
    -keystore "$env:USERPROFILE\.selfframed\keys\moonlight-release.jks" -storetype PKCS12 `
    -alias selfframed-moonlight -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=SelfFramed, O=SelfFramed"
```

keytool fragt das Passwort ab. Bei PKCS12 sind Keystore- und Schlüsselpasswort identisch; `keyPassword` bzw. `SF_KEY_PASSWORD` erhalten
denselben Wert wie `storePassword`. 10 000 Tage Gültigkeit reichen für die Lebensdauer der App.

Fingerabdruck notieren (zum späteren Abgleich):

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.selfframed\keys\moonlight-release.jks"
```

### Sichern

- KeePassXC-Eintrag „SelfFramed Moonlight Release-Schlüssel“: Passwort, Alias, Fingerabdruck (SHA-256), die `.jks`-Datei als Anhang.
- Eine zweite Kopie offline (z. B. die KeePassXC-Datenbank auf einem USB-Stick oder einer externen Platte), getrennt vom PC aufbewahrt.
- Die Sicherung prüfen: `keytool -list -v` auf die Kopie muss denselben SHA-256-Fingerabdruck zeigen wie ein veröffentlichtes APK
  (`%LOCALAPPDATA%\Android\Sdk\build-tools\<version>\apksigner.bat verify --print-certs <apk>`).
- Der Schlüssel kommt nie ins Git-Repo und nie in Issues, PRs oder Chats.

### GitHub-Secrets anlegen

GitHub → `SelfFramed/moonlight-android` → Settings → Secrets and variables → Actions → „New repository secret“:

| Secret | Inhalt |
|---|---|
| `SF_KEYSTORE_BASE64` | die `.jks`-Datei, Base64-kodiert |
| `SF_KEYSTORE_PASSWORD` | Keystore-Passwort |
| `SF_KEY_ALIAS` | `selfframed-moonlight` |
| `SF_KEY_PASSWORD` | Schlüsselpasswort (bei PKCS12 gleich dem Keystore-Passwort) |

Base64 in die Zwischenablage (PowerShell), danach die Zwischenablage leeren:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.selfframed\keys\moonlight-release.jks")) | Set-Clipboard
# einfügen als SF_KEYSTORE_BASE64, dann:
Set-Clipboard -Value ' '
```

Die gh-CLI auf Martins PC ist als `krausality` angemeldet; Secrets für `SelfFramed` deshalb über die Weboberfläche anlegen.
Kontrolle: Der nächste CI-Lauf auf `main` meldet „Signing secrets present“, und das Artefakt enthält ein `…-nonRoot-release.apk`.
Secrets stehen Workflows aus fremden Forks nicht zur Verfügung; deren PR-Builds bleiben unsigniert.

### Wenn der Schlüssel verloren geht

Ohne den alten Schlüssel kann kein Update mehr die installierte App ersetzen. Folgen und Vorgehen:

1. Neuen Schlüssel erzeugen und sichern (wie oben), die vier GitHub-Secrets und `sf-signing.properties` ersetzen.
2. Neues Release veröffentlichen; im Release-Text und in `CHANGELOG.md` auf den Schlüsselwechsel hinweisen.
3. Auf dem Telefon: Presets vorher exportieren, falls die App noch läuft (`BEDIENUNG.md`). Moonlight E-Ink deinstallieren, damit gehen
   Einstellungen, Presets und Host-Kopplungen verloren. Neues Release installieren, jeden Host einmal per PIN neu mit Sunshine
   koppeln (`BUILD.md`, Abschnitt 7), Presets aus der Exportdatei importieren.
4. In Obtainium den alten App-Eintrag entfernen und neu anlegen.

Darum Presets regelmäßig exportieren. Ist der Schlüssel nicht verloren, aber kompromittiert (der alte liegt noch vor), ist mit
`apksigner rotate` und APK Signature Scheme v3 auch ein Wechsel ohne Neuinstallation möglich; das wird dann gesondert geplant.

## 4. Automatische Updates mit Obtainium

Obtainium (Open Source, aus F-Droid oder von `github.com/ImranR98/Obtainium`) installiert und aktualisiert Apps direkt aus
GitHub-Releases.

Einrichtung auf dem Telefon:

1. Obtainium → „Add App“ → URL `https://github.com/SelfFramed/moonlight-android`.
2. „Filter APKs by regular expression“: `nonRoot-release\.apk$`
3. „Include prereleases“: aus (Releases ohne Signatur sind Vorabversionen und enthalten nur das Debug-APK).
4. Hinzufügen und installieren. Ist die Release-Variante schon per adb installiert, erkennt Obtainium sie an der applicationId
   `com.selfframed.moonlight`, sofern sie mit demselben Schlüssel signiert ist.

Die Menüpunkte können je nach Obtainium-Version und Sprache etwas anders heißen.

Versionserkennung: Der Tag (`v12.2-sf.2`) ist `v` + versionName der App (`12.2-sf.2`). Nach dem ersten Release mit `n ≥ 2` prüfen, dass
Obtainium das Update anbietet. Falls nicht, in den App-Einstellungen von Obtainium als „Version extraction regex“
`[0-9]+(\.[0-9]+)*-sf\.[0-9]+` eintragen.

Obtainium prüft im Hintergrund regelmäßig auf neue Releases; das GitHub-Limit ohne Token (60 Anfragen pro Stunde) reicht dafür.
Debug-APKs werden nie über Obtainium verteilt.
