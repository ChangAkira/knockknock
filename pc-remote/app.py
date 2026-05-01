"""
Windows PC 端：接收局域网内手机的 HTTP 指令，用键盘模拟 PPT 翻页与显示桌面。
可选环境变量 REMOTE_TOKEN：若设置则所有 /command 请求须携带请求头 X-Auth-Token。
"""

from __future__ import annotations

import json
import os
import sys
import threading
import urllib.error
import urllib.request

from flask import Flask, jsonify, request
from pynput.keyboard import Controller, Key

app = Flask(__name__)
keyboard = Controller()

EXPECTED_TOKEN = os.environ.get("REMOTE_TOKEN", "").strip()
OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "llama3.2")

# English lines injected before the user message (spatial grounding for LLM)
SPATIAL_CONTEXT = {
    "TOP": (
        "The user just performed a tap on the desk; the estimated direction "
        "relative to the phone is the upper sector."
    ),
    "RIGHT": (
        "The user just performed a tap on the desk; the estimated direction "
        "relative to the phone is the right-side sector."
    ),
    "BOTTOM": (
        "The user just performed a tap on the desk; the estimated direction "
        "relative to the phone is the lower sector."
    ),
}

# 避免并发按键交叠
_key_lock = threading.Lock()


def _require_token() -> tuple[dict, int] | None:
    if not EXPECTED_TOKEN:
        return None
    if request.headers.get("X-Auth-Token") != EXPECTED_TOKEN:
        return {"error": "unauthorized"}, 401
    return None


def _ppt_previous() -> None:
    keyboard.press(Key.page_up)
    keyboard.release(Key.page_up)


def _ppt_next() -> None:
    keyboard.press(Key.page_down)
    keyboard.release(Key.page_down)


def _show_desktop() -> None:
    # Windows: Win+D
    keyboard.press(Key.cmd)
    keyboard.press("d")
    keyboard.release("d")
    keyboard.release(Key.cmd)


ACTIONS = {
    "ppt_previous": _ppt_previous,
    "ppt_next": _ppt_next,
    "show_desktop": _show_desktop,
}


@app.get("/health")
def health():
    return jsonify({"ok": True})


@app.post("/command")
def command():
    err = _require_token()
    if err is not None:
        body, code = err
        return jsonify(body), code

    payload = request.get_json(silent=True) or {}
    action = payload.get("action")
    if action not in ACTIONS:
        return jsonify({"error": "invalid_action", "allowed": list(ACTIONS.keys())}), 400

    def _run():
        with _key_lock:
            try:
                ACTIONS[action]()
            except Exception as ex:  # noqa: BLE001
                print(f"[remote] action={action!r} failed: {ex}", file=sys.stderr)

    threading.Thread(target=_run, daemon=True).start()
    return jsonify({"ok": True, "action": action})


def _ollama_generate(prompt: str) -> str:
    body = json.dumps(
        {
            "model": OLLAMA_MODEL,
            "prompt": prompt,
            "stream": False,
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        f"{OLLAMA_HOST}/api/generate",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        detail = ex.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"Ollama HTTP {ex.code}: {detail}") from ex
    except urllib.error.URLError as ex:
        raise RuntimeError(f"Ollama unreachable: {ex}") from ex
    return str(data.get("response", "")).strip()


@app.post("/llm")
def llm_route():
    err = _require_token()
    if err is not None:
        body, code = err
        return jsonify(body), code

    payload = request.get_json(silent=True) or {}
    sector = str(payload.get("spatial_sector", "")).strip().upper()
    user_text = str(payload.get("user_text", "")).strip()
    if sector not in SPATIAL_CONTEXT:
        return jsonify(
            {"error": "invalid spatial_sector", "allowed": list(SPATIAL_CONTEXT.keys())},
        ), 400
    if not user_text:
        return jsonify({"error": "user_text required"}), 400

    context_line = SPATIAL_CONTEXT[sector]
    full_prompt = (
        f"{context_line}\n\nUser message:\n{user_text}\n\n"
        "Respond concisely in plain text."
    )

    print("[llm] spatial_sector=", sector, "model=", OLLAMA_MODEL, flush=True)
    print("[llm] ----- final prompt to Ollama -----", flush=True)
    print(full_prompt, flush=True)
    print("[llm] ----- end prompt -----", flush=True)

    try:
        reply = _ollama_generate(full_prompt)
    except RuntimeError as ex:
        return jsonify({"ok": False, "error": str(ex)}), 502

    return jsonify({"ok": True, "response": reply})


def main():
    port = int(os.environ.get("PORT", "5000"))
    host = os.environ.get("HOST", "0.0.0.0")
    print(f"Listening on http://{host}:{port}", flush=True)
    if EXPECTED_TOKEN:
        print("X-Auth-Token required for /command and /llm", flush=True)
    else:
        print("REMOTE_TOKEN not set — /command and /llm are open on LAN (demo only)", flush=True)
    print(f"Ollama: {OLLAMA_HOST} model={OLLAMA_MODEL}", flush=True)
    app.run(host=host, port=port, threaded=True)


if __name__ == "__main__":
    main()
