# SynchronizeTool (Version 0.8.0)

Kleines Java/Swing-Werkzeug, um ausgewählte Dateien und Verzeichnisse von Rechner 1 auf
Rechner 2 zu übertragen. Exportierte Pakete liegen als ZIP-Dateien vor und werden beim
Einspielen automatisch in einen temporären Ordner entpackt. Vor dem Überschreiben wird
der vorhandene Stand auf Rechner 2 mit einem Timestamp gesichert. Nach dem Kopieren wird
per SHA-256 geprüft, ob beide Stände wirklich identisch sind.

> **Ausführliche Schritt-für-Schritt-Anleitung:** [BEDIENUNGSANLEITUNG.md](BEDIENUNGSANLEITUNG.md)

## Funktionen

- Auswahl einzelner Dateien und ganzer Verzeichnisse über eine Swing-Oberfläche
- Konfiguration als lesbare `config.txt`
- Transport per USB-Stick/Netzlaufwerk **oder** direkt über das lokale Netzwerk
- Sicherung der Zieldateien mit Timestamp vor jedem Überschreiben
- **Dry Run**: Vorschau der Änderungen, ohne etwas anzufassen
- **Rollback**: letzte Synchronisation rückgängig machen
- **Ausschlussmuster** (`.gitignore`-ähnlich), z. B. `*.log`, `node_modules`
- **Kopiermodus wählbar**: nur geänderte Dateien oder alles ersetzen
- **Getrennte Protokolldateien** für Paketerstellung und Paketeinspielung, in der
  Oberfläche auswählbar und einsehbar
- **Gespeicherte Konfigurationen**: nach einem Neustart wieder auswählen und laden
- **Pakete kombinieren**: mehrere Pakete zu einem Gesamtpaket zusammenführen
- **Feste Programmordner** `Import`, `Export` und `Logfiles` mit mehreren benannten
  Paketen sowie Auswahl der vorhandenen Pakete über Dropdowns
- **ZIP-Pakete**: Export als `<Name>.zip`, Import mit automatischem Entpacken in einen
  temporären Ordner; alte entpackte Paketordner bleiben weiterhin lesbar
- SHA-256-Prüfung nach dem Kopieren

## Grundidee

Das Tool arbeitet mit benannten Paketen. Ein Paket ist eine ZIP-Datei, die im
Wurzelverzeichnis eine `config.txt` und den Ordner `payload/` enthält. Neben der
Jar-Datei liegen feste Programmordner:

```text
SynchronizeTool/
├── synchronize-tool-0.8.0.jar
├── Export/
│   ├── Paket1.zip          # config.txt + payload/ im ZIP
│   ├── Paket2.zip
│   └── JavaPaket.zip
├── Import/
│   └── JavaPaket.zip       # von Rechner 1 hierher kopiert
└── Logfiles/
    ├── Erstellung/
    └── Einspielung/
```

Aufbau einer Paket-ZIP:

```text
Paket1.zip
├── config.txt              # ausgewählte Pfade + Ausschlussmuster
└── payload/                # 1:1-Kopie der ausgewählten Daten
```

- **Rechner 1 (Quelle):** Paket in `Export` wählen oder neu anlegen, Daten auswählen →
  `Paket erstellen`.
- **Transport:** die gewünschte ZIP-Datei aus `Export` nach `Import` auf Rechner 2
  kopieren – oder im Reiter „Netzwerk“ direkt freigeben.
- **Rechner 2 (Ziel):** Paket-ZIP in `Import` auswählen → `Vorschau` und/oder
  `Synchronisieren`. Vor Vorschau und Einspielen wird die ZIP automatisch in einen
  temporären Ordner entpackt und danach wieder gelöscht.

Alte, bereits entpackte Paketordner mit `config.txt` und `payload/` werden weiterhin
erkannt und gelesen; neu erzeugt werden nur noch ZIP-Dateien.

Das Programmverzeichnis ist der Ordner der laufenden Jar-Datei. Wird das Tool aus einer
IDE gestartet, gilt das aktuelle Arbeitsverzeichnis. `Import`, `Export` und `Logfiles`
werden beim Start automatisch angelegt. Für den echten Einsatz sollte die Jar-Datei in
einem eigenen, beschreibbaren Ordner liegen.

Beim Einspielen passiert Folgendes – abhängig vom gewählten **Kopiermodus**:

1. Vorhandene Datei/Verzeichnis wird mit Timestamp gesichert:
   - `settings.ini` → `settings.ini_20261116_143000`
   - `meinprogramm/` → `meinprogramm_20261116_143000/`
   - im Modus **Nur geänderte Dateien** entsteht die Sicherung nur für tatsächlich
     geänderte oder entfernte Dateien.
2. Die Paket-ZIP wird in einen temporären Ordner entpackt; die neue Version aus
   `payload/` wird an denselben logischen Pfad kopiert.
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

1. Unter **Paket in Export** steht das aktuell gewählte Paket. Das Dropdown listet alle
   vollständigen Pakete unter `Export` – ZIP-Dateien und alte Paketordner. **Laden**
   übernimmt die zugehörige Konfiguration nach einer Sicherheitsabfrage;
   **Aktualisieren** liest die Liste neu ein.
2. Das Feld **Paket-ZIP** zeigt den vollständigen Pfad. Über **...** lässt sich auch ein
   Paket außerhalb von `Export` wählen. Der übergeordnete Ordner `Export` selbst kann
   nicht als Paket verwendet werden.
3. Mit **Datei hinzufügen** bzw. **Verzeichnis hinzufügen** die gewünschten Daten
   auswählen. Doppelte und verschachtelte Auswahlen werden automatisch bereinigt.
4. **Ausschlüsse...** öffnet die Musterverwaltung (siehe unten).
5. **Konfiguration speichern...** schreibt nur eine separate `config.txt` (z. B. zum
   Ablegen unter Versionskontrolle) und überschreibt niemals die Paket-ZIP.
6. **Paket erstellen (Export)** erzeugt die Paket-ZIP mit `config.txt` und `payload/`.
   Existiert die ZIP bereits, wird sie nach Bestätigung ersetzt. Nicht mehr ausgewählte
   Daten sind anschließend nicht mehr im Paket enthalten.
7. **Neues Paket...** fragt nur den Paketnamen ab und legt `Export/<Paketname>.zip` an.
   Optional wird die aktuelle Auswahl übernommen; ohne Häkchen startet das neue Paket
   leer (die Ausschlussmuster bleiben erhalten). So lassen sich mehrere Pakete parallel
   pflegen, z. B. `Paket1.zip`, `Paket2.zip` oder `JavaPaket.zip`. Die Endung `.zip`
   wird bei Bedarf automatisch ergänzt; ein bereits vorhandener Name wird abgelehnt.

### Gespeicherte Konfigurationen

Über der Auswahlliste im Reiter „Quelle“ befindet sich das Feld
**Gespeicherte Konfigurationen**. Es bleibt auch nach einem Neustart erhalten und
enthält die zuletzt verwendeten Pakete und `config.txt`-Dateien, das neueste zuerst.

- **Laden** öffnet die markierte Konfiguration. Deren Dateien/Verzeichnisse und
  Ausschlussmuster ersetzen die aktuelle Auswahl; das passende Paket wird automatisch
  gesetzt. Anschließend lässt sich die Auswahl anpassen und das Paket über
  **Paket erstellen (Export)** neu schreiben.
- **Öffnen...** wählt eine Paket-ZIP, einen alten Paketordner oder eine `config.txt`
  direkt vom Dateisystem. Bei einem Ordner wird die darin liegende `config.txt` geladen.
- **Aus Liste entfernen** blendet einen Eintrag nur aus der Liste aus; Dateien und
  Paket bleiben unverändert.

Die Liste wird automatisch ergänzt, wenn ein Paket exportiert, ein neues Paket angelegt,
ein Paket im Netzwerk freigegeben oder eine Konfiguration gespeichert/geladen wird.
Gespeichert wird sie unter:

```text
~/.synchronizetool/recent-configs.txt
```

Sie enthält maximal 25 Einträge, doppelte Pfade werden zusammengefasst. Fehlt ein Paket
inzwischen, bleibt der Eintrag sichtbar und wird mit „(nicht gefunden)“ markiert.

Beim Laden gilt: Eine Paket-ZIP wird direkt gelesen, bei einem Ordner wird die
`config.txt` darin verwendet. Eine separat abgelegte Konfigurationsdatei lässt sich
weiterhin laden; als Ziel für einen anschließenden Export wird dann
`Export/Paket1.zip` – oder das erste vorhandene Paket – eingetragen und ein Hinweis ins
Protokoll geschrieben. So bleibt eine separate Konfiguration nutzbar, ohne ein
bestehendes Paket zu überschreiben.

### Mehrere Pakete kombinieren

Über **Pakete kombinieren...** im Reiter „Quelle“ lassen sich mehrere vorhandene Pakete
zu einem Gesamtpaket zusammenführen, zum Beispiel Paket 1, 3 und 5.

1. Links die gewünschten Pakete markieren und mit **Hinzufügen →** nach rechts
   übernehmen. **Weitere...** ergänzt ein Paket, das nicht in der Liste steht.
2. Rechts die Reihenfolge festlegen: Das **oberste** Paket hat Vorrang, wenn dieselbe
   Datei in mehreren Paketen vorkommt. **Nach oben** bzw. **Nach unten** ändert die
   Reihenfolge.
3. Nur den Paketnamen für das Gesamtpaket wählen. Es wird als
   `Export/<Paketname>.zip` angelegt; ein bereits vorhandenes Ziel wird nicht
   überschrieben.
4. **OK** entpackt die Quellpakete bei Bedarf, führt sie zusammen und schreibt die neue
   Paket-ZIP.

Dabei gilt:

- Verwendet werden nur vollständige Pakete mit `config.txt` und `payload/`, sowohl als
  ZIP als auch als alter Paketordner.
- Die Ausschlussmuster aller Pakete werden zusammengeführt und beim Zusammenbau
  angewendet.
- Gleichnamige Dateien aus dem obersten Paket überschreiben die Versionen darunter
  liegender Pakete; jede Überschneidung mit unterschiedlichem Inhalt erscheint im
  Protokoll.
- Die Daten stammen aus den Payloads der vorhandenen Pakete. Die ursprünglichen
  Quelldateien müssen nicht mehr existieren.
- Widersprüchliche Strukturen – etwa eine Datei und ein Verzeichnis am selben Pfad –
  werden mit einer Meldung abgelehnt.
- Das fertige Gesamtpaket wird direkt im Quelle-Reiter geladen und kann wie jedes
  andere Paket auf Rechner 2 über den Reiter „Ziel“ eingespielt werden.

### Reiter „Ziel“

1. Unter **Paket in Import** das gewünschte Paket auswählen. Das Dropdown listet alle
   vollständigen Pakete unter `Import` – ZIP-Dateien und alte Paketordner; die Auswahl
   setzt den Paketpfad und zeigt die Konfiguration an. **Aktualisieren** liest die Liste
   neu ein, z. B. nachdem eine Paket-ZIP manuell nach `Import` kopiert wurde.
2. Das Feld **Paket-ZIP** zeigt den vollständigen Pfad. Über **...** lässt sich auch ein
   Paket außerhalb von `Import` wählen. Der übergeordnete Ordner `Import` selbst kann
   nicht eingespielt werden.
3. **Konfiguration anzeigen** zeigt die Pfade und ihre Auflösung auf diesem Rechner.
   Bei einer ZIP wird die `config.txt` direkt aus dem Archiv gelesen.
4. Unter **Kopiermodus** wählen:
   - **Nur geänderte Dateien** (Standard): kopiert nur neue und geänderte Dateien.
   - **Alles ersetzen**: sichert und kopiert jeden Eintrag vollständig.
   Die Auswahl wird gespeichert und gilt auch für **Herunterladen & Synchronisieren**
   im Netzwerk-Reiter.
5. **Vorschau (Dry Run)** vergleicht, ohne etwas zu verändern. Die Paket-ZIP wird dafür
   temporär entpackt.
6. **Synchronisieren (Sicherung + Kopieren)** führt den Abgleich aus; auch hier wird die
   ZIP vorher temporär entpackt und danach wieder gelöscht.
7. **Letzte Synchronisation rückgängig machen** spielt die Sicherungen des letzten
   Laufs zurück.

Alle Schritte erscheinen im Protokoll unten. Zusätzlich wird für jeden Vorgang eine
Protokolldatei geschrieben (siehe [Protokolldateien](#protokolldateien)).

### Kopiermodi im Detail

**Nur geänderte Dateien** (Standard)

- Jede Datei wird per Größe und SHA-256 mit dem Ziel verglichen. Identische Dateien
  werden übersprungen; sie werden weder kopiert noch gesichert.
- Neue Dateien werden angelegt, geänderte Dateien vorher mit Timestamp gesichert.
- Dateien und leere Verzeichnisse, die im Ziel vorhanden sind, aber nicht mehr im
  Paket, werden mit Timestamp gesichert und entfernt. So bleibt das Ergebnis 1:1.
- Wird an einem Pfad der Typ gewechselt (Datei ↔ Verzeichnis), wird der alte Stand
  gesichert und der neue Typ angelegt.
- Ausgeschlossene Dateien und Verzeichnisse bleiben unangetastet.
- Neu angelegte Verzeichnisse werden im Rollback-Protokoll vermerkt, damit der
  Rollback sie wieder entfernen kann.
- Sicherungen des Werkzeugs (`name_yyyyMMdd_HHmmss`) werden bei späteren Läufen
  nicht als überzählige Dateien behandelt.

**Alles ersetzen**

- Jeder ausgewählte Eintrag wird als Ganzes mit Timestamp gesichert und anschließend
  vollständig neu kopiert – auch wenn er bereits identisch ist.
- Das entspricht dem bisherigen Verhalten und ist z. B. zum Auffrischen von
  Attributen oder für kleine Datenmengen sinnvoll.

In beiden Modi wird nach dem Kopieren per SHA-256 geprüft, ob der Zielstand dem
Payload entspricht. Die gewählte Option wird unter
`~/.synchronizetool/settings.txt` gespeichert und beim nächsten Start wieder
vorausgewählt.

## Protokolldateien

Für Paketerstellung und Paketeinspielung werden getrennte Protokolle geschrieben:

```text
<Programmordner>/Logfiles/
├── Erstellung/     # Paket erstellen, neues Paket, kombinieren, freigeben
└── Einspielung/    # Vorschau, Synchronisieren, Rollback, Netzwerk-Einspielung
```

- Jeder Vorgang erhält eine eigene Datei nach dem Muster
  `<yyyyMMdd_HHmmss>_<vorgang>.log`; bei Namensgleichheit wird `_2`, `_3` usw.
  angehängt.
- Die Dateien sind UTF-8-kodiert und enthalten einen Kopf mit Bereich, Vorgang,
  Zeitpunkt und Rechner sowie alle Meldungen des Laufs. Schreibfehler beim
  Protokollieren brechen die Synchronisation nicht ab.
- Nur herunterladen ohne Einspielen erzeugt kein Einspiel-Protokoll, weil dabei keine
  Dateien im Ziel verändert werden.
- Im Reiter **Protokolle** lässt sich der Bereich wählen, die Dateien werden neueste
  zuerst aufgelistet und der Inhalt schreibgeschützt angezeigt. **Aktualisieren** liest
  die Liste neu ein, **Logordner öffnen** zeigt den Ordner im Dateimanager.

Benutzerspezifische Zustände bleiben weiterhin unter `~/.synchronizetool` und werden
nicht mit dem Programmordner kopiert: `settings.txt` (Kopiermodus),
`recent-configs.txt` (zuletzt verwendete Konfigurationen) und `last-sync.txt`
(Rollback-Informationen).

Bestehende Pakete und Protokolle an den alten Speicherorten werden nicht automatisch
verschoben.

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
- Beim Einspielen bleiben ausgeschlossene Dateien im Ziel erhalten: Im Modus
  „Alles ersetzen“ werden sie aus der Sicherung zurückgeholt; im Modus „Nur geänderte
  Dateien“ werden sie gar nicht erst angefasst. So verschwinden z. B. lokale Logs oder
  Cache-Dateien nicht.

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
Im Modus „Alles ersetzen“ sind es die Sicherungen ganzer Einträge, im Modus „Nur
geänderte Dateien“ die einzelnen geänderten bzw. entfernten Dateien und Verzeichnisse.
**Letzte Synchronisation rückgängig machen** im Reiter „Ziel“:

- Sicherungen werden an ihren ursprünglichen Platz zurückverschoben.
- Neu angelegte Dateien und Verzeichnisse werden zur Seite gelegt.
- Der aktuell vorhandene Stand wird nicht gelöscht, sondern mit dem Zusatz
  `_rollback_<Timestamp>` zur Seite gelegt.
- Das Protokoll wird als `last-sync.txt.done_<Timestamp>` archiviert.
- Ein zweites Rollback direkt hintereinander ist dadurch nicht möglich.
- Das Rollback bezieht sich immer auf den letzten Einspiel-Lauf. Sicherungen früherer
  Läufe bleiben erhalten.

## Netzwerkmodus

Wenn beide Rechner im selben LAN sind, kann das Paket direkt übertragen werden.

### Rechner 1

1. Im Reiter **Netzwerk** einen Port wählen (Standard `8765`).
2. **Paket erstellen & freigeben** klicken. Das Tool erzeugt die aktuell in „Quelle“
   gewählte Paket-ZIP – eine vorhandene ZIP wird nach Bestätigung ersetzt – und gibt
   genau diese ZIP über einen kleinen HTTP-Server frei. Die angezeigte URL enthält einen
   zufälligen Token.
3. URL kopieren oder auf Rechner 2 abtippen.

### Rechner 2

1. Im Reiter **Netzwerk** die URL einfügen.
2. **Nur herunterladen**, **Herunterladen & Vorschau** oder
   **Herunterladen & Synchronisieren** wählen. Für das Einspielen gilt derselbe
   Kopiermodus, der im Reiter „Ziel“ ausgewählt ist.
3. Das Paket wird standardmäßig als
   `Import/Netzwerkpaket-<Zeitstempel>.zip` gespeichert. Ein anderer Speicherort kann im
   Feld **Paket-ZIP** gewählt werden; die Endung `.zip` wird bei Bedarf ergänzt,
   vorhandene Dateien werden nach Bestätigung ersetzt. Anschließend steht das Paket auch
   im Reiter „Ziel“ zur Auswahl. Beim Herunterladen mit Vorschau oder Synchronisieren
   wird die ZIP temporär entpackt.

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
java -jar target/synchronize-tool-0.8.0.jar
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
- **Kein differentieller Export:** Beim Export wird der komplette Payload neu
  geschrieben. Der Import kann wahlweise inkrementell erfolgen („Nur geänderte
  Dateien“) oder alles ersetzen.
- **Netzwerkmodus ist unverschlüsselt** und hat keine Benutzerverwaltung; gedacht für
  ein vertrauenswürdiges Heim-/Büro-LAN.
- **Rollback** deckt den letzten Einspiel-Lauf ab, nicht beliebig viele Generationen.

## Sinnvolle nächste Schritte

- Zeitgesteuerte oder automatische Synchronisation
- Profile für mehrere Rechner-Paare
- Verschlüsselte Netzwerkübertragung (HTTPS) und Authentifizierung
- Pfad-Mapping zwischen Windows und macOS/Linux
- Vergleichsbericht als Datei exportieren
