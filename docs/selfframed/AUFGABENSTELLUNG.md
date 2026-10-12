# Aufgabenstellung: SelfFramed-Fork von Moonlight (Android)

Stand: 12.10.2026 · Verfasst von Claude in der Rolle Produktverantwortung, Supervision und Lösungsarchitektur, im Auftrag von Martin (SelfFramed).
Dieses Dokument ist der Maßstab für diese und alle folgenden Sitzungen am Fork. Martins Korrekturen gehen vor und werden hier nachgetragen.

---

## 0. Ausgangsimpuls (gekürzt, sinngemäß)

> Ich will Moonlight forken und von dir weiterentwickeln lassen, alles gut dokumentiert: meine Modifikationen einbauen
> und Upstream-Änderungen nachziehen. Grund: Gradationskurven usw. wie in meinem WASM-Spiel direkt im Stream, damit ich
> nicht ReShade (mit Lightroom-Shader) oder Browser-Modifikationen brauche. Eigene Hotkeys wären möglich. Konzipiere und
> baue die reibungsloseste UX: zwischen Kurven wechseln, Presets speichern, Hotswapping, vielleicht per Hotkey. Ich nutze
> einen E-Ink-Bildschirm und streame meinen Desktop per Moonlight/Sunshine/Tailscale auf ein E-Ink-Lapdock.
> Fernes Ziel: automatische Belichtungsanpassung wie bei meiner a6500.

## 1. Inferenz: Was gebraucht wird

### 1.1 Belegte Fakten (aus Repo, Notizen und Gedächtnis)

| Thema | Befund | Quelle |
|---|---|---|
| Client | Moto G100 im SelfFramed-Lapdock, reiner Thin Client: **Moonlight für Android** streamt den PC | `SelfFramed/README.md`, Profil 08.09.2026 |
| Display | 13,3″ ES133UE2, 1600×1200, Glider/Caster: RGB666-Eingang → Luma (77R+150G+29B)/256 = BT.601 → Dithering → 16 Graustufen | `LAPDOCK/pearl-caster/rtl/rgb2y.v` |
| Hosts | wechseln: Windows-PC (Kontrastdesign „Kontrast Weiß“), Bazzite, Raspberry Pi 4 | Gedächtnis, `bazzite_sunshine_keep_audio` |
| Heutige Kurven | pro Anwendung **auf dem Host**: ReShade+Lightroom.fx pro Spiel; Userscripts `game-curves.js` und `discord-stream-filter` im Browser | WASM-Ordner, `discord-stream-filter/README.md` |
| Bedienmuster, das er kennt | Regler Helligkeit/Kontrast/Sättigung/Gamma, Kurven Gesamt/R/G/B mit Punkten, `Alt+Shift+F` Filter an/aus, `Alt+Shift+P` Bedienfeld | `game-curves.js` |
| Eingabe | 65-%-Tastatur am Lapdock; das Telefon steckt im Rahmen, sein Touchscreen ist im Alltag nicht erreichbar | `SelfFramed/README.md` |

### 1.2 Was dahinter steht

1. **Die Korrektur gehört zum Display, nicht zum Host.** Die Kurven gleichen das Panel aus (Graustufen, Dithering, wenige
   Tonwerte). Das Panel hängt am Telefon, die Hosts wechseln. Eine Lösung im Client wirkt deshalb einmal für alles: jede
   App, jeder Host, Desktop, Spiel und Video. ReShade und Userscripts decken dagegen nur einzelne Programme auf einem Host ab.
2. **Es geht um Barrierefreiheit.** Martin kann hintergrundbeleuchtete Bildschirme praktisch nicht nutzen; E-Ink ist sein
   tägliches Arbeitsgerät. Dunkle Spiele, Videos und dunkle Oberflächen sind dort ohne Tonwertkorrektur oft unlesbar.
3. **„Reibungslos“ heißt konkret:**
   - Man bleibt im Stream.
   - Die Bedienung läuft über die Tastatur, weil der Touchscreen nicht erreichbar ist.
   - Ein Wechsel wirkt sofort, auch wenn das Bild gerade steht (statischer Desktop).
   - Es gibt keinen Speichern-Knopf; Werte bleiben einfach erhalten.
   - Die Kürzel sind die, die er aus seinen Userscripts kennt.
   - Die Oberfläche ist auf E-Ink lesbar: schwarz auf weiß, kräftige Linien, keine Bedeutung über Farbe, kaum Animation.
4. **Der Fork ist ein Pflegeprodukt.** Claude soll ihn über Jahre mit Upstream mitziehen. Deshalb gehören dazu:
   möglichst kleine, markierte Eingriffe in Upstream-Dateien, eine Eingriffsliste (Patch-Ledger), Runbooks, CI und eine
   automatische Meldung bei neuen Upstream-Versionen.
5. **Der Fork ist SelfFramed-Software.** Er ist die erste eigene Software auf dem Gerät (vgl. `SelfFramed/README.md`, Roadmap
   Punkt 6), liegt unter `github.com/SelfFramed` und bleibt GPLv3 wie Upstream.

## 2. Steelman

Martin will keinen Kurvenfilter als Spielerei. Er will einen **display-bewussten, dauerhaft gepflegten Moonlight-Client
für sein E-Ink-Lapdock**. Eine GPU-Bildstufe im Client bringt seine vertraute Lightroom-/Userscript-Bedienung (Gamma,
Helligkeit, Kontrast, Sättigung, Gradationskurven) auf **jedes** gestreamte Bild. Neu dazu kommt eine Graustufen-Mischung,
die zur Umrechnung des Glider passt. Presets lassen sich mitten im Stream per Tastenkürzel sofort umschalten und live über
dem laufenden Bild feinjustieren; sie speichern sich selbst. Der Fork bleibt mit Upstream zusammenführbar, ist so
dokumentiert, dass jede künftige Claude-Sitzung ohne Vorwissen weiterarbeiten kann, und ist so gebaut, dass später eine
automatische Belichtungsanpassung (Histogramm → dynamische Kurve) andocken kann.

## 3. Aufgabenstellung (verbindlich)

### A. Fork-Fundament
- A1. Fork von `moonlight-stream/moonlight-android` ab **v12.2**. Branch-Modell: `master` spiegelt Upstream (nur
  Fast-Forward), `main` trägt SelfFramed. Upstream-Releases (`v*`-Tags) werden per Merge in `main` übernommen.
- A2. Eigene Identität: applicationId `com.selfframed.moonlight` (ausdrücklicher Wunsch des Upstream-Autors), Name
  „Moonlight E-Ink“. Die App läuft parallel zum offiziellen Moonlight, das als Rückfallebene erhalten bleibt.
- A3. Minimal-invasiv: neuer Code nur in `com.limelight.selfframed.*` und `sf_*`-Ressourcen. Eingriffe in Upstream-Dateien
  sind mit `[SelfFramed]` markiert und in `PATCHES.md` gelistet.
- A4. Versionsschema `<upstream>-sf.<n>`, versionCode = upstream × 100 + n.

### B. Bildpipeline im Client
- B1. Der Decoder schreibt in eine GPU-Textur (SurfaceTexture). Ein GLES-Shader rechnet pro Pixel:
  Graustufen-Mischung (optional) → Gamma → Gesamtkurve → Kanalkurven → Helligkeit → Kontrast → Sättigung.
  Die Reihenfolge und Mathematik sind identisch mit `game-curves.js`, damit seine Werte gleich wirken.
- B2. Latenzneutral: Zeitstempel durchreichen, immer das neueste Bild, kein Kopieren; GPU-Kosten unter 1–2 ms pro Bild.
- B3. Rückfall auf den Originalpfad, wenn GL scheitert oder HDR aktiv ist (für E-Ink irrelevant).
- B4. Hauptschalter in den Einstellungen; aus = exakt Upstream-Verhalten.

### C. Presets und Tastenkürzel
- C1. 9 Preset-Plätze mit E-Ink-Startwerten, ein Neutral-Zustand. Speicherung lokal und automatisch; das letzte Preset
  gilt beim nächsten Stream wieder. Das JSON-Format ist kompatibel mit seinem Userscript-Zustand. Export und Import
  laufen über eine Datei.
- C2. Kürzel im Moonlight-Schema Strg+Alt+Shift+Taste:
  - `1`–`9`: Preset wählen
  - `0`: Neutral
  - `F`: Filter an/aus (Vorher/Nachher)
  - `P`: Bedienfeld
  - `←` / `→`: voriges/nächstes Preset
  - Jeder Wechsel zeigt eine E-Ink-lesbare Einblendung.
- C3. Die Kürzel stehen zentral in einer Tabelle, damit neue Kürzel trivial ergänzbar sind.

### D. Live-Editor im Stream
- D1. Bedienfeld über dem laufenden Stream: Preset-Auswahl, Regler, Kurveneditor (Gesamt/R/G/B), Graustufen-Mischung.
  Es ist vollständig per Tastatur bedienbar und mit der Maus, wenn sie nicht eingefangen ist. Wirkung live, Speicherung
  automatisch.
- D2. Solange das Bedienfeld offen ist, gehen keine Bedien-Tasten an den Host.

### E. Doku und Pflege
- E1. `docs/selfframed/`: Aufgabenstellung, Architektur, Bedienung, Presets, Build, Upstream-Sync-Runbook, Patch-Ledger,
  Release, Roadmap, Testprotokoll. Dazu eine `CLAUDE.md` für künftige Sitzungen.
- E2. CI (GitHub Actions): Build und Tests bei jedem Push; ein Upstream-Wächter meldet neue Upstream-Releases als PR oder
  Issue.

### F. Verifikation
- F1. JVM-Unit-Tests der Kurvenmathematik, gegen Referenzwerte aus seinem `game-curves.js` (Node).
- F2. Der Build läuft durch (Debug und Release), die Tests sind grün.
- F3. GPU-Selbsttest auf einem Emulator, sofern ohne Last-Probleme möglich.
- F4. Ein Testprotokoll für das echte Gerät (Schritt für Schritt), weil das Telefon in dieser Sitzung nicht erreichbar ist.

### G. Roadmap (nicht in diesem Inkrement)
- Automatische Belichtung: Histogramm, Auto-Levels/Auto-Gamma mit Glättung, Belichtungskorrektur ±EV wie an der a6500.
- Farbtonbasierte Graustufen-Mischung im Stil der Lightroom-S/W-Mischung.
- Schärfen (Unscharf maskieren) für Text.
- Presets automatisch je Host oder App.
- QMK-Tasten am eigenen Keyboard-PCB.
- Abstimmung mit dem Glider-Refresh (Full-Refresh-Taste).

## 4. Randbedingungen
- Martin streamt diesen PC während der Arbeit. Builds laufen deshalb mit niedriger Priorität und begrenzten Workern; kein
  zweiter Stream gegen seinen Sunshine-Host ohne Rückfrage.
- Nichts wird auf sein Telefon installiert und nichts öffentlich gepusht ohne seine Zustimmung. Das GitHub-Konto
  `SelfFramed` ist ein eigenes Konto; die gh-CLI ist als `krausality` angemeldet.
- GPLv3, Upstream-Namensnennung, keine offizielle applicationId.

## 5. Nicht-Ziele
- Keine Host-seitige Lösung (Sunshine-Filter), kein Ersatz für ReShade-Effekte jenseits der Tonwerte.
- Kein Umbau von Upstream-Code über die Einhängepunkte hinaus.
- Kein HDR-Tonemapping.

## 6. Konfidenz
- Hoch: Client = Moonlight Android; Einhängepunkt (Decoder-Ausgabe → SurfaceTexture → GL); Kurvenmathematik (Port von
  `game-curves.js`, gegen Node-Referenz getestet).
- Mittel: Startwerte der E-Ink-Presets. Sie sind begründete Schätzungen und brauchen den Sehtest am Gerät.
- Offen bis zum Gerätetest: tatsächliche Zusatzlatenz und Verhalten mit dem Glider bei 75 Hz.
