"""
МоиСтраницы — социальный сервер (Yandex Cloud Functions + YDB serverless).

Один HTTP-обработчик. Клиент шлёт POST с JSON {"op": "...", ...}
и заголовком X-Yandex-Token: <OAuth-токен Яндекс ID>.
Пользователь определяется по токену через login.yandex.ru/info.
"""
import base64
import json
import os
import time
import urllib.request
import uuid

import ydb

U8 = ydb.PrimitiveType.Utf8
I64 = ydb.PrimitiveType.Int64
BOOL = ydb.PrimitiveType.Bool
LIST_U8 = ydb.ListType(U8)

_driver = None
_pool = None
_token_cache = {}  # token -> (user_info, expires)

SCHEMA = [
    """CREATE TABLE IF NOT EXISTS users (
        id Utf8, name Utf8, login Utf8, avatar Utf8, is_public Bool,
        created Int64, updated Int64, read_count Int64,
        PRIMARY KEY (id))""",
    """CREATE TABLE IF NOT EXISTS shelf (
        user_id Utf8, book_id Utf8, title Utf8, authors Utf8, cover Utf8, series Utf8,
        status Utf8, rating Int64, updated Int64,
        PRIMARY KEY (user_id, book_id))""",
    """CREATE TABLE IF NOT EXISTS follows (
        follower Utf8, followee Utf8, created Int64,
        PRIMARY KEY (follower, followee))""",
    """CREATE TABLE IF NOT EXISTS followers (
        followee Utf8, follower Utf8, created Int64,
        PRIMARY KEY (followee, follower))""",
    """CREATE TABLE IF NOT EXISTS events (
        user_id Utf8, created Int64, id Utf8, type Utf8,
        book_id Utf8, title Utf8, authors Utf8, cover Utf8, status Utf8, rating Int64, text Utf8,
        PRIMARY KEY (user_id, created, id))""",
    """CREATE TABLE IF NOT EXISTS event_index (
        id Utf8, user_id Utf8, created Int64,
        PRIMARY KEY (id))""",
    """CREATE TABLE IF NOT EXISTS likes (
        event_id Utf8, user_id Utf8, created Int64,
        PRIMARY KEY (event_id, user_id))""",
    """CREATE TABLE IF NOT EXISTS comments (
        event_id Utf8, created Int64, id Utf8, user_id Utf8, text Utf8,
        PRIMARY KEY (event_id, created, id))""",
]


# ---------------------------------------------------------------- база

def pool():
    global _driver, _pool
    if _pool is None:
        _driver = ydb.Driver(
            endpoint=os.environ["YDB_ENDPOINT"],
            database=os.environ["YDB_DATABASE"],
            credentials=ydb.iam.MetadataUrlCredentials(),
        )
        _driver.wait(fail_fast=True, timeout=10)
        _pool = ydb.QuerySessionPool(_driver)
        for ddl in SCHEMA:
            _pool.execute_with_retries(ddl)
    return _pool


def q(query, params=None):
    """Выполнить запрос, вернуть строки первого набора (список dict)."""
    rs = pool().execute_with_retries(query, params or {})
    if not rs:
        return []
    out = []
    for r in rs[0].rows:
        out.append({k: r[k] for k in r.keys()} if hasattr(r, "keys") else dict(r))
    return out


def now_ms():
    return int(time.time() * 1000)


# ---------------------------------------------------------------- авторизация

class HttpError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code
        self.msg = msg


def yandex_user(token):
    cached = _token_cache.get(token)
    if cached and cached[1] > time.time():
        return cached[0]
    req = urllib.request.Request(
        "https://login.yandex.ru/info?format=json",
        headers={"Authorization": "OAuth " + token},
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            info = json.loads(r.read().decode("utf-8"))
    except Exception:
        raise HttpError(401, "Вход недействителен — войдите заново")
    _token_cache[token] = (info, time.time() + 600)
    return info


def current_user(headers):
    token = None
    for k, v in (headers or {}).items():
        if k.lower() == "x-yandex-token":
            token = v
    if not token:
        raise HttpError(401, "Нужен вход через Яндекс ID")
    info = yandex_user(token)
    uid = str(info.get("id") or info.get("psuid") or "")
    if not uid:
        raise HttpError(401, "Не удалось определить пользователя")
    name = info.get("display_name") or info.get("real_name") or info.get("login") or "Читатель"
    avatar = ""
    if info.get("default_avatar_id") and not info.get("is_avatar_empty", False):
        avatar = "https://avatars.yandex.net/get-yapic/%s/islands-retina-middle" % info["default_avatar_id"]
    login = info.get("login") or ""
    # регистрируем / обновляем профиль
    existing = q("SELECT id, is_public, created FROM users WHERE id = $id", {"$id": uid})
    if not existing:
        q(
            """UPSERT INTO users (id, name, login, avatar, is_public, created, updated, read_count)
               VALUES ($id, $name, $login, $avatar, true, $now, $now, 0)""",
            {"$id": uid, "$name": name, "$login": login, "$avatar": avatar, "$now": now_ms()},
        )
    else:
        q(
            "UPDATE users SET name = $name, login = $login, avatar = $avatar, updated = $now WHERE id = $id",
            {"$id": uid, "$name": name, "$login": login, "$avatar": avatar, "$now": now_ms()},
        )
    return uid


# ---------------------------------------------------------------- помощники

def users_by_ids(ids):
    ids = list({i for i in ids if i})
    if not ids:
        return {}
    rows = q(
        "SELECT id, name, login, avatar, is_public, read_count FROM users WHERE id IN $ids",
        {"$ids": (ids, LIST_U8)},
    )
    return {r["id"]: user_json(r) for r in rows}


def user_json(r):
    return {
        "id": r.get("id"),
        "name": r.get("name") or "Читатель",
        "login": r.get("login") or "",
        "avatar": r.get("avatar") or "",
        "isPublic": bool(r.get("is_public")),
        "readCount": int(r.get("read_count") or 0),
    }


def following_ids(uid):
    return [r["followee"] for r in q("SELECT followee FROM follows WHERE follower = $u", {"$u": uid})]


def is_following(uid, other):
    return bool(q("SELECT followee FROM follows WHERE follower = $u AND followee = $o", {"$u": uid, "$o": other}))


def count(query, params):
    rows = q(query, params)
    return int(list(rows[0].values())[0]) if rows else 0


SHELF_STRUCT = (
    ydb.StructType()
    .add_member("user_id", U8)
    .add_member("book_id", U8)
    .add_member("title", U8)
    .add_member("authors", U8)
    .add_member("cover", U8)
    .add_member("series", U8)
    .add_member("status", U8)
    .add_member("rating", I64)
    .add_member("updated", I64)
)

EVENT_STRUCT = (
    ydb.StructType()
    .add_member("user_id", U8)
    .add_member("created", I64)
    .add_member("id", U8)
    .add_member("type", U8)
    .add_member("book_id", U8)
    .add_member("title", U8)
    .add_member("authors", U8)
    .add_member("cover", U8)
    .add_member("status", U8)
    .add_member("rating", I64)
    .add_member("text", U8)
)

INDEX_STRUCT = ydb.StructType().add_member("id", U8).add_member("user_id", U8).add_member("created", I64)


def chunks(lst, n):
    for i in range(0, len(lst), n):
        yield lst[i:i + n]


# ---------------------------------------------------------------- операции

def op_me(uid, body):
    u = users_by_ids([uid]).get(uid)
    u["followers"] = count("SELECT COUNT(*) AS c FROM followers WHERE followee = $u", {"$u": uid})
    u["following"] = count("SELECT COUNT(*) AS c FROM follows WHERE follower = $u", {"$u": uid})
    return u


def op_set_profile(uid, body):
    q("UPDATE users SET is_public = $p, updated = $now WHERE id = $id",
      {"$id": uid, "$p": bool(body.get("isPublic", True)), "$now": now_ms()})
    return op_me(uid, body)


def op_push_shelf(uid, body):
    """Публичная часть полки: изменения книг. Смена статуса / оценки → события в ленту."""
    entries = body.get("entries") or []
    deleted = [str(x) for x in (body.get("deleted") or [])]
    initial = bool(body.get("initial"))
    created_events = 0
    for part in chunks(entries, 200):
        ids = [str(e.get("bookId")) for e in part]
        old = {r["book_id"]: r for r in q(
            "SELECT book_id, status, rating FROM shelf WHERE user_id = $u AND book_id IN $ids",
            {"$u": uid, "$ids": (ids, LIST_U8)},
        )}
        rows, events, index = [], [], []
        t = now_ms()
        for e in part:
            row = {
                "user_id": uid,
                "book_id": str(e.get("bookId")),
                "title": str(e.get("title") or "")[:300],
                "authors": str(e.get("authors") or "")[:300],
                "cover": str(e.get("cover") or "")[:500],
                "series": str(e.get("series") or "")[:300],
                "status": str(e.get("status") or ""),
                "rating": int(e.get("rating") or 0),
                "updated": int(e.get("updated") or t),
            }
            rows.append(row)
            if initial:
                continue
            prev = old.get(row["book_id"])
            kinds = []
            if prev is None or prev.get("status") != row["status"]:
                kinds.append("status")
            if row["rating"] > 0 and (prev is None or int(prev.get("rating") or 0) != row["rating"]):
                kinds.append("rating")
            for kind in kinds:
                eid = uuid.uuid4().hex
                ts = t + len(events)
                events.append({
                    "user_id": uid, "created": ts, "id": eid, "type": kind,
                    "book_id": row["book_id"], "title": row["title"], "authors": row["authors"],
                    "cover": row["cover"], "status": row["status"], "rating": row["rating"], "text": "",
                })
                index.append({"id": eid, "user_id": uid, "created": ts})
        q("UPSERT INTO shelf SELECT * FROM AS_TABLE($rows)", {"$rows": (rows, ydb.ListType(SHELF_STRUCT))})
        if events:
            q("UPSERT INTO events SELECT * FROM AS_TABLE($rows)", {"$rows": (events, ydb.ListType(EVENT_STRUCT))})
            q("UPSERT INTO event_index SELECT * FROM AS_TABLE($rows)", {"$rows": (index, ydb.ListType(INDEX_STRUCT))})
            created_events += len(events)
    for part in chunks(deleted, 200):
        q("DELETE FROM shelf WHERE user_id = $u AND book_id IN $ids", {"$u": uid, "$ids": (part, LIST_U8)})
    read = count("SELECT COUNT(*) AS c FROM shelf WHERE user_id = $u AND status = 'READ'", {"$u": uid})
    q("UPDATE users SET read_count = $c WHERE id = $id", {"$id": uid, "$c": read})
    return {"ok": True, "events": created_events}


def op_search(uid, body):
    text = str(body.get("q") or "").strip().lower()
    if len(text) < 2:
        return {"users": []}
    rows = q(
        """SELECT id, name, login, avatar, is_public, read_count FROM users
           WHERE is_public AND id != $me
             AND (Unicode::ToLower(COALESCE(name, "")) LIKE $p OR Unicode::ToLower(COALESCE(login, "")) LIKE $p)
           ORDER BY read_count DESC LIMIT 30""",
        {"$me": uid, "$p": "%" + text + "%"},
    )
    return {"users": [user_json(r) for r in rows]}


def op_suggest(uid, body):
    mine = set(following_ids(uid))
    rows = q(
        """SELECT id, name, login, avatar, is_public, read_count FROM users
           WHERE is_public AND id != $me ORDER BY read_count DESC LIMIT 40""",
        {"$me": uid},
    )
    return {"users": [user_json(r) for r in rows if r["id"] not in mine][:20]}


def op_user(uid, body):
    other = str(body.get("id") or "")
    u = users_by_ids([other]).get(other)
    if u is None:
        raise HttpError(404, "Пользователь не найден")
    following = is_following(uid, other)
    u["followers"] = count("SELECT COUNT(*) AS c FROM followers WHERE followee = $u", {"$u": other})
    u["following"] = count("SELECT COUNT(*) AS c FROM follows WHERE follower = $u", {"$u": other})
    u["isFollowing"] = following
    u["isMe"] = other == uid
    visible = u["isPublic"] or following or other == uid
    shelf = []
    if visible:
        shelf = [{
            "bookId": r["book_id"], "title": r["title"], "authors": r["authors"], "cover": r["cover"],
            "series": r["series"], "status": r["status"], "rating": int(r["rating"] or 0), "updated": int(r["updated"] or 0),
        } for r in q(
            """SELECT book_id, title, authors, cover, series, status, rating, updated FROM shelf
               WHERE user_id = $u ORDER BY updated DESC LIMIT 1000""",
            {"$u": other},
        )]
    u["shelf"] = shelf
    u["hidden"] = not visible
    return u


def op_follow(uid, body):
    other = str(body.get("id") or "")
    if not other or other == uid:
        raise HttpError(400, "Нельзя подписаться")
    t = now_ms()
    q("UPSERT INTO follows (follower, followee, created) VALUES ($a, $b, $t)", {"$a": uid, "$b": other, "$t": t})
    q("UPSERT INTO followers (followee, follower, created) VALUES ($b, $a, $t)", {"$a": uid, "$b": other, "$t": t})
    return {"ok": True}


def op_unfollow(uid, body):
    other = str(body.get("id") or "")
    q("DELETE FROM follows WHERE follower = $a AND followee = $b", {"$a": uid, "$b": other})
    q("DELETE FROM followers WHERE followee = $b AND follower = $a", {"$a": uid, "$b": other})
    return {"ok": True}


def op_following(uid, body):
    who = str(body.get("id") or uid)
    ids = following_ids(who)
    users = users_by_ids(ids)
    return {"users": [users[i] for i in ids if i in users]}


def op_followers(uid, body):
    who = str(body.get("id") or uid)
    ids = [r["follower"] for r in q("SELECT follower FROM followers WHERE followee = $u", {"$u": who})]
    users = users_by_ids(ids)
    return {"users": [users[i] for i in ids if i in users]}


def event_json(r, users, likes, liked, comments):
    return {
        "id": r["id"], "type": r["type"], "created": int(r["created"]),
        "user": users.get(r["user_id"]),
        "bookId": r["book_id"], "title": r["title"], "authors": r["authors"], "cover": r["cover"],
        "status": r["status"], "rating": int(r["rating"] or 0), "text": r.get("text") or "",
        "likes": likes.get(r["id"], 0), "liked": r["id"] in liked, "comments": comments.get(r["id"], 0),
    }


def decorate(uid, rows):
    ids = [r["id"] for r in rows]
    if not ids:
        return []
    users = users_by_ids([r["user_id"] for r in rows])
    likes = {r["event_id"]: int(r["c"]) for r in q(
        "SELECT event_id, COUNT(*) AS c FROM likes WHERE event_id IN $ids GROUP BY event_id",
        {"$ids": (ids, LIST_U8)},
    )}
    liked = {r["event_id"] for r in q(
        "SELECT event_id FROM likes WHERE event_id IN $ids AND user_id = $u",
        {"$ids": (ids, LIST_U8), "$u": uid},
    )}
    comments = {r["event_id"]: int(r["c"]) for r in q(
        "SELECT event_id, COUNT(*) AS c FROM comments WHERE event_id IN $ids GROUP BY event_id",
        {"$ids": (ids, LIST_U8)},
    )}
    return [event_json(r, users, likes, liked, comments) for r in rows]


def op_feed(uid, body):
    ids = following_ids(uid)
    if body.get("includeMe"):
        ids.append(uid)
    if not ids:
        return {"events": []}
    before = int(body.get("before") or 9_000_000_000_000)
    rows = q(
        """SELECT user_id, created, id, type, book_id, title, authors, cover, status, rating, text
           FROM events WHERE user_id IN $ids AND created < $before
           ORDER BY created DESC LIMIT 30""",
        {"$ids": (ids, LIST_U8), "$before": before},
    )
    return {"events": decorate(uid, rows)}


def op_user_events(uid, body):
    other = str(body.get("id") or uid)
    before = int(body.get("before") or 9_000_000_000_000)
    rows = q(
        """SELECT user_id, created, id, type, book_id, title, authors, cover, status, rating, text
           FROM events WHERE user_id = $u AND created < $before ORDER BY created DESC LIMIT 30""",
        {"$u": other, "$before": before},
    )
    return {"events": decorate(uid, rows)}


def op_like(uid, body):
    eid = str(body.get("eventId") or "")
    if not q("SELECT id FROM event_index WHERE id = $id", {"$id": eid}):
        raise HttpError(404, "Запись не найдена")
    if body.get("on", True):
        q("UPSERT INTO likes (event_id, user_id, created) VALUES ($e, $u, $t)", {"$e": eid, "$u": uid, "$t": now_ms()})
    else:
        q("DELETE FROM likes WHERE event_id = $e AND user_id = $u", {"$e": eid, "$u": uid})
    c = count("SELECT COUNT(*) AS c FROM likes WHERE event_id = $e", {"$e": eid})
    return {"likes": c, "liked": bool(body.get("on", True))}


def op_comments(uid, body):
    eid = str(body.get("eventId") or "")
    rows = q(
        "SELECT created, id, user_id, text FROM comments WHERE event_id = $e ORDER BY created LIMIT 300",
        {"$e": eid},
    )
    users = users_by_ids([r["user_id"] for r in rows])
    return {"comments": [{
        "id": r["id"], "created": int(r["created"]), "text": r["text"], "user": users.get(r["user_id"]),
    } for r in rows]}


def op_comment(uid, body):
    eid = str(body.get("eventId") or "")
    text = str(body.get("text") or "").strip()[:1000]
    if not text:
        raise HttpError(400, "Пустой комментарий")
    if not q("SELECT id FROM event_index WHERE id = $id", {"$id": eid}):
        raise HttpError(404, "Запись не найдена")
    q(
        "UPSERT INTO comments (event_id, created, id, user_id, text) VALUES ($e, $t, $id, $u, $x)",
        {"$e": eid, "$t": now_ms(), "$id": uuid.uuid4().hex, "$u": uid, "$x": text},
    )
    return op_comments(uid, body)


OPS = {
    "me": op_me,
    "setProfile": op_set_profile,
    "pushShelf": op_push_shelf,
    "search": op_search,
    "suggest": op_suggest,
    "user": op_user,
    "follow": op_follow,
    "unfollow": op_unfollow,
    "following": op_following,
    "followers": op_followers,
    "feed": op_feed,
    "userEvents": op_user_events,
    "like": op_like,
    "comments": op_comments,
    "comment": op_comment,
}


# ---------------------------------------------------------------- точка входа

def respond(code, payload):
    return {
        "statusCode": code,
        "headers": {"Content-Type": "application/json; charset=utf-8"},
        "body": json.dumps(payload, ensure_ascii=False),
    }


def handler(event, context):
    try:
        raw = event.get("body") or "{}"
        if event.get("isBase64Encoded"):
            raw = base64.b64decode(raw).decode("utf-8")
        body = json.loads(raw) if raw else {}
        op = body.get("op")
        if op == "ping":
            return respond(200, {"ok": True})
        fn = OPS.get(op)
        if fn is None:
            return respond(400, {"error": "Неизвестная операция: %s" % op})
        uid = current_user(event.get("headers") or {})
        return respond(200, fn(uid, body))
    except HttpError as e:
        return respond(e.code, {"error": e.msg})
    except Exception as e:  # noqa
        return respond(500, {"error": "Ошибка сервера: %s" % e})
