#!/usr/bin/env python3
"""Create a local standalone QT session and join every k6 fixture user."""

import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


ROOT = Path(__file__).parent
TOKEN_FILE = ROOT / "tokens.local.json"
SESSION_FILE = ROOT / "qt-session.local.txt"
BASE_URL = "http://127.0.0.1:18080"


def post(path, token, body):
    request = Request(
        BASE_URL + path,
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=15) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"POST {path} returned HTTP {error.code}") from error


def main():
    if SESSION_FILE.exists():
        raise SystemExit(f"{SESSION_FILE} already exists; remove it explicitly before creating another session")
    with TOKEN_FILE.open() as file:
        tokens = json.load(file)
    if not isinstance(tokens, list) or not tokens:
        raise SystemExit("Prepare local users first")
    session = post("/api/v1/quiet-time/sessions/standalone", tokens[0], {"title": "Local load test QT"})
    for index, token in enumerate(tokens[1:], start=2):
        post(f"/api/v1/quiet-time/offline/{session['offlineCode']}/join", token, {
            "displayName": f"Local tester {index}", "deviceId": f"local-load-device-{index}",
        })
    descriptor = os.open(SESSION_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as file:
        file.write(session["id"] + "\n")
    print(f"Prepared QT session {session['id']} for {len(tokens)} local users")


if __name__ == "__main__":
    main()
