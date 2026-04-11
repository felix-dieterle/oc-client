# Protocol Roundtrip Tests

Dieses Verzeichnis enthaelt einen reproduzierbaren End-to-End-Test fuer das SSH-/TUI-Protokoll.

## Dateien
- `mock-opencode-cli.py`: Simuliert opencode-cli (Bubbletea-Frames, optional Windows-Prompt-Interleave).
- `roundtrip-test.py`: Fuehrt eine komplette SSH-Session aus und parst die Ausgabe mit derselben Logik wie in der App.

## Voraussetzungen
- Laufender SSH-Server auf `127.0.0.1:2222`
- Key-basierter Login fuer User `codespace`
- Python-Paket `paramiko`

## Ausfuehrung
```bash
python3 scripts/roundtrip-test.py
```

## Modi
- `normal`: regulaerer Ablauf
- `windows`: interleaved Windows-Prompt vor Antwortframes

Die Modi werden intern vom Harness nacheinander ausgefuehrt.

## Ziel
Der Test soll sichtbar machen,
1. welche Frames vom Debounce zusammengefasst werden,
2. welche Zeilen als Echo/Prompt gefiltert werden,
3. was am Ende als Assistant-Text in der Chat-Bubble landen wuerde.
