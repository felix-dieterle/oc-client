#!/usr/bin/env python3
"""
Full SSH Roundtrip Test
=======================
Simulates exactly what the Android oc-client app does via JSch:
1. Connect SSH (ChannelShell, PTY=xterm-256color)
2. Send 'opencode-cli' command (with \\r line ending)
3. Debounce incoming output (500ms window)
4. Parse TUI output using the same logic as OpencodeProtocol.kt
5. Show what the chat bubble would display

Tests both normal and Windows-bug scenarios.
"""

import paramiko
import time
import threading
import re
import queue
import sys
import os

# ── Config ────────────────────────────────────────────────────────────────────
PORT            = 2222
HOST            = '127.0.0.1'
USERNAME        = 'codespace'
KEY_PATH        = '/tmp/test_oc_key'
MOCK_SCRIPT     = '/workspaces/oc-client/scripts/mock-opencode-cli.py'
STARTUP_DELAY   = 1.0   # mirrors ChatViewModel.OPENCODE_STARTUP_DELAY_MS
DEBOUNCE_MS     = 0.5   # mirrors ChatViewModel.OUTPUT_DEBOUNCE_MS
SSH_POLL_MS     = 0.05  # mirrors SshManager.SSH_READ_POLL_INTERVAL_MS

# ── ANSI / protocol parsing (mirrors OpencodeProtocol.kt) ─────────────────────
_CSI   = re.compile(r'\x1b\[[\x20-\x3f]*[\x40-\x7e]')
_OSC   = re.compile(r'\x1b\][^\x07]*\x07')
_OTHER = re.compile(r'\x1b[^\[\]]')

def ansi_strip(text: str) -> str:
    text = _CSI.sub('', text)
    text = _OSC.sub('', text)
    text = _OTHER.sub('', text)
    return text.replace('\r', '')

WIN_PROMPT = re.compile(r'^(?:PS )?(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+ )?[A-Za-z]:\\[^\n]*>\s*$')
UNIX_PROMPT= re.compile(r'^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^\n]*[#$]\s*$')
CODESPACE_PROMPT = re.compile(r'^@[A-Za-z0-9_.-]+\s+[➜→❯][^\n]*[#$]\s*$')

def extract_screen_candidates(raw: str) -> list[str]:
    hide = '\x1b[?25l'
    candidates = []
    pos  = len(raw)
    while pos > 0:
        p = raw.rfind(hide, 0, pos)
        if p < 0:
            break
        cand = raw[p:]
        if ansi_strip(cand).strip():
            candidates.append(cand)
        pos = p
    if not candidates:
        candidates.append(raw)
    return candidates


def _parse_lines(text: str, pending_echos: dict, consume: bool) -> dict:
    saw_prompt = False
    echo_drop = []
    prompt_drop = []
    kept = []

    for line in text.split('\n'):
        s = line.strip()
        if not s:
            continue
        # exact echo
        if s in pending_echos:
            echo_drop.append(s)
            if consume:
                pending_echos[s] -= 1
                if pending_echos[s] <= 0:
                    del pending_echos[s]
            continue
        # prompt-prefixed echo
        found = False
        for cmd in list(pending_echos):
            if any(s.endswith(suf + cmd) for suf in ('>', '> ', '$', '$ ', '#', '# ')):
                echo_drop.append(s)
                if consume:
                    pending_echos[cmd] -= 1
                    if pending_echos[cmd] <= 0:
                        del pending_echos[cmd]
                found = True
                break
        if found:
            continue
        # shell prompt line
        if WIN_PROMPT.match(s) or UNIX_PROMPT.match(s) or CODESPACE_PROMPT.match(s):
            saw_prompt = True
            prompt_drop.append(s)
            continue
        kept.append(s)

    return {
        'assistant_text': '\n'.join(kept).strip(),
        'saw_shell_prompt': saw_prompt,
        'echo_dropped': echo_drop,
        'prompt_dropped': prompt_drop,
        'considered_line_count': len(text.split('\n')),
    }

def parse_output(raw: str, pending_echos: dict) -> dict:
    candidates = extract_screen_candidates(raw)
    selected = None
    for cand in candidates:
        probe = _parse_lines(ansi_strip(cand), dict(pending_echos), consume=False)
        if probe['assistant_text']:
            selected = cand
            break
    if selected is None:
        selected = candidates[0]

    parsed = _parse_lines(ansi_strip(selected), pending_echos, consume=True)

    return {
        'assistant_text':   parsed['assistant_text'],
        'saw_shell_prompt': parsed['saw_shell_prompt'],
        'raw_len':          len(raw),
        'extracted_len':    len(selected),
        'echo_dropped':     parsed['echo_dropped'],
        'prompt_dropped':   parsed['prompt_dropped'],
    }

# ── SSH roundtrip runner ──────────────────────────────────────────────────────
class RoundtripTest:
    def __init__(self, mode: str):
        self.mode           = mode
        self.received_bytes = 0
        self.raw_log        = []
        self.flush_queue    = queue.Queue()
        self._pending       = ''
        self._lock          = threading.Lock()
        self._debounce_timer= None
        self.messages       = []       # what the chat would show
        self.pending_echos  = {}
        self.is_processing  = False

    def _on_output(self, chunk: str):
        """Mirrors SshManager.onOutputReceived → ChatViewModel.processOutput"""
        with self._lock:
            self._pending += chunk
            self.received_bytes += len(chunk)
            self.raw_log.append(('rx', chunk))

        if self._debounce_timer:
            self._debounce_timer.cancel()
        self._debounce_timer = threading.Timer(DEBOUNCE_MS, self._flush)
        self._debounce_timer.start()

    def _flush(self):
        """Mirrors ChatViewModel.flushOutput"""
        with self._lock:
            raw = self._pending
            self._pending = ''
        if not raw:
            return

        result = parse_output(raw, self.pending_echos)

        # ── PROTOCOL TRACE ──────────────────────────────────────────────────
        print(f"\n  [PROTO] rawLen={result['raw_len']} extractedLen={result['extracted_len']}"
              f" echo_drop={result['echo_dropped']} prompt_drop={result['prompt_dropped']}"
              f" sawPrompt={result['saw_shell_prompt']}")
        print(f"  [PROTO] assistantText={result['assistant_text']!r}")

        if result['saw_shell_prompt'] and self.is_processing:
            print(f"  [PROTO] ← Prompt arrived, still waiting for AI response...")

        if result['assistant_text']:
            self.is_processing = False
            self.messages.append({'type': 'ASSISTANT', 'text': result['assistant_text']})
            print(f"\n  ★ Chat bubble: {result['assistant_text']!r}")
        else:
            print(f"  ★ Chat bubble: (none – empty after filtering)")

    def run(self, user_prompt: str):
        print(f"\n{'═'*70}")
        print(f"  ROUNDTRIP TEST: mode={self.mode!r}")
        print(f"  User input: {user_prompt!r}")
        print(f"{'═'*70}")

        # 1. SSH connect
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        key = paramiko.Ed25519Key.from_private_key_file(KEY_PATH)
        print(f"\n[1] Connecting SSH to {HOST}:{PORT}...")
        client.connect(HOST, PORT, USERNAME, pkey=key, look_for_keys=False, allow_agent=False)
        print(f"    Connected ✓")

        # 2. Open shell channel with PTY (mirrors ChannelShell.setPtyType)
        chan = client.get_transport().open_session()
        chan.get_pty(term='xterm-256color', width=220, height=50)
        chan.invoke_shell()
        print(f"[2] Shell channel opened ✓")

        # 3. Start our mock opencode in the shell
        time.sleep(STARTUP_DELAY)

        # Track echo for the launcher command
        self.pending_echos['opencode-cli'] = 1

        env = f'MOCK_MODE={self.mode}'
        launch_cmd = f'MOCK_MODE={self.mode} MOCK_PROMPT="{user_prompt}" python3 {MOCK_SCRIPT}\r'
        chan.send(launch_cmd.encode())
        self.raw_log.append(('tx', f'python3 mock-opencode-cli.py [{self.mode}]'))
        print(f"[3] Sent: opencode-cli launch (mode={self.mode}) ✓")

        # 4. Read thread – mirrors SshManager.startReading
        def reader():
            buf = bytearray()
            while True:
                if chan.closed:
                    break
                if chan.recv_ready():
                    data = chan.recv(4096)
                    if not data:
                        break
                    chunk = data.decode('utf-8', errors='replace')
                    self._on_output(chunk)
                else:
                    time.sleep(SSH_POLL_MS)

        t = threading.Thread(target=reader, daemon=True)
        t.start()

        # Wait for mock to initialize
        time.sleep(0.5)

        # 5. Send user prompt with \r (mirrors SshManager.sendCommand)
        print(f"[4] Waiting for opencode-cli to start (0.5s)...")
        self.pending_echos[user_prompt.strip()] = 1
        chan.send((user_prompt + '\r').encode())
        self.raw_log.append(('tx', user_prompt))
        self.is_processing = True
        print(f"[5] Sent user prompt: {user_prompt!r} (with \\r) ✓")

        # 6. Wait for response + final debounce
        print(f"[6] Waiting for response (4.5s)...")
        time.sleep(4.5)

        # Ensure last debounce fires
        if self._debounce_timer:
            self._debounce_timer.cancel()
        self._flush()

        chan.close()
        client.close()

        # 7. Results
        print(f"\n{'─'*70}")
        print(f"  RESULTS  (mode={self.mode})")
        print(f"{'─'*70}")
        print(f"  Total bytes received : {self.received_bytes}")
        print(f"  SSH tx frames        : {sum(1 for e in self.raw_log if e[0]=='tx')}")
        print(f"  SSH rx frames        : {sum(1 for e in self.raw_log if e[0]=='rx')}")
        print(f"  Chat messages shown  : {len(self.messages)}")

        if self.messages:
            for i, m in enumerate(self.messages, 1):
                print(f"\n  Message {i} [{m['type']}]:")
                for line in m['text'].split('\n'):
                    print(f"    {line}")
            print(f"\n  ✓ SUCCESS – chat shows response")
        else:
            print(f"\n  ✗ FAILURE – chat is EMPTY after full roundtrip")
            print(f"    → User would see: spinner without any message")

        return len(self.messages) > 0


# ── Main ─────────────────────────────────────────────────────────────────────
if __name__ == '__main__':
    prompt = "Was ist 2+2?"
    results = {}

    print("╔══════════════════════════════════════════════════════════════════════╗")
    print("║       OC-CLIENT FULL SSH ROUNDTRIP PROTOCOL TEST                    ║")
    print("╚══════════════════════════════════════════════════════════════════════╝")
    print(f"\nTest scenario: SSH → opencode-cli start → prompt → parse → chat bubble")
    print(f"Library:       paramiko 4 (equiv. to JSch in Android)")
    print(f"Parsing:       exact mirror of OpencodeProtocol.kt + ChatViewModel.kt")

    # Test A: normal mode
    test_a = RoundtripTest(mode='normal')
    results['normal'] = test_a.run(prompt)

    # Test B: windows bug mode
    test_b = RoundtripTest(mode='windows')
    results['windows'] = test_b.run(prompt)

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'═'*70}")
    print(f"  FINAL SUMMARY")
    print(f"{'═'*70}")
    print(f"  normal  mode: {'✓ PASS' if results['normal']  else '✗ FAIL – response empty'}")
    print(f"  windows mode: {'✓ PASS' if results['windows'] else '✗ FAIL – response empty'}")
    if results['normal'] and not results['windows']:
        print(f"\n  → CONFIRMED: Windows-host bug reproduced.")
        print(f"    Shell prompt arrives before AI response.")
        print(f"    Chat stays empty because assistantText = '' after filtering.")
        print(f"    isProcessing stays True → spinner never clears.")
    elif results['normal'] and results['windows']:
        print(f"\n  → Both modes work – check debounce timing or real behavior.")
    print(f"{'═'*70}")
