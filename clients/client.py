#!/usr/bin/env python3
"""
spring-demo · Python client

Pure stdlib — no extra dependencies. Demonstrates every endpoint with
each role and shows the global error envelope when things go wrong.

Usage:
    python3 client.py
    python3 client.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass
class Creds:
    user: str
    password: str

    def basic(self) -> str:
        token = base64.b64encode(f"{self.user}:{self.password}".encode()).decode()
        return f"Basic {token}"


ADMIN = Creds("admin", "admin123")
MANAGER = Creds("manager", "manager123")
USER = Creds("user", "user123")


class Client:
    def __init__(self, base_url: str = "http://localhost:8080") -> None:
        self.base = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        creds: Creds | None = None,
        body: Any = None,
    ) -> tuple[int, Any]:
        url = f"{self.base}{path}"
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if creds is not None:
            headers["Authorization"] = creds.basic()

        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, _safe_json(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, _safe_json(e.read())

    # ---- shorthand verbs ----
    def get(self, path, creds=None):              return self.request("GET", path, creds)
    def post(self, path, body, creds=None):       return self.request("POST", path, creds, body)
    def put(self, path, body, creds=None):        return self.request("PUT", path, creds, body)
    def delete(self, path, creds=None):           return self.request("DELETE", path, creds)


def _safe_json(raw: bytes) -> Any:
    if not raw:
        return ""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw.decode("utf-8", errors="replace")


def banner(title: str) -> None:
    print(f"\n\033[1;33m── {title} \033[0m" + "─" * max(0, 60 - len(title)))


def show(label: str, status: int, body: Any) -> None:
    color = "\033[32m" if 200 <= status < 300 else "\033[31m"
    print(f"  {color}{status}\033[0m  {label}")
    print("       " + json.dumps(body, indent=2, default=str).replace("\n", "\n       "))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    c = Client(args.base_url)

    banner("PUBLIC")
    show("GET /api/health/public",      *c.get("/api/health/public"))
    show("GET /actuator/health",        *c.get("/actuator/health"))
    show("GET /actuator/info",          *c.get("/actuator/info"))

    banner("ROLE-AWARE /api/role/me")
    show("admin -> AdminDto",   *c.get("/api/role/me", ADMIN))
    show("manager -> ManagerDto", *c.get("/api/role/me", MANAGER))
    show("user -> UserDto",     *c.get("/api/role/me", USER))

    banner("PRODUCTS · READ (any auth)")
    show("user lists",          *c.get("/api/products", USER))

    banner("PRODUCTS · CREATE (manager+)")
    new = {"name": "Webcam 4K", "description": "USB-C, 60fps", "price": 199.0, "stock": 30}
    show("manager POST", *c.post("/api/products", new, MANAGER))
    show("user POST (expect 403)", *c.post("/api/products", new, USER))

    banner("PRODUCTS · VALIDATION")
    bad = {"name": "", "price": -1, "stock": None}
    show("manager POST invalid (expect 400)", *c.post("/api/products", bad, MANAGER))

    banner("PRODUCTS · NOT FOUND")
    show("GET /api/products/9999 (expect 404)", *c.get("/api/products/9999", USER))

    banner("PRODUCTS · UPDATE & DELETE")
    show("manager PUT /1",
         *c.put("/api/products/1",
                {"name": "Wireless Mouse", "description": "Updated by client", "price": 34.99, "stock": 88},
                MANAGER))
    show("manager DELETE /1 (expect 403)", *c.delete("/api/products/1", MANAGER))
    show("admin DELETE /4 (no content)",   *c.delete("/api/products/4", ADMIN))

    banner("HEALTH CONTROLLER")
    show("admin /api/health/secure", *c.get("/api/health/secure", ADMIN))

    banner("ACTUATOR (admin)")
    show("admin /actuator/metrics", *c.get("/actuator/metrics", ADMIN))

    return 0


if __name__ == "__main__":
    sys.exit(main())
