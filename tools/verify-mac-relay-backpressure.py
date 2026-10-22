"""Owned local relay test: stalled video stdout must not block the TCP control bridge.
Run with --perl pointing to a Perl executable (Mac/Linux or Git for Windows).
Uses local interface/loopback sockets only; no saved profiles, remote hosts or credentials.
"""
import argparse
import json
import re
import socket
import struct
import subprocess
import threading
import time
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--perl", default="perl")
parser.add_argument("--script", default="app/src/main/assets/mac-hevc-relay.pl")
parser.add_argument("--loopback-fixture", action="store_true", help="Owned 127.0.0.2 encoder; relax only the production loopback-target guard in an in-memory test copy")
args = parser.parse_args()
local = "127.0.0.2" if args.loopback_fixture else socket.gethostbyname(socket.gethostname())
assert args.loopback_fixture or not local.startswith("127."), "Need an assigned local interface for the owned encoder fixture"
# Fail before starting if the fixed relay ports belong to another local process.
for port in (5900, 5901):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.bind(("127.0.0.1", port))
listener = socket.socket()
listener.bind((local, 0))
listener.listen()
listener.settimeout(10)
errors = []
stop = threading.Event()
def echo():
    try:
        connection, _ = listener.accept()
        with connection:
            connection.settimeout(5)
            while not stop.is_set():
                data = connection.recv(65536)
                if not data:
                    break
                connection.sendall(data)
    except Exception as error:
        if not stop.is_set():
            errors.append(str(error))
threading.Thread(target=echo, daemon=True).start()
fixture_path = None
script_args = [args.script]
if args.loopback_fixture:
    source = Path(args.script).read_text(encoding="utf-8")
    guard = "die 'Loopback media target would loop relay packets' if inet_ntoa($target) =~ /^127\\./;"
    assert guard in source
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".pl", delete=False) as fixture:
        fixture.write(source.replace(guard, "# Only the owned harness allows a loopback encoder."))
        fixture_path = Path(fixture.name)
    script_args = [str(fixture_path)]
process = subprocess.Popen([args.perl, *script_args, local, str(listener.getsockname()[1]), "target"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
deadline = threading.Timer(20, process.kill)
deadline.start()
control = None
try:
    ready = process.stdout.readline().decode().strip()
    assert ready.startswith("READY "), (ready, process.poll())
    control = socket.create_connection(("127.0.0.1", int(ready.split()[1])), 3)
    control.settimeout(1)
    control.sendall(b"before")
    assert control.recv(6) == b"before"
    # Deliberately stop consuming stdout. Media pressure must not block control I/O.
    def flood():
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind((local, 0))
            for sequence in range(4000):
                if stop.is_set():
                    break
                udp.sendto(struct.pack("!I", sequence) + b"x" * 3996, ("127.0.0.1", 5901))
                time.sleep(0.0005)
    sender = threading.Thread(target=flood, daemon=True)
    sender.start()
    time.sleep(0.6)
    started = time.monotonic()
    control.sendall(b"during")
    response = control.recv(6)
    latency_ms = int((time.monotonic() - started) * 1000)
    assert response == b"during" and latency_ms < 800, (response, latency_ms)
    # Exercise lossless TCP backpressure and half-close while video remains stalled.
    payload = bytes(range(256)) * 4096
    control.sendall(payload)
    control.shutdown(socket.SHUT_WR)
    echoed = bytearray()
    while True:
        chunk = control.recv(65536)
        if not chunk:
            break
        echoed.extend(chunk)
    assert echoed == payload, "TCP buffer/half-close truncated control data"
    sender.join(timeout=4)
    assert not sender.is_alive(), "Owned flood did not complete"
    time.sleep(3)  # Let the helper emit its five-second bounded queue/drop counters.
    # Resuming the framed stream must not expose a truncated/dropped partial packet.
    def exact(size):
        data = b""
        while len(data) < size:
            chunk = process.stdout.read(size-len(data))
            assert chunk, "Truncated framed output"
            data += chunk
        return data
    for _ in range(100):
        port, size = struct.unpack("!HH", exact(4))
        frame_payload = exact(size)
        assert port == 5901 and size == 4000 and frame_payload[4:] == b"x" * 3996
    statistics = process.stderr.readline().decode().strip()
    match = re.fullmatch(r"RELAY_STATS queued=(\d+) dropped=(\d+) forwarded=(\d+) control=(\d+)", statistics)
    assert match and int(match[1]) <= 4*1024*1024 and int(match[2]) > 0, statistics
    assert not errors, errors
    print(statistics)
    print(json.dumps({"result": "PASS", "control_during_blocked_media_ms": latency_ms,
        "validated_frames": 100, "lossless_tcp_bytes": len(payload), "scope": "owned local sockets; stdout backpressure; no SSH network latency"}))
finally:
    deadline.cancel()
    stop.set()
    if control:
        control.close()
    listener.close()
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()

    diagnostics = process.stderr.read().decode(errors="replace")
    if diagnostics:
        print(diagnostics)

    if fixture_path:
        fixture_path.unlink()
