"""
Tiny local HTTP server around kokoro-onnx, spawned by the Study Assistant
backend. Nothing here talks to the network beyond 127.0.0.1.

    python kokoro_server.py --port 0 --model kokoro-v1.0.onnx --voices voices-v1.0.bin

Endpoints
    GET  /health            -> {"ok": true, "voices": [...], "sampleRate": 24000}
    POST /tts               -> raw signed 16-bit little-endian mono PCM
         body: {"text": "...", "voice": "af_heart", "speed": 1.0, "lang": "en-us"}
         headers on the reply: X-Sample-Rate

The chosen port is printed on stdout as "PORT <n>" so the parent can find it
when it asked for port 0.
"""

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

try:
    from kokoro_onnx import Kokoro
except ImportError as e:  # pragma: no cover - reported to the parent as a startup failure
    print("ERROR kokoro-onnx is not installed: pip install kokoro-onnx", file=sys.stderr)
    sys.exit(2)


class State:
    kokoro = None
    lock = threading.Lock()  # onnxruntime sessions are not safe for concurrent runs here
    sample_rate = 24000


def synth(text: str, voice: str, speed: float, lang: str) -> bytes:
    with State.lock:
        samples, sr = State.kokoro.create(text, voice=voice, speed=speed, lang=lang)
    State.sample_rate = sr
    pcm = np.clip(np.asarray(samples, dtype=np.float32), -1.0, 1.0)
    return (pcm * 32767.0).astype("<i2").tobytes()


class Handler(BaseHTTPRequestHandler):
    server_version = "KokoroSidecar/1.0"

    def log_message(self, fmt, *args):  # keep stdout clean for the parent
        pass

    def _json(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True, "voices": sorted(State.kokoro.get_voices()), "sampleRate": State.sample_rate})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/tts":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(length).decode("utf-8"))
            text = (req.get("text") or "").strip()
            if not text:
                self._json(400, {"error": "text is required"})
                return
            pcm = synth(
                text,
                req.get("voice") or "af_heart",
                float(req.get("speed") or 1.0),
                req.get("lang") or "en-us",
            )
        except Exception as e:  # noqa: BLE001 - surface anything to the parent
            self._json(500, {"error": f"{type(e).__name__}: {e}"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(pcm)))
        self.send_header("X-Sample-Rate", str(State.sample_rate))
        self.end_headers()
        self.wfile.write(pcm)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--model", required=True)
    ap.add_argument("--voices", required=True)
    args = ap.parse_args()

    State.kokoro = Kokoro(args.model, args.voices)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"PORT {server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
