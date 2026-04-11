#!/usr/bin/env python3
"""
Mock opencode-cli – time-triggered TUI simulation.

Does NOT read from stdin (PTY stdin in a subprocess is unreliable).
Instead emits all frames automatically on a fixed timeline, controlled
by MOCK_MODE env var, so the roundtrip test can observe the full
SSH output sequence the Android app would receive.

MOCK_MODE=normal    – clean TUI startup + streaming response frames
MOCK_MODE=windows   – TUI startup + Windows shell prompt BEFORE response
                      (reproduces the first-chat hang on Windows hosts)
MOCK_MODE=startup_only – only startup frames, no response (tests spinner)
"""
import os
import select
import sys
import time

E  = '\x1b'
HC = f'{E}[?25l'   # hide cursor  (Bubbletea frame start marker)
SC = f'{E}[?25h'   # show cursor  (Bubbletea frame end marker)
CL = f'{E}[2J{E}[H'  # clear + home

def w(s: str):
    sys.stdout.write(s)
    sys.stdout.flush()

def tui_frame(content: str):
    """Emit one full Bubbletea redraw frame."""
    w(HC + CL + content + SC)

MODE        = os.environ.get('MOCK_MODE', 'normal')
USER_PROMPT = os.environ.get('MOCK_PROMPT', 'Was ist 2+2?')


def read_user_prompt(timeout_s: float = 3.0) -> str:
    """Read one line from PTY stdin; fallback to MOCK_PROMPT on timeout."""
    fd = sys.stdin.fileno()
    deadline = time.time() + timeout_s
    buf = ''

    while time.time() < deadline:
        wait = max(0.0, min(0.2, deadline - time.time()))
        r, _, _ = select.select([fd], [], [], wait)
        if fd not in r:
            continue

        chunk = os.read(fd, 1024)
        if not chunk:
            break
        buf += chunk.decode('utf-8', errors='replace')
        if '\n' in buf or '\r' in buf:
            break

    if not buf:
        return USER_PROMPT

    line = buf.replace('\r', '\n').split('\n', 1)[0].strip()
    return line or USER_PROMPT

# ── Phase 1: TUI startup ──────────────────────────────────────────────────────
tui_frame(
    " opencode  v1.0\n"
    "────────────────────────────────────────\n\n"
    " Initializing...\n"
)
time.sleep(0.25)

tui_frame(
    " opencode  v1.0\n"
    "────────────────────────────────────────\n\n"
    " > \n"
    " Waiting for prompt...\n"
)
time.sleep(0.20)

USER_PROMPT = read_user_prompt(3.0)

tui_frame(
    " opencode  v1.0\n"
    "────────────────────────────────────────\n\n"
    f" > {USER_PROMPT}\n"
    " Thinking...\n"
)
time.sleep(0.30)

if MODE == 'startup_only':
    sys.exit(0)

# ── Phase 2: optional Windows shell prompt frame ───────────────────────────────
if MODE == 'windows':
    # opencode momentarily returns to shell on Windows before TUI response
    w(SC + '\r\n')
    time.sleep(0.05)
    w('PS C:\\Users\\coder\\workspace> \r\n')
    time.sleep(0.15)

# ── Phase 3: streaming AI response frames (like real Bubbletea token streaming) ─
ANSWER = "Die Antwort auf deine Frage ist 4."
tokens = ['D', 'ie ', 'Ant', 'wort ', 'auf ', 'deine ', 'Frage ', 'ist ', '4.']
accumulated = ''
for token in tokens:
    accumulated += token
    tui_frame(
        " opencode  v1.0\n"
        "────────────────────────────────────────\n\n"
        f" > {USER_PROMPT}\n\n"
        f" {accumulated}▌\n"
    )
    time.sleep(0.07)

# Final complete frame (no cursor blink)
tui_frame(
    " opencode  v1.0\n"
    "────────────────────────────────────────\n\n"
    f" > {USER_PROMPT}\n\n"
    f" {ANSWER}\n\n"
    " ──────────────────────────────────────\n"
    " > "
)
time.sleep(0.10)

# Trailing ANSI-only cleanup frame (common in Bubbletea, causes issues)
w(HC + f'{E}[2K' + SC)
time.sleep(0.05)
