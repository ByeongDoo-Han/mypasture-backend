#!/usr/bin/env python3
"""Create isolated local accounts through the public signup flow for k6."""

import argparse
import hashlib
from http.client import RemoteDisconnected
import json
import os
import re
import secrets
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BASE_URL = "http://127.0.0.1:18080"
OUTPUT = Path(__file__).with_name("tokens.local.json")
COMPOSE = ["docker", "compose", "-p", "mypasture-load", "-f", "docker-compose.yml", "-f", "scripts/load/compose.load.yml"]


def call(method, path, body=None, token=None):
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(BASE_URL + path, data=payload, headers=headers, method=method)
    try:
        with urlopen(request, timeout=15) as response:
            data = response.read()
            return json.loads(data) if data else None
    except HTTPError as error:
        raise RuntimeError(f"{method} {path} returned HTTP {error.code}") from error


def wait_until_ready():
    for _ in range(120):
        try:
            with urlopen(BASE_URL + "/actuator/health", timeout=2) as response:
                if response.status == 200 and json.load(response).get("status") == "UP":
                    return
        except (HTTPError, URLError, TimeoutError, RemoteDisconnected):
            pass
        time.sleep(1)
    raise RuntimeError("Local backend did not become healthy within 120 seconds")


def required_consents(scope):
    documents = call("GET", f"/api/v1/policies/current?scope={scope}")
    return [
        {"type": item["type"], "version": item["version"], "agreed": True}
        for item in documents if item["requirement"] == "REQUIRED"
    ]


def verification_code(email, container_id):
    email_hash = hashlib.sha256(email.encode()).hexdigest()
    pattern = re.compile(rf"emailHash={email_hash}\s+expiresAt=\S+\s+code=(\d{{6}})")
    for _ in range(40):
        result = subprocess.run(
            ["docker", "logs", "--since", "2m", container_id],
            capture_output=True, text=True, check=True,
        )
        logs = result.stdout + result.stderr
        match = pattern.search(logs)
        if match:
            return match.group(1)
        time.sleep(0.25)
    raise RuntimeError("Local verification code was not found in backend logs")


def nickname(index, run_id):
    # The public nickname policy accepts letters and limits English names to 10.
    run_letters = "".join(chr(ord("a") + int(digit, 16)) for digit in run_id[:5])
    index_letters = chr(ord("a") + index // 26) + chr(ord("a") + index % 26)
    return "L" + run_letters + index_letters


def create_user(index, run_id, account_consents, ai_consents, container_id):
    email = f"load-{run_id}-{index}@example.com"
    password = secrets.token_urlsafe(20)
    call("POST", "/api/v1/auth/email-verifications", {"email": email})
    confirmed = call("POST", "/api/v1/auth/email-verifications/confirm", {
        "email": email, "code": verification_code(email, container_id),
    })
    call("POST", "/api/v1/auth/signup", {
        "email": email, "password": password, "displayName": nickname(index, run_id),
        "verificationToken": confirmed["verificationToken"], "policyConsents": account_consents,
    })
    session = call("POST", "/api/v1/auth/login", {"email": email, "password": password})
    token = session["token"]
    if ai_consents:
        call("POST", "/api/v1/policies/consents", {"consents": ai_consents}, token)
    return token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--users", type=int, default=1, help="1 to 200; one account per k6 VU")
    args = parser.parse_args()
    if not 1 <= args.users <= 200:
        parser.error("--users must be between 1 and 200")
    if OUTPUT.exists():
        parser.error(f"{OUTPUT} already exists; remove it explicitly before creating fresh accounts")
    container_id = subprocess.run(
        [*COMPOSE, "ps", "-q", "backend"], capture_output=True, text=True, check=True,
    ).stdout.strip()
    if not container_id:
        parser.error("Start the isolated mypasture-load Compose backend first")
    inspect = subprocess.run(
        ["docker", "inspect", container_id, "--format", "{{range .Config.Env}}{{println .}}{{end}}"],
        capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    env = dict(item.split("=", 1) for item in inspect if "=" in item)
    if env.get("SPRING_PROFILES_ACTIVE") != "local" or env.get("AI_MOCK_ENABLED") != "true" or env.get("EMAIL_VERIFICATION_DELIVERY", "log") != "log":
        parser.error("Backend must run with local profile, AI_MOCK_ENABLED=true, and log email delivery")
    wait_until_ready()
    account_consents = required_consents("ACCOUNT")
    ai_consents = required_consents("AI")
    run_id = secrets.token_hex(5)
    tokens = []
    for index in range(args.users):
        tokens.append(create_user(index, run_id, account_consents, ai_consents, container_id))
        print(f"Prepared local user {index + 1}/{args.users}")
    descriptor = os.open(OUTPUT, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as file:
        json.dump(tokens, file)
    print(f"Wrote {len(tokens)} access tokens to {OUTPUT} (mode 600; expires with local JWT TTL)")


if __name__ == "__main__":
    main()
