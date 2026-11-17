# SynchronizeTool – Bedienungsanleitung

**Version 0.8.0 · Stand 17.11.2026**

Diese Anleitung beschreibt Schritt für Schritt, wie Sie mit SynchronizeTool Daten von
einem Rechner auf einen anderen übertragen. Sie richtet sich an Anwender, die das
Programm ohne Programmierkenntnisse bedienen möchten.

---

## Inhalt

1. [Wozu dient SynchronizeTool?](#1-wozu-dient-synchronizetool)
2. [Voraussetzungen](#2-voraussetzungen)
3. [Installation und Start](#3-installation-und-start)
4. [Grundbegriffe](#4-grundbegriffe)
5. [Der Programmordner](#5-der-programmordner)
6. [Schnellstart](#6-schnellstart)
7. [Reiter „Quelle“ – Paket erstellen](#7-reiter-quelle--paket-erstellen)
8. [Reiter „Ziel“ – Paket einspielen](#8-reiter-ziel--paket-einspielen)
9. [Reiter „Netzwerk“ – direkt übertragen](#9-reiter-netzwerk--direkt-übertragen)
10. [Reiter „Protokolle“](#10-reiter-protokolle)
11. [Ausschlussmuster](#11-ausschlussmuster)
12. [Pfade und `${HOME}`](#12-pfade-und-home)
13. [Aufbau einer Paket-ZIP](#13-aufbau-einer-paket-zip)
14. [Sicherungen und Rollback](#14-sicherungen-und-rollback)
15. [Gespeicherte Konfigurationen](#15-gespeicherte-konfigurationen)
16. [Dateien und Speicherorte im Überblick](#16-dateien-und-speicherorte-im-überblick)
17. [Typische Fragen und Fehler](#17-typische-fragen-und-fehler)
18. [Grenzen und Sicherheitshinweise](#18-grenzen-und-sicherheitshinweise)

---

## 1. Wozu dient SynchronizeTool?

SynchronizeTool überträgt ausgewählte **Dateien und ganze Verzeichnisse** von einem
Rechner (Quelle) auf einen anderen Rechner (Ziel). Typische Einsatzfälle sind portable
Programme, Einstellungsdateien, Konfigurationsordner oder Projektdaten.

Das Werkzeug arbeitet mit **benannten Paketen**. Ein Paket ist eine ZIP-Datei, die die
gewünschten Daten und eine Beschreibung der Zielpfade enthält. Auf dem Zielrechner wird
das Paket wieder eingespielt. Vor jedem Ersetzen werden vorhandene Dateien mit einem
Zeitstempel gesichert; die letzte Einspielung lässt sich rückgängig machen.

**Die wichtigsten Eigenschaften:**

- Export als ZIP-Datei (`.zip`), Import durch Auswahl derselben ZIP-Datei
- Transport per USB-Stick, Netzlaufwerk **oder** direkt über das lokale Netzwerk
- Sicherung der Zieldateien vor dem Überschreiben
- Vorschau (Dry Run) vor dem Einspielen
- Rollback der letzten Einspielung
- Ausschlussmuster für Logs, Caches usw.
- Zwei Kopiermodi: nur geänderte Dateien oder alles ersetzen
- Getrennte Protokolldateien für Erstellung und Einspielung

---

## 2. Voraussetzungen

| Zweck | Voraussetzung |
|---|---|
| Programm ausführen | Java 17 oder neuer (JRE genügt) |
| Programm selbst bauen | JDK 17 oder neuer und Maven (optional) |
| Betriebssystem | Windows, macOS oder Linux |

- Auf **beiden** Rechnern sollte möglichst dasselbe Betriebssystem laufen. Absolute
  Pfade werden nicht zwischen Windows und macOS/Linux umgerechnet.
- Das Programmverzeichnis muss **beschreibbar** sein, weil dort die Ordner `Export`,
  `Import` und `Logfiles` angelegt werden.

---

## 3. Installation und Start

### 3.1 Fertige JAR-Datei verwenden

1. Legen Sie die Datei `synchronize-tool-0.8.0.jar` in einen eigenen, beschreibbaren
   Ordner, zum Beispiel `SynchronizeTool/`.
2. Starten Sie das Programm:

   ```bash
   java -jar synchronize-tool-0.8.0.jar
   ```

   Unter Windows genügt ein Doppelklick auf die JAR-Datei, sofern Java korrekt
   installiert ist.

3. Beim ersten Start legt das Programm neben der JAR-Datei automatisch die Ordner
   `Export`, `Import` und `Logfiles` an.

### 3.2 Selbst bauen (optional)

```bash
mvn package
java -jar target/synchronize-tool-0.8.0.jar
```

Ohne Maven:

```bash
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
java -cp out de.synchronizetool.Main
```

### 3.3 Wo liegt das Programmverzeichnis?

Das Programmverzeichnis ist der Ordner, in dem die JAR-Datei liegt. Wird das Programm
aus einer Entwicklungsumgebung gestartet, gilt das aktuelle Arbeitsverzeichnis. Beim
Start zeigt das Protokoll die verwendeten Ordner an:

```text
Programmordner: ...
Pakete exportieren: .../Export
Pakete importieren: .../Import
Protokolle: .../Logfiles
```

---

## 4. Grundbegriffe

| Begriff | Bedeutung |
|---|---|
| **Paket** | Eine ZIP-Datei mit `config.txt` und `payload/`. Enthält die zu übertragenden Daten und die Zielpfade. |
| **Quelle / Rechner 1** | Der Rechner, auf dem die Daten ausgewählt und als Paket exportiert werden. |
| **Ziel / Rechner 2** | Der Rechner, auf dem das Paket eingespielt wird. |
| **`config.txt`** | Lesbare Liste der ausgewählten Pfade und Ausschlussmuster. |
| **`payload/`** | Die eigentlichen Daten im Paket. |
| **Sicherung** | Kopie einer vorhandenen Datei bzw. eines Ordners mit Zeitstempel vor dem Ersetzen. |
| **Dry Run** | Vorschau: zeigt Änderungen an, ohne etwas zu verändern. |
| **Rollback** | Rückgängigmachen der letzten Einspielung. |

---

## 5. Der Programmordner

Neben der JAR-Datei liegen drei feste Ordner:

```text
SynchronizeTool/
├── synchronize-tool-0.8.0.jar
├── Export/                 # erzeugte Paket-ZIPs (Rechner 1)
├── Import/                 # Paket-ZIPs von Rechner 1 (Rechner 2)
└── Logfiles/
    ├── Erstellung/         # Protokolle: Paket erstellen, kombinieren, freigeben
    └── Einspielung/        # Protokolle: Vorschau, Synchronisieren, Rollback
```

- **`Export`** enthält die Pakete, die Sie auf Rechner 1 erzeugen.
- **`Import`** ist der Übergabeordner auf Rechner 2. Kopieren Sie die gewünschte
  ZIP-Datei von `Export` (Rechner 1) nach `Import` (Rechner 2).
- **`Logfiles`** enthält die Protokolldateien, getrennt nach Erstellung und Einspielung.

Die Ordner werden beim Start automatisch angelegt. Sie können beliebig viele Pakete
parallel pflegen, zum Beispiel `Paket1.zip`, `Paket2.zip` oder `JavaPaket.zip`.

---

## 6. Schnellstart

### 6.1 Übertragung per USB-Stick oder Netzlaufwerk

**Auf Rechner 1 (Quelle):**

1. Wechseln Sie in den Reiter **„Quelle: auswählen & Paket erstellen“**.
2. Wählen Sie unter **„Paket in Export“** ein vorhandenes Paket oder legen Sie mit
   **„Neues Paket...“** ein neues an.
3. Fügen Sie mit **„Datei hinzufügen“** bzw. **„Verzeichnis hinzufügen“** die
   gewünschten Daten hinzu.
4. Klicken Sie auf **„Paket erstellen (Export)“**.
5. Kopieren Sie die erzeugte ZIP-Datei aus `Export` auf einen USB-Stick oder in den
   Ordner `Import` auf Rechner 2.

**Auf Rechner 2 (Ziel):**

1. Wechseln Sie in den Reiter **„Ziel: Paket einspielen“**.
2. Wählen Sie unter **„Paket in Import“** das kopierte Paket aus.
3. Prüfen Sie die angezeigte `config.txt` und den Kopiermodus.
4. Klicken Sie optional auf **„Vorschau (Dry Run)“**.
5. Klicken Sie auf **„Synchronisieren (Sicherung + Kopieren)“**.

### 6.2 Übertragung über das lokale Netzwerk

**Auf Rechner 1:** Reiter **„Netzwerk“** → Port prüfen → **„Paket erstellen &
freigeben“** → URL kopieren.

**Auf Rechner 2:** Reiter **„Netzwerk“** → URL einfügen → **„Herunterladen &
Synchronisieren“** (oder nur herunterladen bzw. Vorschau).

Details siehe [Abschnitt 9](#9-reiter-netzwerk--direkt-übertragen).

---

## 7. Reiter „Quelle“ – Paket erstellen

Der Reiter **„Quelle: auswählen & Paket erstellen“** ist die Arbeitsfläche auf
Rechner 1.

### 7.1 Paket auswählen oder neu anlegen

- **„Paket in Export“**: Dropdown mit allen vollständigen Paketen unter `Export`
  (ZIP-Dateien und alte Paketordner). Die Auswahl lädt die zugehörige Konfiguration
  nach einer Sicherheitsabfrage. **„Aktualisieren“** liest die Liste neu ein.
- **„Paket-ZIP“**: vollständiger Pfad des aktuellen Pakets. Über **„...“** können Sie
  auch ein Paket außerhalb von `Export` wählen. Die Endung `.zip` wird bei Bedarf
  automatisch ergänzt.
- **„Neues Paket...“**: legt ein neues, leeres Paket an. Geben Sie einen Namen an
  (z. B. `JavaPaket`); gespeichert wird `Export/JavaPaket.zip`. Optional übernimmt die
  Checkbox **„Aktuelle Auswahl übernehmen“** die derzeit ausgewählten Einträge. Ein
  bereits vorhandener Name wird abgelehnt.

> Der übergeordnete Ordner `Export` selbst kann nicht als Paket verwendet werden.

### 7.2 Dateien und Verzeichnisse hinzufügen

- **„Datei hinzufügen“**: eine oder mehrere einzelne Dateien auswählen.
- **„Verzeichnis hinzufügen“**: einen kompletten Ordner samt Inhalt übernehmen.
- **„Entfernen“**: markierten Eintrag aus der Auswahl entfernen.

Doppelte Einträge und Einträge, die bereits in einem ausgewählten Verzeichnis liegen,
werden automatisch verworfen. Ein neu hinzugefügtes Verzeichnis ersetzt umgekehrt
bereits ausgewählte Untereinträge.

### 7.3 Ausschlüsse festlegen

Über **„Ausschlüsse...“** öffnen Sie einen Dialog, in dem Sie ein Muster pro Zeile
eintragen, zum Beispiel:

```text
*.log
node_modules
cache
**/tmp/**
```

Ausgeschlossene Dateien und Verzeichnisse werden nicht ins Paket kopiert und beim
Einspielen im Ziel nicht angetastet. Details siehe
[Abschnitt 11](#11-ausschlussmuster).

### 7.4 Konfiguration separat speichern

**„Konfiguration speichern...“** schreibt nur eine separate `config.txt`, zum Beispiel
für die Ablage unter Versionskontrolle. Eine bestehende Paket-ZIP wird dabei **nicht**
überschrieben.

### 7.5 Paket erstellen (Export)

**„Paket erstellen (Export)“** erzeugt die Paket-ZIP mit `config.txt` und `payload/`.

- Existiert die ZIP bereits, wird sie nach Bestätigung ersetzt.
- Nicht mehr ausgewählte Daten sind anschließend nicht mehr im Paket enthalten.
- Die ZIP wird erst nach erfolgreichem Schreiben ersetzt, damit kein halbes Paket
  zurückbleibt.

### 7.6 Mehrere Pakete kombinieren

**„Pakete kombinieren...“** führt mehrere vorhandene Pakete zu einem Gesamtpaket
zusammen:

1. Links die gewünschten Pakete markieren und mit **„Hinzufügen →“** nach rechts
   übernehmen. **„Weitere...“** ergänzt ein Paket von einem anderen Speicherort.
2. Rechts die Reihenfolge festlegen: Das **oberste** Paket hat Vorrang, wenn dieselbe
   Datei in mehreren Paketen vorkommt. **„Nach oben“** / **„Nach unten“** ändert die
   Reihenfolge.
3. Einen **Paketnamen** eingeben. Das Gesamtpaket wird als `Export/<Name>.zip` angelegt;
   ein bereits vorhandenes Ziel wird nicht überschrieben.
4. **OK** entpackt die Quellpakete bei Bedarf, führt sie zusammen und schreibt die neue
   Paket-ZIP.

Dabei gilt:

- Es werden nur vollständige Pakete verwendet (`config.txt` und `payload/`), sowohl als
  ZIP als auch als alter Paketordner.
- Die Ausschlussmuster aller Pakete werden zusammengeführt.
- Die Daten stammen aus den Payloads der vorhandenen Pakete; die ursprünglichen
  Quelldateien müssen nicht mehr existieren.
- Widersprüchliche Strukturen (Datei und Verzeichnis am selben Pfad) werden mit einer
  Meldung abgelehnt.

### 7.7 Gespeicherte Konfigurationen

Über der Auswahlliste befindet sich das Feld **„Gespeicherte Konfigurationen“**. Es
enthält die zuletzt verwendeten Pakete und `config.txt`-Dateien, das neueste zuerst,
und bleibt nach einem Neustart erhalten.

- **„Laden“** öffnet die markierte Konfiguration. Deren Einträge und Ausschlussmuster
  ersetzen die aktuelle Auswahl; das passende Paket wird automatisch gesetzt.
- **„Öffnen...“** wählt eine Paket-ZIP, einen alten Paketordner oder eine `config.txt`
  direkt vom Dateisystem.
- **„Aus Liste entfernen“** blendet einen Eintrag nur aus; Dateien und Paket bleiben
  unverändert.

Details siehe [Abschnitt 15](#15-gespeicherte-konfigurationen).

---

## 8. Reiter „Ziel“ – Paket einspielen

Der Reiter **„Ziel: Paket einspielen“** ist die Arbeitsfläche auf Rechner 2.

### 8.1 Paket auswählen

- **„Paket in Import“**: Dropdown mit allen vollständigen Paketen unter `Import`. Die
  Auswahl setzt den Paketpfad und zeigt die Konfiguration an. **„Aktualisieren“** liest
  die Liste neu ein, zum Beispiel nachdem Sie eine ZIP-Datei manuell nach `Import`
  kopiert haben.
- **„Paket-ZIP“**: vollständiger Pfad. Über **„...“** können Sie ein Paket außerhalb
  von `Import` wählen.
- Die Liste **„Inhalt der config.txt“** zeigt die Einträge und ihre Auflösung auf
  diesem Rechner.

> Der übergeordnete Ordner `Import` selbst kann nicht eingespielt werden.

### 8.2 Konfiguration anzeigen

**„Konfiguration anzeigen“** liest die `config.txt` und zeigt die Pfade an. Bei einer
ZIP-Datei wird die `config.txt` direkt aus dem Archiv gelesen; das Paket wird dafür
nicht vollständig entpackt.

### 8.3 Kopiermodus wählen

Unter **„Kopiermodus“** stehen zwei Optionen zur Verfügung:

| Modus | Verhalten |
|---|---|
| **Nur geänderte Dateien** (Standard) | Kopiert nur neue und geänderte Dateien. Unveränderte Dateien bleiben unangetastet. Nicht mehr vorhandene Dateien und leere Verzeichnisse werden mit Zeitstempel gesichert und entfernt. |
| **Alles ersetzen** | Sichert und kopiert jeden Eintrag vollständig – auch wenn er bereits identisch ist. |

Die Auswahl wird gespeichert und gilt auch für **„Herunterladen & Synchronisieren“**
im Netzwerk-Reiter.

### 8.4 Vorschau (Dry Run)

**„Vorschau (Dry Run)“** vergleicht das Paket mit dem Ziel, **ohne etwas zu verändern**.
Die Paket-ZIP wird dafür temporär entpackt und danach wieder gelöscht. Die Ausgabe
enthält unter anderem:

- `NEU` – existiert im Ziel noch nicht
- `GLEICH` – Inhalt ist bereits identisch
- `ÄNDERN` – wird ersetzt (mit Details: hinzugefügt / geändert / entfernt)
- `PROBLEM` – zum Beispiel fehlende Daten im Paket
- wie viele Sicherungen angelegt würden

### 8.5 Synchronisieren

**„Synchronisieren (Sicherung + Kopieren)“** führt den Abgleich aus. Vorher erscheint
eine Sicherheitsabfrage. Auch hier wird die ZIP temporär entpackt und danach gelöscht.

Ablauf:

1. Vorhandene Dateien/Verzeichnisse werden mit Zeitstempel gesichert.
2. Die neue Version aus `payload/` wird an denselben logischen Pfad kopiert.
3. Per SHA-256 wird geprüft, ob die Inhalte übereinstimmen.

### 8.6 Letzte Synchronisation rückgängig machen

**„Letzte Synchronisation rückgängig“** spielt die Sicherungen des letzten Laufs
zurück. Ein zweites Rollback direkt hintereinander ist nicht möglich. Details siehe
[Abschnitt 14](#14-sicherungen-und-rollback).

---

## 9. Reiter „Netzwerk“ – direkt übertragen

Wenn beide Rechner im selben lokalen Netzwerk sind, kann das Paket direkt übertragen
werden. Die Übertragung ist **unverschlüsselt (HTTP)** und für vertrauenswürdige
Netzwerke gedacht. Die URL enthält einen zufälligen Token als Schutz vor
versehentlichem Zugriff.

### 9.1 Rechner 1 (freigeben)

1. Im Reiter **„Netzwerk“** einen Port wählen (Standard `8765`).
2. **„Paket erstellen & freigeben“** klicken. Das Tool erzeugt die aktuell in „Quelle“
   gewählte Paket-ZIP – eine vorhandene ZIP wird nach Bestätigung ersetzt – und gibt
   genau diese ZIP über einen kleinen HTTP-Server frei.
3. Die angezeigte URL kopieren oder auf Rechner 2 abtippen.
4. Mit **„Freigabe beenden“** wird der Server wieder gestoppt.

### 9.2 Rechner 2 (laden und einspielen)

1. Im Reiter **„Netzwerk“** die URL einfügen (Button **„Einfügen“** nutzt die
   Zwischenablage).
2. Zielpfad im Feld **„Paket-ZIP (wird unter Import angelegt)“** prüfen oder über
   **„...“** ändern.
3. Eine der drei Aktionen wählen:
   - **„Nur herunterladen“** – speichert das Paket, ohne etwas einzuspielen.
   - **„Herunterladen & Vorschau“** – speichert und führt einen Dry Run aus.
   - **„Herunterladen & Synchronisieren“** – speichert und spielt das Paket ein; es
     gilt der im Reiter „Ziel“ gewählte Kopiermodus.
4. Das Paket wird standardmäßig als
   `Import/Netzwerkpaket-<Zeitstempel>.zip` gespeichert und steht anschließend auch im
   Reiter „Ziel“ zur Auswahl.

> Bei Bedarf muss der Port in der Firewall freigegeben werden.

---

## 10. Reiter „Protokolle“

Für jede Paketerstellung und jede Einspielung wird eine eigene Protokolldatei
geschrieben:

```text
<Programmordner>/Logfiles/
├── Erstellung/     # Paket erstellen, neues Paket, kombinieren, freigeben
└── Einspielung/    # Vorschau, Synchronisieren, Rollback, Netzwerk-Einspielung
```

- Dateiname: `<yyyyMMdd_HHmmss>_<vorgang>.log`; bei Namensgleichheit wird `_2`, `_3`
  usw. angehängt.
- Die Dateien sind UTF-8-kodiert und enthalten einen Kopf mit Bereich, Vorgang,
  Zeitpunkt, Rechner und Betriebssystem sowie alle Meldungen des Laufs.
- Im Reiter **„Protokolle“** lässt sich der Bereich wählen. Die Dateien werden neueste
  zuerst aufgelistet und der Inhalt schreibgeschützt angezeigt.
- **„Aktualisieren“** liest die Liste neu ein, **„Logordner öffnen“** zeigt den Ordner
  im Dateimanager.
- Nur Herunterladen ohne Einspielen erzeugt kein Einspiel-Protokoll, weil dabei keine
  Dateien im Ziel verändert werden.

Zusätzlich zeigt das Protokollfeld am unteren Rand alle Meldungen des aktuellen Laufs
an.

---

## 11. Ausschlussmuster

Im Reiter „Quelle“ über **„Ausschlüsse...“** ein Muster pro Zeile eintragen:

```text
*.log
node_modules
cache
**/tmp/**
```

Regeln:

| Muster | Bedeutung |
|---|---|
| `*` | beliebig viele Zeichen innerhalb eines Pfadsegments |
| `?` | genau ein Zeichen |
| `**` | beliebig viele Zeichen über Verzeichnisgrenzen hinweg |

- Muster **ohne `/`** gelten für jedes Pfadsegment. `node_modules` trifft also auch
  `a/node_modules/x.js`.
- Ausgeschlossene Dateien werden nicht ins Paket kopiert.
- Beim Einspielen bleiben ausgeschlossene Dateien im Ziel erhalten:
  - **Alles ersetzen:** Sie werden aus der Sicherung zurückgeholt.
  - **Nur geänderte Dateien:** Sie werden gar nicht erst angefasst.

So verschwinden zum Beispiel lokale Logs oder Cache-Dateien nicht.

---

## 12. Pfade und `${HOME}`

In der `config.txt` werden Pfade **logisch** gespeichert:

- Alles unterhalb des Benutzerverzeichnisses wird als `${HOME}/...` gespeichert und auf
  dem Zielrechner in dessen Benutzerverzeichnis aufgelöst. So funktioniert zum Beispiel
  `~/.config/meinprogramm` auf beiden Maschinen, auch wenn die Benutzernamen
  unterschiedlich sind.
- Absolute Pfade außerhalb des Benutzerverzeichnisses werden unverändert übernommen
  (z. B. `C:/ProgramData/...` oder `/opt/...`). Diese Pfade müssen auf beiden Rechnern
  gleich sein.

Beispiel einer `config.txt`:

```text
# SyncTool Konfiguration v1
# F = Datei, D = Verzeichnis | ${HOME} = Benutzerverzeichnis des Zielrechners
# X = Ausschlussmuster (*, ?, **), z. B. X|*.log oder X|node_modules
F|${HOME}/.config/meinprogramm/settings.ini
D|${HOME}/.local/share/meinprogramm
D|C:/ProgramData/meinprogramm
X|*.log
X|node_modules
```

---

## 13. Aufbau einer Paket-ZIP

```text
Paket1.zip
├── config.txt              # ausgewählte Pfade + Ausschlussmuster
└── payload/                # 1:1-Kopie der ausgewählten Daten
```

- Ein vollständiges Paket enthält `config.txt` und den Ordner `payload/` im
  Wurzelverzeichnis des ZIP.
- Beim Erstellen wird das Paket zuerst in einem temporären Ordner vorbereitet und
  anschließend als ZIP geschrieben.
- Beim Einspielen wird die ZIP in einen temporären Ordner entpackt und nach dem Lauf
  wieder gelöscht.
- Alte, bereits entpackte Paketordner mit `config.txt` und `payload/` werden weiterhin
  erkannt und gelesen. Neu erzeugt werden nur noch ZIP-Dateien.
- Manipulierte Einträge, die aus dem Zielordner ausbrechen wollen (Zip-Slip), werden
  abgelehnt.

---

## 14. Sicherungen und Rollback

### 14.1 Sicherungen

Vor dem Ersetzen wird der vorhandene Stand mit einem Zeitstempel gesichert:

```text
settings.ini        →  settings.ini_20261117_143000
meinprogramm/       →  meinprogramm_20261117_143000/
```

- Im Modus **„Nur geänderte Dateien“** entsteht die Sicherung nur für tatsächlich
  geänderte oder entfernte Dateien.
- Sicherungen des Werkzeugs (`name_yyyyMMdd_HHmmss`) werden bei späteren Läufen nicht
  als überzählige Dateien behandelt.

### 14.2 Rollback

Nach jedem Einspiel-Lauf schreibt das Tool auf dem Zielrechner:

```text
~/.synchronizetool/last-sync.txt
```

Darin steht, welche Datei unter welchem Sicherungsnamen liegt oder neu angelegt wurde.

**„Letzte Synchronisation rückgängig“** im Reiter „Ziel“:

- Sicherungen werden an ihren ursprünglichen Platz zurückverschoben.
- Neu angelegte Dateien und Verzeichnisse werden zur Seite gelegt.
- Der aktuell vorhandene Stand wird nicht gelöscht, sondern mit dem Zusatz
  `_rollback_<Timestamp>` zur Seite gelegt.
- Das Protokoll wird als `last-sync.txt.done_<Timestamp>` archiviert.
- Ein zweites Rollback direkt hintereinander ist dadurch nicht möglich.
- Das Rollback bezieht sich immer auf den letzten Einspiel-Lauf. Sicherungen früherer
  Läufe bleiben erhalten.

---

## 15. Gespeicherte Konfigurationen

Die Liste der zuletzt verwendeten Pakete und Konfigurationsdateien wird automatisch
gepflegt und unter

```text
~/.synchronizetool/recent-configs.txt
```

gespeichert. Sie enthält maximal 25 Einträge; doppelte Pfade werden zusammengefasst.
Fehlt ein Paket inzwischen, bleibt der Eintrag sichtbar und wird mit
„(nicht gefunden)“ markiert.

Beim Laden gilt:

- Eine **Paket-ZIP** wird direkt gelesen.
- Bei einem **Ordner** wird die darin liegende `config.txt` verwendet.
- Eine separat abgelegte **Konfigurationsdatei** lässt sich weiterhin laden; als Ziel
  für einen anschließenden Export wird dann `Export/Paket1.zip` – oder das erste
  vorhandene Paket – eingetragen und ein Hinweis ins Protokoll geschrieben. So bleibt
  eine separate Konfiguration nutzbar, ohne ein bestehendes Paket zu überschreiben.

---

## 16. Dateien und Speicherorte im Überblick

| Datei / Ordner | Ort | Zweck |
|---|---|---|
| `Export/` | Programmordner | erzeugte Paket-ZIPs (Rechner 1) |
| `Import/` | Programmordner | Paket-ZIPs zum Einspielen (Rechner 2) |
| `Logfiles/Erstellung/` | Programmordner | Protokolle der Paketerstellung |
| `Logfiles/Einspielung/` | Programmordner | Protokolle der Einspielung |
| `settings.txt` | `~/.synchronizetool/` | gewählter Kopiermodus |
| `recent-configs.txt` | `~/.synchronizetool/` | zuletzt verwendete Konfigurationen |
| `last-sync.txt` | `~/.synchronizetool/` | Rollback-Informationen der letzten Einspielung |

Benutzerspezifische Zustände unter `~/.synchronizetool` werden **nicht** mit dem
Programmordner kopiert. Bestehende Pakete und Protokolle an alten Speicherorten werden
nicht automatisch verschoben.

---

## 17. Typische Fragen und Fehler

| Problem | Ursache / Lösung |
|---|---|
| „Kein vollständiges Paket gefunden“ | Die ZIP enthält nicht `config.txt` und `payload/` im Wurzelverzeichnis, oder es wurde der übergeordnete Ordner `Export`/`Import` gewählt. Bitte die Paket-ZIP selbst auswählen. |
| Paket erscheint nicht im Dropdown | **„Aktualisieren“** klicken. Die ZIP muss direkt unter `Export` bzw. `Import` liegen und vollständig sein. |
| „Bitte eine Paket-ZIP auswählen, nicht den übergeordneten Ordner“ | Es wurde `Export/` oder `Import/` statt einer konkreten Paketdatei gewählt. |
| Datei kann nicht kopiert werden | Die Datei ist geöffnet oder gesperrt. Programm schließen und erneut versuchen. |
| Pfad existiert auf Rechner 2 nicht | Bei absoluten Pfaden außerhalb `${HOME}` müssen die Pfade auf beiden Rechnern gleich sein. |
| Symbolischer Link wurde übersprungen | Links innerhalb eines Verzeichnisses werden bewusst nicht kopiert; ein Hinweis erscheint im Protokoll. |
| Netzwerk-Download schlägt fehl | URL und Token prüfen, gleiche Netzwerkumgebung sicherstellen, Port in der Firewall freigeben. |
| Rollback nicht möglich | Es wurde auf diesem Rechner noch nichts eingespielt, oder das Rollback wurde bereits ausgeführt. |
| „Programmordner nicht beschreibbar“ | Die JAR-Datei in einen beschreibbaren Ordner verschieben (nicht z. B. nach `Program Files`, ohne Rechte). |

---

## 18. Grenzen und Sicherheitshinweise

- **Installierte Programme:** Reines Dateikopieren überträgt keine Windows-Registry,
   keine Dienste, keine Treiber und keine Berechtigungen. Für portable Programme,
   Einstellungen und Konfigurationsdateien funktioniert das 1:1-Prinzip gut; für echte
   Installationen braucht es zusätzlich MSI-/PKG-Deployment oder Werkzeuge wie
   Ansible/Chef.
- **Gleiches Betriebssystem empfohlen:** Absolute Pfade werden nicht zwischen Windows
   und macOS/Linux umgerechnet (`C:/...` wird auf Unix als relativer Pfad
   interpretiert).
- **Symbolische Links** innerhalb eines Verzeichnisses werden übersprungen und mit
   einem Hinweis protokolliert. Ist der ausgewählte Pfad selbst ein Link, wird sein
   Ziel kopiert.
- **Offene/gesperrte Dateien** (z. B. eine laufende Datenbank) können nicht kopiert
   werden.
- **Kein differentieller Export:** Beim Export wird der komplette Payload neu
   geschrieben. Der Import kann wahlweise inkrementell erfolgen („Nur geänderte
   Dateien“) oder alles ersetzen.
- **Netzwerkmodus ist unverschlüsselt** und hat keine Benutzerverwaltung; gedacht für
   ein vertrauenswürdiges Heim-/Büro-LAN.
- **Rollback** deckt den letzten Einspiel-Lauf ab, nicht beliebig viele Generationen.
- **Sicherungen aufbewahren:** Die mit Zeitstempel gesicherten Dateien werden nicht
  automatisch gelöscht. Prüfen und entfernen Sie sie bei Bedarf selbst.

---

*SynchronizeTool 0.8.0 – Stand 17.11.2026*
