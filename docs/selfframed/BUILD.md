# Build

Stand: 12.10.2026. Wie der SelfFramed-Fork von Moonlight Android gebaut, getestet und auf das Telefon gebracht wird.

---

## 1. Überblick

| Punkt | Wert |
|---|---|
| Gradle / AGP | Wrapper 9.7.1, Android Gradle Plugin 9.4.0 |
| SDK-Stände | compileSdk 37, targetSdk 36, minSdk 21 |
| NDK | 29.0.14206865 (installiert AGP bei Bedarf selbst) |
| Java | Quellniveau 17. Gradle läuft mit JDK 17 bis 21: lokal Android Studios JBR 21, in der CI Temurin 21 (Upstreams AppVeyor nutzt JDK 25) |
| Flavors | `nonRoot` (wird ausgeliefert), `root` (Altlast, maxSdk 25, wird nicht gebaut) |
| Native Teile | `app/src/main/jni/moonlight-core/moonlight-common-c` als Git-Submodul, darin `enet` und `nanors` |

| Gradle-Task | Zweck |
|---|---|
| `testNonRootDebugUnitTest` | JVM-Unit-Tests (`app/src/test/`) |
| `assembleNonRootDebug` | Debug-APK |
| `assembleNonRootRelease` | Release-APK (signiert, wenn ein Schlüssel konfiguriert ist; siehe `RELEASE.md`) |

| Variante | applicationId | Name auf dem Telefon |
|---|---|---|
| Debug | `com.selfframed.moonlight.debug` | Moonlight E-Ink (Debug) |
| Release | `com.selfframed.moonlight` | Moonlight E-Ink |

Beide Varianten lassen sich nebeneinander und neben dem offiziellen Moonlight (`com.limelight`) installieren.

## 2. Voraussetzungen auf Martins PC

- Repository: `C:\GITHUB\moonlight-android`
- Android Studio mit JBR unter `C:\Program Files\Android\Android Studio\jbr` (Java 21)
- Android SDK unter `%LOCALAPPDATA%\Android\Sdk` (`C:\Users\mpp\AppData\Local\Android\Sdk`). `local.properties` im Repo enthält
  `sdk.dir=C:/Users/mpp/AppData/Local/Android/Sdk`; die Datei ist gitignoriert.
- SDK-Lizenzen akzeptiert (einmalig, Android Studio → SDK Manager oder
  `"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses`). Dann installiert AGP beim ersten Build
  das NDK 29.0.14206865 und fehlende Plattformen selbst (nach `Sdk\ndk\29.0.14206865`, rund 1 GB).
- Submodule ausgecheckt:
  ```bash
  git submodule update --init --recursive
  ```

## 3. Bauen mit niedriger Priorität (Standard auf Martins PC)

Martins PC ist zugleich der Sunshine-Host, über den er streamt. Ein Build mit voller Priorität und allen Kernen bringt den Stream
zum Stocken. Deshalb wird lokal nur über `tools/sf/build-lowprio.ps1` gebaut:

```powershell
powershell -ExecutionPolicy Bypass -File C:\GITHUB\moonlight-android\tools\sf\build-lowprio.ps1 -Wait
```

| Parameter | Standard | Bedeutung |
|---|---|---|
| `-Tasks` | `"testNonRootDebugUnitTest assembleNonRootDebug"` | Gradle-Tasks (und einfache Optionen wie `--stacktrace`), durch Leerzeichen getrennt |
| `-Workers` | `4` | höchstens so viele Gradle-Worker (`org.gradle.workers.max`) |
| `-Wait` | aus | auf das Ende warten, die letzten 40 Logzeilen zeigen, mit dem Exit-Code von Gradle enden |

Beispiele:

```powershell
.\tools\sf\build-lowprio.ps1 -Wait
.\tools\sf\build-lowprio.ps1 -Tasks "assembleNonRootRelease" -Workers 2 -Wait
```

Was das Skript tut:

1. Es wählt `JAVA_HOME`: Es behält einen gesetzten Wert, wenn dieser auf Java 17 bis 21 zeigt, sonst nimmt es Android Studios JBR.
   `ANDROID_HOME` wird auf `%LOCALAPPDATA%\Android\Sdk` gesetzt, falls leer.
2. Es schreibt eine temporäre `.cmd`-Datei (CRLF) nach `%TEMP%`. Diese wartet etwa 3 Sekunden, setzt die Umgebung, wechselt ins Repo
   und ruft `gradlew.bat --no-daemon --priority=low -Dorg.gradle.workers.max=<n> <tasks>` auf.
3. Es startet `cmd.exe` minimiert und setzt den Prozess sofort auf **BelowNormal**. Die Wartezeit sorgt dafür, dass Gradle und alle
   Kindprozesse (javac, R8, ndk-build, clang) diese Priorität erben. Lässt sich die Priorität nicht senken, bricht es den Build ab.
4. Es schreibt das Log nach `build\sf-build.log` (im Repo, gitignoriert) und die PID nach `build\sf-build.pid`. Ein zweiter Build
   wird verweigert, solange der erste läuft.

Ohne `-Wait` kehrt das Skript sofort zurück:

```powershell
Get-Content -LiteralPath C:\GITHUB\moonlight-android\build\sf-build.log -Wait -Tail 30   # Log verfolgen
taskkill /T /F /PID <pid>                                                                  # Build abbrechen (ganzer Prozessbaum)
```

`--no-daemon` bedeutet: Nach dem Build bleibt kein Gradle-Prozess im Speicher. Der erste Build dauert lange (NDK, Abhängigkeiten);
spätere Builds nutzen den Cache unter `%USERPROFILE%\.gradle`.

### Direkt bauen (nur wenn der PC gerade nicht streamt)

Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon -Dorg.gradle.workers.max=4 testNonRootDebugUnitTest assembleNonRootDebug
```

In Android Studio: Build Variant `nonRootDebug` wählen.

## 4. Ergebnisse

| Was | Pfad |
|---|---|
| Debug-APK | `app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk` |
| Release-APK, signiert | `app/build/outputs/apk/nonRoot/release/app-nonRoot-release.apk` |
| Release-APK, unsigniert (kein Schlüssel konfiguriert; nicht installierbar) | `app/build/outputs/apk/nonRoot/release/app-nonRoot-release-unsigned.apk` |
| versionName und versionCode | `output-metadata.json` im jeweiligen APK-Ordner |
| Unit-Test-Bericht | `app/build/reports/tests/testNonRootDebugUnitTest/index.html` |
| Build-Log des Startskripts | `build/sf-build.log` |

## 5. CI-Build (GitHub Actions)

Workflow `.github/workflows/sf-build.yml` („SelfFramed Build“). Er läuft bei Pushes auf `main` und `sync/**`, bei PRs nach `main`,
bei Fork-Tags `v*-sf.*` und von Hand (Actions → „SelfFramed Build“ → „Run workflow“).

Ablauf: Checkout mit Submodulen → Temurin 21 → Gradle-Cache → SDK-Lizenzen akzeptieren → `testNonRootDebugUnitTest assembleNonRootDebug`
→ falls die Signatur-Secrets vorhanden sind, zusätzlich `assembleNonRootRelease` → APKs als Artefakt hochladen.

- Artefakt: `moonlight-eink-<versionName>-<commit>` mit `moonlight-eink-<versionName>-nonRoot-debug.apk` und, wenn signiert,
  `moonlight-eink-<versionName>-nonRoot-release.apk`. Download: Actions → Lauf → „Artifacts“ (ZIP), 30 Tage aufbewahrt.
- Schlagen Tests fehl, liegen die Testberichte als Artefakt `test-reports-<run>` am Lauf.
- Bei Tags erzeugt der Workflow zusätzlich das GitHub-Release (siehe `RELEASE.md`).
- Der Upstream-Wächter (`sf-upstream-watch.yml`) baut Sync-Branches selbst; siehe `UPSTREAM_SYNC.md`.

Debug-APKs aus der CI sind mit einem zufälligen Debug-Schlüssel des jeweiligen Runners signiert. Sie lassen sich nicht über ein
Debug-APK von Martins PC oder aus einem anderen CI-Lauf installieren (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); vorher muss die
Debug-App deinstalliert werden. Für den Alltag ist das signierte Release-APK gedacht.

## 6. Häufige Fehler

| Meldung oder Symptom | Ursache | Abhilfe |
|---|---|---|
| „Android Gradle plugin requires Java 17 to run“, „Unsupported class file major version“ | falsches JDK (z. B. Java 8 oder ein sehr neues JDK in `JAVA_HOME`) | Startskript nutzen (wählt die JBR) oder `JAVA_HOME` auf die JBR setzen |
| ndk-build findet Dateien unter `moonlight-common-c` nicht, leere Ordner `enet`/`nanors` | Submodule fehlen | `git submodule update --init --recursive` |
| „licences have not been accepted“, NDK oder Plattform wird nicht installiert | SDK-Lizenzen fehlen | `sdkmanager --licenses` oder SDK Manager in Android Studio |
| „SDK location not found“ | `local.properties` fehlt und `ANDROID_HOME` ist leer | `local.properties` mit `sdk.dir=…` anlegen oder Startskript nutzen |
| „SelfFramed release keystore not found“ | Pfad in `sf-signing.properties` oder `SF_KEYSTORE_FILE` stimmt nicht | Pfad korrigieren (`RELEASE.md`, Abschnitt 3) |
| „sf.revision must be 1..99“ | ungültiger Wert in `app/selfframed.properties` | Wert korrigieren |
| Stream ruckelt während des Builds | Build ohne Startskript gestartet | abbrechen, über das Startskript neu starten; im Task-Manager (Details → Priorität) muss „Niedriger als normal“ stehen |
| „A build started by this script is still running“ | ein früherer Build läuft noch | abwarten oder `taskkill /T /F /PID <pid>` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` beim Installieren | installierte App hat eine andere Signatur | diese Variante zuerst deinstallieren (ihre Daten gehen verloren) |
| CI: „signing secrets are set, but no signed release APK was produced“ | Secrets vorhanden, Signatur greift aber nicht | Secrets und `app/selfframed.gradle` prüfen |
| CI: „Tag … does not match versionName …“ | Tag und `sf.revision` passen nicht zusammen | `RELEASE.md`, Abschnitt 2 |

## 7. Installation auf dem Telefon

Claude installiert nichts auf Martins Telefon ohne seine Zustimmung.

### Über USB (adb)

1. Auf dem Telefon die Entwickleroptionen freischalten (Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen) und
   „USB-Debugging“ aktivieren.
2. Telefon anschließen, den Fingerabdruck des PCs bestätigen.
3. Am PC (adb liegt unter `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`):
   ```powershell
   adb devices
   adb install -r C:\GITHUB\moonlight-android\app\build\outputs\apk\nonRoot\debug\app-nonRoot-debug.apk
   ```
   `-r` ersetzt eine installierte Version und behält deren Daten (nur bei gleicher Signatur).

### Über WLAN (Wireless debugging, Android 11 und neuer)

1. Entwickleroptionen → „Debugging über WLAN“ aktivieren → „Gerät über einen Kopplungscode koppeln“.
2. Am PC: `adb pair <IP>:<Kopplungsport>` und den angezeigten Code eingeben.
3. Danach mit dem Port aus dem Hauptbildschirm von „Debugging über WLAN“ (nicht dem Kopplungsport) verbinden:
   `adb connect <IP>:<Port>`, dann `adb install -r <apk>`.

PC und Telefon müssen im selben Netz sein.

### Ohne adb

APK auf das Telefon kopieren (USB-Dateiübertragung oder Cloud), im Dateimanager öffnen und der App einmalig „Unbekannte Apps
installieren“ erlauben. Für automatische Updates des Release-APKs: Obtainium (`RELEASE.md`, Abschnitt 4).

### Erster Start

Die App erscheint als „Moonlight E-Ink“ (bzw. „Moonlight E-Ink (Debug)“). Sie hat eigene Einstellungen und ein eigenes
Client-Zertifikat. Kopplungen des offiziellen Moonlight gelten für sie nicht. Jeder Host wird deshalb einmal neu gekoppelt:
Host antippen (oder per IP bzw. Tailscale-Adresse hinzufügen); die App zeigt eine PIN. Diese PIN in der Sunshine-Weboberfläche
(`https://localhost:47990` → PIN) eingeben. Sunshine führt den Fork danach als eigenen Client. Das offizielle Moonlight bleibt
unverändert als Rückfallebene installiert.
