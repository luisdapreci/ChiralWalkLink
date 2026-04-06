import socket
import json
import pydirectinput
import threading
import time

UDP_IP = "0.0.0.0"
UDP_PORT = 5005

# Duration to keep "W" held after the last step arrives.
# If steps arrive faster than this the key stays held down smoothly.
HOLD_DURATION = 0.55

# ── Speed mode → modifier keys ──────────────────────────────────────────────
# walk   : W + Left Ctrl
# jog    : W only
# sprint : W + Left Shift
MODE_MODIFIERS = {
    "walk":   "ctrl",
    "jog":    None,
    "sprint": "shift",
}

last_step_time = 0
key_pressed    = False          # True while 'w' is held
active_mode    = "jog"          # last received speed mode
active_mod     = None           # modifier key currently held down
lock           = threading.Lock()

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


def key_manager():
    global last_step_time, key_pressed, active_mod
    while True:
        with lock:
            current_time = time.time()

            if current_time - last_step_time < HOLD_DURATION and last_step_time != 0:
                # ── Movement active ──────────────────────────────────────────
                if not key_pressed:
                    # Apply current mode modifiers first, then press W
                    _press_modifier(active_mod)
                    pydirectinput.keyDown('w')
                    key_pressed = True
                    print(f" [DRAWBRIDGE] Walk Link Active → Holding 'W' [{active_mode.upper()}]")
            else:
                # ── Movement expired ─────────────────────────────────────────
                if key_pressed:
                    pydirectinput.keyUp('w')
                    _release_modifier(active_mod)
                    active_mod  = None
                    key_pressed = False
                    print(f" [DRAWBRIDGE] Walk Link Standby → Releasing 'W'")

        time.sleep(0.05)


def start_server():
    global last_step_time, active_mode

    pydirectinput.FAILSAFE = False

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))

    threading.Thread(target=key_manager, daemon=True).start()

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
    print(" > SPEED MODES: WALK (LCtrl) | JOG | SPRINT (LShift)")
    print("-" * 52)
    print(" [ AWAITING CHIRAL NETWORK HANDSHAKE... ]")
    print("\n")

    while True:
        data, addr = sock.recvfrom(1024)
        try:
            message = json.loads(data.decode("utf-8"))
            event   = message.get("event")

            if event == "step":
                mode = message.get("mode", "jog")
                with lock:
                    # Switch speed mode if changed (modifier key swap)
                    if mode != active_mode:
                        apply_mode(mode)
                    last_step_time = time.time()

            elif event == "stop":
                with lock:
                    last_step_time = 0  # Immediately release 'W'

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
        if key_pressed:
            pydirectinput.keyUp('w')
        if active_mod:
            pydirectinput.keyUp(active_mod)
