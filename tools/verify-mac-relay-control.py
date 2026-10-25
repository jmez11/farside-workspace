"""Exercise the shipped Perl control-only mode on owned sockets; no remote hosts."""
import argparse
import json
import socket
import struct
import subprocess
import threading

parser = argparse.ArgumentParser()
parser.add_argument("--perl", default="perl")
args = parser.parse_args()

for exit_mode in ("frame", "eof"):
    with socket.socket() as server, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as video, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as audio:
        # Fallback must work even when the media ports are unavailable.
        video.bind(("127.0.0.1", 5901))
        audio.bind(("127.0.0.1", 5900))
        server.bind(("127.0.0.1", 0))
        server.listen()
        server.settimeout(5)
        errors = []

        def echo():
            try:
                connection, _ = server.accept()
                with connection:
                    connection.settimeout(5)
                    while data := connection.recv(65536):
                        connection.sendall(data)
            except Exception as error:
                errors.append(str(error))

        worker = threading.Thread(target=echo, daemon=True)
        worker.start()
        process = subprocess.Popen([args.perl, "app/src/main/assets/mac-hevc-relay.pl",
                                    "unused.invalid", str(server.getsockname()[1]), "local", "control"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = threading.Timer(15, process.kill)
        deadline.start()
        try:
            line = process.stdout.readline().decode().strip()
            assert line.startswith("READY "), line
            # Heartbeat is distinct from shutdown and may be split across SSH reads.
            process.stdin.write(b"\x00\x00\x00\x01")
            process.stdin.flush()
            process.stdin.write(b"\x00")
            process.stdin.flush()
            with socket.create_connection(("127.0.0.1", int(line.split()[1])), 3) as client:
                client.settimeout(5)
                payload = bytes(range(256)) * 4096
                client.sendall(payload)
                client.shutdown(socket.SHUT_WR)
                response = bytearray()
                while chunk := client.recv(65536):
                    response.extend(chunk)
                assert response == payload
            worker.join(5)
            assert not worker.is_alive() and not errors, errors
            if exit_mode == "frame":
                process.stdin.write(struct.pack("!I", 0))
                process.stdin.flush()
            else:
                process.stdin.close()
            assert process.wait(5) == 0
            with socket.socket() as probe:
                assert probe.connect_ex(("127.0.0.1", int(line.split()[1]))) != 0
        finally:
            deadline.cancel()
            if process.poll() is None:
                process.kill()
                process.wait()
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream and not stream.closed:
                    stream.close()
print(json.dumps({"result": "PASS", "scope": "unmodified local control-only Perl; UDP ports occupied; custom TCP port; 1MiB lossless half-close; termination frame and owner EOF"}))
