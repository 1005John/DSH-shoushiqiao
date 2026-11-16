#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MOA 聊天记录只读 API
数据源：/root/moa-chats/<sid>/messages.jsonl（由 moa-receiver 写入）

鉴权：请求头 X-Moa-Token 或查询参数 ?token=
监听：127.0.0.1:8091（只走 Caddy 反代，不直接对外）
"""
import json
import os
from datetime import datetime

from aiohttp import web

ROOT = "/root/moa-chats"
TOKEN = os.environ.get("MOA_READ_TOKEN", "")
PORT = int(os.environ.get("MOA_READ_PORT", "8091"))

routes = web.RouteTableDef()


def _check(request):
    t = request.headers.get("X-Moa-Token") or request.query.get("token", "")
    return t == TOKEN and TOKEN != ""


@routes.get("/health")
async def health(request):
    return web.json_response({"ok": True, "root": ROOT, "sessions": len(_sessions())})


def _sessions():
    out = []
    if not os.path.isdir(ROOT):
        return out
    for sid in os.listdir(ROOT):
        d = os.path.join(ROOT, sid)
        f = os.path.join(d, "messages.jsonl")
        if not os.path.isfile(f):
            continue
        name, count, last = sid, 0, ""
        try:
            with open(f, "r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        m = json.loads(line)
                    except Exception:
                        continue
                    count += 1
                    name = m.get("chat_name") or name
                    t = m.get("time") or ""
                    if t > last:
                        last = t
        except Exception:
            continue
        out.append({"sid": sid, "name": name, "count": count, "last_time": last})
    out.sort(key=lambda x: x["last_time"], reverse=True)
    return out


def _read_messages(sid, since="", limit=0):
    f = os.path.join(ROOT, sid, "messages.jsonl")
    if not os.path.isfile(f):
        return []
    out = []
    with open(f, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                m = json.loads(line)
            except Exception:
                continue
            if since and (m.get("time") or "") <= since:
                continue
            out.append(m)
    out.sort(key=lambda x: x.get("time") or "")
    if limit and limit > 0:
        out = out[:limit]
    return out


@routes.get("/sessions")
async def sessions(request):
    if not _check(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    return web.json_response(_sessions())


@routes.get("/messages")
async def messages(request):
    if not _check(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    sid = request.query.get("sid", "")
    since = request.query.get("since", "")
    limit = int(request.query.get("limit", "0") or 0)
    if not sid:
        return web.json_response({"error": "sid required"}, status=400)
    return web.json_response(_read_messages(sid, since, limit))


def _read_all(since="", limit=0):
    out = []
    for s in _sessions():
        out.extend(_read_messages(s["sid"], since, 0))
    out.sort(key=lambda x: x.get("time") or "")
    if limit and limit > 0:
        out = out[-limit:]
    return out


@routes.get("/all")
async def all_messages(request):
    if not _check(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    since = request.query.get("since", "")
    limit = int(request.query.get("limit", "0") or 0)
    return web.json_response(_read_all(since, limit))


@routes.get("/digest")
async def digest(request):
    if not _check(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    for p in ("/root/.dsh/bot/workspace/todo/im_digest.md",
              "/root/.dsh/bot/workspace/todo/TODO.md"):
        if os.path.isfile(p):
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                return web.json_response({"path": p, "content": fh.read()})
    return web.json_response({"path": None, "content": ""})


@routes.get("/search")
async def search(request):
    if not _check(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    q = request.query.get("q", "").strip()
    if not q:
        return web.json_response({"error": "q required"}, status=400)
    limit = int(request.query.get("limit", "50") or 50)
    hits = []
    for m in _read_all("", 0):
        if q in (m.get("content") or ""):
            hits.append(m)
            if len(hits) >= limit:
                break
    return web.json_response(hits)


def main():
    app = web.Application()
    app.add_routes(routes)
    print(f"[moa-read-api] listening 127.0.0.1:{PORT} root={ROOT}", flush=True)
    web.run_app(app, host="127.0.0.1", port=PORT, print=None)


if __name__ == "__main__":
    main()
