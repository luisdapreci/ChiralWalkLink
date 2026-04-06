import socket
import json
import pydirectinput
import threading
import time
import asyncio
import ctypes
import ctypes.wintypes
try:
    import websockets
    _WS_ENABLED = True
except ImportError:
    _WS_ENABLED = False
    print(" [WARN] 'websockets' not installed — calibration UI live link disabled.")
    print("        Run: pip install websockets")

UDP_IP   = "0.0.0.0"
UDP_PORT = 5005
WS_PORT  = 5006          # WebSocket port for the calibration browser UI

# ── WebSocket broadcast layer ────────────────────────────────────────────────
_ws_clients: set = set()
_ws_loop: asyncio.AbstractEventLoop | None = None

async def _ws_handler(ws):
    """Accept and hold a browser WebSocket connection."""
    _ws_clients.add(ws)
    addr = ws.remote_address[0] if ws.remote_address else "?"
    print(f" [CALIBRATION] Browser connected from {addr}")
    try:
        # Listen for threshold update messages from the calibration UI
        async for raw in ws:
            try:
                msg = json.loads(raw)
                if msg.get("event") == "set_thresholds":
                    global WALK_MAX_SPS, JOG_MAX_SPS
                    with lock:
                        WALK_MAX_SPS = float(msg.get("walk",   WALK_MAX_SPS))
                        JOG_MAX_SPS  = float(msg.get("sprint", JOG_MAX_SPS))
                    print(f" [CALIBRATION] Thresholds updated: walk<{WALK_MAX_SPS:.1f}  sprint>{JOG_MAX_SPS:.1f}")
            except Exception:
                pass
    finally:
        _ws_clients.discard(ws)
        print(f" [CALIBRATION] Browser disconnected")

async def _ws_serve():
    async with websockets.serve(_ws_handler, "0.0.0.0", WS_PORT):
        await asyncio.Future()  # run forever

def _start_ws_thread():
    global _ws_loop
    _ws_loop = asyncio.new_event_loop()
    asyncio.set_event_loop(_ws_loop)
    _ws_loop.run_until_complete(_ws_serve())

def ws_broadcast(payload: str):
    """Thread-safe: send a JSON string to all connected browser clients."""
    if not _WS_ENABLED or not _ws_loop or not _ws_clients:
        return
    async def _send():
        dead = set()
        for client in list(_ws_clients):
            try:
                await client.send(payload)
            except Exception:
                dead.add(client)
        _ws_clients.difference_update(dead)
    asyncio.run_coroutine_threadsafe(_send(), _ws_loop)
# ─────────────────────────────────────────────────────────────────────────────

# Duration to keep "W" held after the last step arrives.
HOLD_DURATION = 0.55

# ── Speed mode thresholds (tunable live from calibration UI) ─────────────────
# walk   : W + Left Ctrl   (cadence < WALK_MAX_SPS)
# jog    : W only          (WALK_MAX_SPS ≤ cadence < JOG_MAX_SPS)
# sprint : W + Left Shift  (cadence ≥ JOG_MAX_SPS)
WALK_MAX_SPS = 1.4   # steps/sec below which = walk
JOG_MAX_SPS  = 2.2   # steps/sec above which = sprint

# ── Cadence rolling window ───────────────────────────────────────────────────
CADENCE_WINDOW_MS   = 3000   # ms — look back this far
CADENCE_WINDOW_SIZE = 6      # keep at most this many timestamps
_step_timestamps: list = []  # guarded by lock

# ── Speed mode → modifier keys ──────────────────────────────────────────────
MODE_MODIFIERS = {
    "walk":   "lctrl",
    "jog":    None,
    "sprint": "lshift",
}

last_step_time = 0
key_pressed    = False
active_mode    = "jog"
active_mod     = None
lock           = threading.Lock()

# ── Game window guard ────────────────────────────────────────────────────────
# Keys are only injected when one of these strings appears in the foreground
# window title (case-insensitive). Add/adjust if the game title differs.
GAME_WINDOW_TITLES = [
    "death stranding",
    "ds2",
]

def _user32():
    return ctypes.windll.user32

def get_foreground_title() -> str:
    """Return the title of the currently active window (lower-cased)."""
    hwnd = _user32().GetForegroundWindow()
    length = _user32().GetWindowTextLengthW(hwnd)
    if length == 0:
        return ""
    buf = ctypes.create_unicode_buffer(length + 1)
    _user32().GetWindowTextW(hwnd, buf, length + 1)
    return buf.value.lower()

def is_game_focused() -> bool:
    """Return True only when the game window has focus."""
    title = get_foreground_title()
    return any(name in title for name in GAME_WINDOW_TITLES)

# ─────────────────────────────────────────────────────────────────────────────

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP


def _release_modifier(mod):
    """Release a modifier key if one is currently held."""
    if mod is not None:
        pydirectinput.keyUp(mod)


def _press_modifier(mod):
    """Press a modifier key."""
    if mod is not None:
        pydirectinput.keyDown(mod)


def apply_mode(new_mode):
    """
    Transition to new_mode:
      1. Release old modifier (if any)
      2. Press new modifier (if any)
    Called while holding 'lock'.
    """
    global active_mode, active_mod
    new_mod = MODE_MODIFIERS.get(new_mode, None)

    if new_mod != active_mod:
        _release_modifier(active_mod)
        _press_modifier(new_mod)
        active_mod  = new_mod
        active_mode = new_mode
        print(f" [MODE] Switched to {new_mode.upper()} (modifier: {new_mod or 'none'})")
    elif new_mode != active_mode:
        active_mode = new_mode


def classify_mode(sps: float) -> str:
    """Map steps/sec to walk / jog / sprint."""
    if sps < WALK_MAX_SPS:
        return "walk"
    if sps < JOG_MAX_SPS:
        return "jog"
    return "sprint"


def register_step() -> str:
    """
    Record a new step timestamp, prune the rolling window, compute cadence,
    classify and return the current speed mode.
    """
    global _step_timestamps
    now = time.time() * 1000  # ms
    _step_timestamps.append(now)

    # Prune old entries
    cutoff = now - CADENCE_WINDOW_MS
    _step_timestamps = [t for t in _step_timestamps if t >= cutoff]
    if len(_step_timestamps) > CADENCE_WINDOW_SIZE:
        _step_timestamps = _step_timestamps[-CADENCE_WINDOW_SIZE:]

    if len(_step_timestamps) < 2:
        return active_mode   # not enough data yet — keep current mode

    span = _step_timestamps[-1] - _step_timestamps[0]
    sps  = (len(_step_timestamps) - 1) * 1000 / span if span > 0 else 0
    return classify_mode(sps)


def _release_all_keys():
    """Unconditionally release W and any held modifier. Called without lock."""
    global key_pressed, active_mod
    if key_pressed:
        pydirectinput.keyUp('w')
        key_pressed = False
    if active_mod is not None:
        _release_modifier(active_mod)
        active_mod = None


def key_manager():
    global last_step_time, key_pressed, active_mod
    while True:
        with lock:
            current_time  = time.time()
            game_active   = is_game_focused()
            step_is_fresh = (current_time - last_step_time < HOLD_DURATION
                             and last_step_time != 0)

            if step_is_fresh and game_active:
                # ── Movement active & game has focus ─────────────────────────
                if not key_pressed:
                    _press_modifier(active_mod)
                    pydirectinput.keyDown('w')
                    key_pressed = True
                    print(f" [DRAWBRIDGE] Walk Link Active → Holding 'W' [{active_mode.upper()}]")

            else:
                # ── Movement expired OR game lost focus ───────────────────────
                if key_pressed:
                    pydirectinput.keyUp('w')
                    _release_modifier(active_mod)
                    active_mod  = None
                    key_pressed = False
                    if not game_active and step_is_fresh:
                        print(" [GUARD] Game not in focus — keys suppressed")
                    else:
                        print(" [DRAWBRIDGE] Walk Link Standby → Releasing 'W'")

        time.sleep(0.05)


def start_server():
    global last_step_time, active_mode

    pydirectinput.FAILSAFE = False

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))

    threading.Thread(target=key_manager, daemon=True).start()

    # Start WebSocket server for calibration UI
    if _WS_ENABLED:
        threading.Thread(target=_start_ws_thread, daemon=True).start()

    local_ip = get_local_ip()
    print("\n")
    print("   [ DRAWBRIDGE TERMINAL INITIALIZING ]")
    print("-" * 52)
    print("    CHIRAL NETWORK NODE - WALK LINK RECEIVER      ")
    print("-" * 52)
    print(" > EXECUTING Q-PID CONNECTION PROTOCOL...")
    print(f" > REQUIRED TERMINAL IP  : {local_ip}")
    print(f" > DESIGNATED PORT       : {UDP_PORT}")
    print("-" * 52)
    print(f" > SPEED MODES: WALK (LCtrl) | JOG | SPRINT (LShift)")
    print(f" > GAME GUARD  : keys only sent when game window is focused")
    print(f"                 titles matched: {GAME_WINDOW_TITLES}")
    if _WS_ENABLED:
        print(f" > CALIBRATION UI WS   : ws://localhost:{WS_PORT}  (open calibration.html)")
    print("-" * 52)
    print(" [ AWAITING CHIRAL NETWORK HANDSHAKE... ]")
    print("\n")

    while True:
        data, addr = sock.recvfrom(1024)
        try:
            message = json.loads(data.decode("utf-8"))
            event   = message.get("event")

            if event == "step":
                with lock:
                    new_mode = register_step()
                    if new_mode != active_mode:
                        apply_mode(new_mode)
                    last_step_time = time.time()
                ws_broadcast(data.decode("utf-8"))  # relay raw step to calibration UI

            elif event == "stop":
                with lock:
                    last_step_time = 0  # Immediately release 'W'
                ws_broadcast(data.decode("utf-8"))  # relay to calibration UI

            elif event == "ping":
                response = json.dumps({"event": "pong"}).encode("utf-8")
                sock.sendto(response, addr)
                print(f" [CHIRAL NETWORK] Handshake accepted from {addr[0]}. Link Established.")

        except json.JSONDecodeError:
            print(f"[Packet] Received non-JSON or malformed data: {data}")


if __name__ == "__main__":
    try:
        start_server()
    except KeyboardInterrupt:
        print("\n [!] Connection severed. Server shutting down...")
        _release_all_keys()
