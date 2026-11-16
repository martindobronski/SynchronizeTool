# SynchronizeTool (Version 0.3.0)

Kleines Java/Swing-Werkzeug, um ausgewählte Dateien und Verzeichnisse von Rechner 1 auf
Rechner 2 zu übertragen. Vor dem Überschreiben wird der vorhandene Stand auf Rechner 2
mit einem Timestamp gesichert. Nach dem Kopieren wird per SHA-256 geprüft, ob beide
Stände wirklich identisch sind.

## Funktionen

- Auswahl einzelner Dateien und ganzer Verzeichnisse über eine Swing-Oberfläche
- Konfiguration als lesbare `config.txt`
- Transport per USB-Stick/Netzlaufwerk **oder** direkt über das lokale Netzwerk
- Sicherung der Zieldateien mit Timestamp vor jedem Überschreiben
- **Dry Run**: Vorschau der Änderungen, ohne etwas anzufassen
- **Rollback**: letzte Synchronisation rückgängig machen
- **Ausschlussmuster** (`.gitignore`-ähnlich), z. B. `*.log`, `node_modules`
- **Gespeicherte Konfigurationen**: nach einem Neustart wieder auswählen und laden
- SHA-256-Prüfung nach dem Kopieren

## Grundidee

Das Tool arbeitet mit einem transportablen Paket:

```
SyncTool-Paket/
├── config.txt          # ausgewählte Pfade + Ausschlussmuster
└── payload/            # 1:1-Kopie der ausgewählten Daten
    ├── HOME/.config/meinprogramm/...
    └── C/ProgramData/meinprogramm/settings.ini   (Beispiel Windows)
```

**Rechner 1 (Quelle):** Dateien/Verzeichnisse auswählen → `Paket erstellen`.  
**Transport:** Ordner per USB-Stick/Netzlaufwerk kopieren – oder im Reiter „Netzwerk“
direkt freigeben.  
**Rechner 2 (Ziel):** Paketverzeichnis wählen → `Vorschau` und/oder `Synchronisieren`.

Beim Einspielen passiert Folgendes:

1. Vorhandene Datei/Verzeichnis wird mit Timestamp umbenannt:
   - `settings.ini` → `settings.ini_20261116_143000`
   - `meinprogramm/` → `meinprogramm_20261116_143000/`
2. Die neue Version aus `payload/` wird an denselben logischen Pfad kopiert.
3. Per SHA-256 wird geprüft, ob die Inhalte übereinstimmen.

### Pfade und `${HOME}`

In der `config.txt` werden Pfade logisch gespeichert. Alles unter dem Benutzerverzeichnis
wird als `${HOME}/...` gespeichert und auf dem Zielrechner in dessen Benutzerverzeichnis
aufgelöst. So funktioniert z. B. `~/.config/meinprogramm` auf beiden Maschinen, auch wenn
die Benutzernamen unterschiedlich sind. Absolute Pfade außerhalb des Home-Verzeichnisses
werden unverändert übernommen (z. B. `C:/ProgramData/...` oder `/opt/...`) – dafür müssen
die Pfade auf beiden Rechnern gleich sein.

## Bedienung

### Reiter „Quelle“

1. Unter **Paketverzeichnis** steht standardmäßig `~/SyncTool-Paket`. Hier kann ein Pfad
   auf einem USB-Stick oder Netzlaufwerk angegeben werden.
2. Mit **Datei hinzufügen** bzw. **Verzeichnis hinzufügen** die gewünschten Daten
   auswählen. Doppelte und verschachtelte Auswahlen werden automatisch bereinigt.
3. **Ausschlüsse...** öffnet die Musterverwaltung (siehe unten).
4. **Konfiguration speichern...** schreibt nur die `config.txt`.
   **Paket erstellen (Export)** schreibt die `config.txt` *und* kopiert alle Daten in
   `payload/`. Beim erneuten Export wird der alte Payload eines Eintrags vollständig
   ersetzt, damit kein alter Rest übrig bleibt.
5. **Neues Paket...** legt ein weiteres Paket an einem frei wählbaren Speicherort an.
   Optional wird die aktuelle Auswahl übernommen; ohne Häkchen startet das neue Paket
   leer (die Ausschlussmuster bleiben erhalten). Das bisherige Paket bleibt unverändert.
   So lassen sich mehrere Pakete parallel pflegen, z. B. eines pro Anwendung. Der
   Paketname darf keine Pfadtrenner oder Sonderzeichen enthalten; ein bereits
   vorhandener Name wird abgelehnt.

### Gespeicherte Konfigurationen

Über der Auswahlliste im Reiter „Quelle“ befindet sich das Feld
**Gespeicherte Konfigurationen**. Es bleibt auch nach einem Neustart erhalten und
enthält die zuletzt verwendeten `config.txt`-Dateien, die neueste zuerst.

- **Laden** öffnet die markierte Konfiguration. Deren Dateien/Verzeichnisse und
  Ausschlussmuster ersetzen die aktuelle Auswahl; das passende Paketverzeichnis wird
  automatisch gesetzt. Anschließend lässt sich die Auswahl anpassen und das Paket über
  **Paket erstellen (Export)** neu schreiben.
- **Öffnen...** wählt einen Paketordner oder eine `config.txt` direkt vom Dateisystem.
  Bei einem Ordner wird die darin liegende `config.txt` geladen.
- **Aus Liste entfernen** blendet einen Eintrag nur aus der Liste aus; Dateien und
  Paket bleiben unverändert.

Die Liste wird automatisch ergänzt, wenn ein Paket exportiert, ein neues Paket angelegt,
ein Paket im Netzwerk freigegeben oder eine Konfiguration gespeichert/geladen wird.
Gespeichert wird sie unter:

```text
~/.synchronizetool/recent-configs.txt
```

Sie enthält maximal 25 Einträge, doppelte Pfade werden zusammengefasst. Fehlt eine
`config.txt` inzwischen, bleibt der Eintrag sichtbar und wird mit „(nicht gefunden)“
markiert.

Beim Laden gilt: Heißt die Datei `config.txt`, wird ihr Ordner als Paketverzeichnis
verwendet. Bei einem anderen Dateinamen wird als Paketverzeichnis `~/SyncTool-Paket`
eingetragen und ein Hinweis ins Protokoll geschrieben – so bleibt eine separat
abgelegte Konfiguration nutzbar, ohne ein bestehendes Paket zu überschreiben.

### Reiter „Ziel“

1. Paketverzeichnis auswählen.
2. **Konfiguration anzeigen** zeigt die Pfade und ihre Auflösung auf diesem Rechner.
3. **Vorschau (Dry Run)** vergleicht, ohne etwas zu verändern.
4. **Synchronisieren (Sicherung + Kopieren)** führt den Abgleich aus.
5. **Letzte Synchronisation rückgängig machen** spielt die Sicherungen des letzten
   Laufs zurück.

Alle Schritte erscheinen im Protokoll unten.

## Ausschlussmuster

Im Reiter „Quelle“ über **Ausschlüsse...** ein Muster pro Zeile eintragen:

```text
*.log
node_modules
cache
**/tmp/**
```

- `*` trifft innerhalb eines Pfadsegments, `?` genau ein Zeichen, `**` über
  Verzeichnisgrenzen hinweg.
- Muster ohne `/` gelten für jedes Pfadsegment, `node_modules` also auch für
  `a/node_modules/x.js`.
- Ausgeschlossene Dateien werden nicht ins Paket kopiert.
- Beim Einspielen bleiben ausgeschlossene Dateien im Ziel erhalten: Sie werden aus der
  Sicherung zurückgeholt, damit z. B. lokale Logs oder Cache-Dateien nicht verschwinden.

## Dry Run (Vorschau)

Im Reiter „Ziel“ auf **Vorschau (Dry Run)** klicken. Das Tool vergleicht den Payload mit
dem Ziel und meldet:

- `NEU` – existiert im Ziel noch nicht
- `GLEICH` – Inhalt ist bereits identisch
- `ÄNDERN` – wird ersetzt (mit Details: hinzugefügt / geändert / entfernt)
- `PROBLEM` – z. B. fehlende Daten im Paket
- wie viele Sicherungen angelegt würden

Es wird dabei **nichts** verändert, auch keine Sicherung erstellt.

## Rollback

Nach jedem Einspiel-Lauf schreibt das Tool auf dem Zielrechner:

```text
~/.synchronizetool/last-sync.txt
```

Darin steht, welche Datei unter welchem Sicherungsnamen liegt (oder neu angelegt wurde).
**Letzte Synchronisation rückgängig machen** im Reiter „Ziel“:

- Sicherungen werden an ihren ursprünglichen Platz zurückverschoben.
- Der aktuell vorhandene Stand wird nicht gelöscht, sondern mit dem Zusatz
  `_rollback_<Timestamp>` zur Seite gelegt.
- Das Protokoll wird als `last-sync.txt.done_<Timestamp>` archiviert.
- Ein zweites Rollback direkt hintereinander ist dadurch nicht möglich.

## Netzwerkmodus

Wenn beide Rechner im selben LAN sind, kann das Paket direkt übertragen werden.

### Rechner 1

1. Im Reiter **Netzwerk** einen Port wählen (Standard `8765`).
2. **Paket erstellen & freigeben** klicken. Das Tool exportiert das Paket und startet
   einen kleinen HTTP-Server. Die angezeigte URL enthält einen zufälligen Token.
3. URL kopieren oder auf Rechner 2 abtippen.

### Rechner 2

1. Im Reiter **Netzwerk** die URL einfügen.
2. **Nur herunterladen**, **Herunterladen & Vorschau** oder
   **Herunterladen & Synchronisieren** wählen.
3. Das Paket wird nach `~/SyncTool-Download` geladen und kann auch im Reiter „Ziel“
   weiterverwendet werden.

Der Token schützt vor versehentlichem Zugriff, die Übertragung ist aber **unverschlüsselt
(HTTP)**. Nur in vertrauenswürdigen Netzen verwenden. Bei Bedarf muss der Port in der
Firewall freigegeben werden.

## Konfigurationsformat

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

Die Datei wird beim Export automatisch ins Paket geschrieben und kann zusätzlich über
**Konfiguration speichern...** separat abgelegt werden.

## Bauen und starten

Voraussetzung: JDK 17 oder neuer.

```bash
mvn package
java -jar target/synchronize-tool-0.3.0.jar
```

Ohne Maven geht es auch direkt:

```bash
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
java -cp out de.synchronizetool.Main
```

## Wichtige Grenzen

- **Installierte Programme:** Reines Dateikopieren überträgt keine Windows-Registry,
  keine Dienste, keine Treiber und keine Berechtigungen. Für portable Programme,
  Einstellungen und Konfigurationsdateien funktioniert das 1:1-Prinzip gut; für echte
  Installationen braucht es zusätzlich MSI-/PKG-Deployment oder Werkzeuge wie
  Ansible/Chef.
- **Gleiches Betriebssystem empfohlen:** Absolute Pfade werden nicht zwischen Windows und
  macOS/Linux umgerechnet (`C:/...` wird auf Unix als relativer Pfad interpretiert).
- **Symbolische Links** innerhalb eines Verzeichnisses werden übersprungen und mit einem
  Hinweis protokolliert. Ist der ausgewählte Pfad selbst ein Link, wird sein Ziel kopiert.
- **Offene/gesperrte Dateien** (z. B. eine laufende Datenbank) können nicht kopiert werden.
- **Kein differentieller Transfer:** Beim Export wird der komplette Payload neu
  geschrieben, beim Import vollständig kopiert. Der Dry Run vergleicht zwar per Hash,
  kopiert aber noch nicht inkrementell.
- **Netzwerkmodus ist unverschlüsselt** und hat keine Benutzerverwaltung; gedacht für
  ein vertrauenswürdiges Heim-/Büro-LAN.
- **Rollback** deckt den letzten Einspiel-Lauf ab, nicht beliebig viele Generationen.

## Sinnvolle nächste Schritte

- Inkrementeller Abgleich: nur geänderte Dateien kopieren
- Zeitgesteuerte oder automatische Synchronisation
- Profile für mehrere Rechner-Paare
- Verschlüsselte Netzwerkübertragung (HTTPS) und Authentifizierung
- Pfad-Mapping zwischen Windows und macOS/Linux
- Vergleichsbericht als Datei exportieren
