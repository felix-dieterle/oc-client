# Protocol Analysis: First Response and Prompt Handling

## Status (2026-04-11)
Die Hauptursache fuer den First-Response-Haenger wurde reproduzierbar nachgestellt und parserseitig adressiert.

Umgesetzt:
- `OpencodeProtocol` erkennt jetzt auch Codespaces-/Arrow-Prompts.
- Parsing faellt auf einen vorherigen sinnvollen Bubbletea-Frame zurueck, wenn der neueste Frame nach Filterung leer ist (nur Prompt/Echo).
- Zusatzt tests fuer Codespace-Prompt und Frame-Fallback.
- Voller SSH-Roundtrip-Harness mit Mock-CLI im `scripts`-Ordner.

## Root Cause (urspruenglich)
1. Shell-Prompt kann vor dem eigentlichen AI-Text kommen.
2. Prompt wird korrekt gefiltert.
3. Ergebnis wird dadurch teilweise leer.
4. Mit `if (result.isNotBlank())` wird in dem Flush kein Assistant-Update angelegt.

## Was gefixt wurde

### 1) Prompt-Erkennung erweitert
- Datei: `app/src/main/java/com/felix/occlient/viewmodel/OpencodeProtocol.kt`
- Neu: Regex fuer Codespace-/Arrow-Prompts (`@user ➜ ... $`)

### 2) Robustere Frame-Auswahl
- Datei: `app/src/main/java/com/felix/occlient/viewmodel/OpencodeProtocol.kt`
- Vorher: nur „letzter brauchbarer Frame“.
- Jetzt: mehrere Kandidaten; bevorzugt wird der erste mit nicht-leerem Assistant-Text.
- Effekt: trailing prompt-only Frames loeschen nicht mehr die sichtbare Antwort.

### 3) Tests erweitert
- Datei: `app/src/test/java/com/felix/occlient/viewmodel/OpencodeProtocolTest.kt`
- Neu:
  - Fallback auf vorherigen Frame bei prompt-only latest frame
  - Codespace-Prompt-Erkennung

## End-to-End Test Setup
- `scripts/mock-opencode-cli.py`: Mock fuer Bubbletea-Output, liest Prompt ueber PTY stdin.
- `scripts/roundtrip-test.py`: Simuliert SSH-Flow wie in der App (PTY, Debounce, Echo-/Prompt-Filter).

Zuletzt beobachteter Lauf:
- `normal`: PASS
- `windows`: PASS

## Offene Punkte / geplanter Fix-Plan

### P1: Ausgabebereinigung der Assistant-Bubble
Problem:
- Die Bubble enthaelt noch Teile von TUI-Header/Prompt (`opencode v1.0`, Trenner, `> ...`).

Plan:
1. In `OpencodeProtocol.parseCandidate` sichtbare UI-Zeilen gezielt filtern.
2. Muster fuer statische TUI-Header und Prompt-Zeilen als klar getrennte Regeln.
3. Unit-Tests fuer „nur Antworttext bleibt“.

### P2: Startup-Frames nicht als echte Assistant-Antwort darstellen
Problem:
- Fruehe „Initializing/Thinking“-Frames koennen als Assistant-Nachricht erscheinen.

Plan:
1. Guard in `ChatViewModel`: Antwortbubble erst nach validem Antwortinhalt oder bestehender Assistant-ID.
2. Optional Placeholder-Strategie (`Thinking...`) nur bei aktiver Verarbeitung.
3. UI-Verhalten per Instrumented/VM-Test absichern.

### P3: Build-/Test-Umgebung stabilisieren
Problem:
- Lokaler Gradle-Testlauf scheitert in dieser Umgebung mit Java-25-Thema.

Plan:
1. Java 21 fuer Gradle festlegen.
2. Android SDK-Pfad sauber in CI/Devcontainer setzen.
3. `:app:testDebugUnitTest` in CI verpflichtend fuer Parser-Tests.

## Relevante Dateien
- `app/src/main/java/com/felix/occlient/viewmodel/OpencodeProtocol.kt`
- `app/src/test/java/com/felix/occlient/viewmodel/OpencodeProtocolTest.kt`
- `scripts/mock-opencode-cli.py`
- `scripts/roundtrip-test.py`
