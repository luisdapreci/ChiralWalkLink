import socket
import json
import pydirectinput
import threading
import time

UDP_IP = "0.0.0.0"
UDP_PORT = 5005

# Duration to hold "W" for each step (in seconds)
# If steps come in faster than this, "W" stays held down smoothly.
HOLD_DURATION = 0.55  

last_step_time = 0
key_pressed = False
lock = threading.Lock()

def get_local_ip():
    # Simple hack to get the true local IP address that the phone needs
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

def key_manager():
    global last_step_time, key_pressed
    while True:
        with lock:
            current_time = time.time()
            # If a step occurred recently, ensure 'w' is pressed
            if current_time - last_step_time < HOLD_DURATION and last_step_time != 0:
                if not key_pressed:
                    pydirectinput.keyDown('w')
                    key_pressed = True
                    print(" [DRAWBRIDGE] Walk Link Active -> Holding 'W'")
            else:
                # If time expired and key is still pressed, release it
                if key_pressed:
                    pydirectinput.keyUp('w')
                    key_pressed = False
                    print(" [DRAWBRIDGE] Walk Link Standby -> Releasing 'W'")
        time.sleep(0.05)

def start_server():
    global last_step_time
    
    # disable the failsafe which might throw exception if mouse is in corners
    pydirectinput.FAILSAFE = False
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))
    
    # Start the key manager in a background thread
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
    print(" [ AWAITING CHIRAL NETWORK HANDSHAKE... ]")
    print("\n")
    
    while True:
        data, addr = sock.recvfrom(1024)
        try:
            message = json.loads(data.decode("utf-8"))
            if message.get("event") == "step":
                with lock:
                    last_step_time = time.time()
                # Determine time passed since start for neat console logging
                # print(f"[Packet] Received STEP from {addr[0]}")
            elif message.get("event") == "stop":
                with lock:
                    last_step_time = 0 # Immediately release 'W'
            elif message.get("event") == "ping":
                # Respond to connection ping
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
