"""
Мост Telegram-бот ↔ GitHub Issues для приложения «МоиСтраницы».

Запускается GitHub Actions раз в 10 минут:
  1) забирает новые сообщения бота;
  2) сообщения администраторов превращает в задачи (Issues с меткой bot-task),
     ответы на сообщения бота про задачу #N — в комментарии к задаче;
  3) комментарии Claude с меткой <!-- tg-reply --> пересылает в чат автору задачи.

Состояние (offset, админы, частота проверки) — в bot/state.json ветки bot-state.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ["TELEGRAM_BOT_TOKEN"]
ADMIN_CODE = os.environ.get("TELEGRAM_ADMIN_CODE", "").strip()
GH_TOKEN = os.environ["GH_TOKEN"]
REPO = os.environ["GITHUB_REPOSITORY"]
STATE_DIR = os.environ.get("STATE_DIR", "state")
STATE_FILE = os.path.join(STATE_DIR, "bot", "state.json")
MEDIA_DIR = os.path.join(STATE_DIR, "bot", "media")
LABEL = "bot-task"
REPLY_MARK = "<!-- tg-reply -->"
ALLOWED_FREQ = [1, 2, 3, 6, 12, 24]

DEFAULT_STATE = {
    "offset": 0,
    "admins": {},          # telegram user id -> имя
    "interval_hours": 1,   # как часто Claude разбирает задачи
    "paused": False,
    "last_run": 0,         # время последнего разбора (ставит Claude)
    "sent_comments": [],   # id комментариев, уже отправленных в Telegram
}


# ------------------------------------------------------------------ утилиты

def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            s = json.load(f)
    except Exception:
        s = {}
    for k, v in DEFAULT_STATE.items():
        s.setdefault(k, v if not isinstance(v, (dict, list)) else type(v)(v))
    return s


def save_state(s):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    s["sent_comments"] = s["sent_comments"][-500:]
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(s, f, ensure_ascii=False, indent=2)


def tg(method, params=None):
    data = json.dumps(params or {}).encode("utf-8")
    req = urllib.request.Request(
        "https://api.telegram.org/bot%s/%s" % (TOKEN, method),
        data=data, headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode("utf-8")).get("result")
    except urllib.error.HTTPError as e:
        print("telegram error", method, e.code, e.read()[:300], file=sys.stderr)
        return None


def send(chat_id, text, reply_to=None):
    p = {"chat_id": chat_id, "text": text[:4000], "disable_web_page_preview": True}
    if reply_to:
        p["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
    return tg("sendMessage", p)


def gh(method, path, body=None):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers={
            "Authorization": "Bearer " + GH_TOKEN,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "moistranicy-bot",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        print("github error", method, path, e.code, e.read()[:300], file=sys.stderr)
        return None


def ensure_label():
    if gh("GET", "/repos/%s/labels/%s" % (REPO, LABEL)) is None:
        gh("POST", "/repos/%s/labels" % REPO, {"name": LABEL, "color": "7B6CF6", "description": "Задача из Telegram-бота"})


def save_photo(photos):
    """Сохранить самое большое фото в ветку bot-state, вернуть ссылку."""
    best = max(photos, key=lambda p: p.get("file_size", 0))
    info = tg("getFile", {"file_id": best["file_id"]})
    if not info:
        return None
    url = "https://api.telegram.org/file/bot%s/%s" % (TOKEN, info["file_path"])
    os.makedirs(MEDIA_DIR, exist_ok=True)
    name = "%d_%s.jpg" % (int(time.time()), best["file_unique_id"])
    with urllib.request.urlopen(url, timeout=60) as r, open(os.path.join(MEDIA_DIR, name), "wb") as f:
        f.write(r.read())
    return "https://raw.githubusercontent.com/%s/bot-state/bot/media/%s" % (REPO, name)


def who(user):
    name = " ".join(x for x in [user.get("first_name"), user.get("last_name")] if x) or "?"
    if user.get("username"):
        name += " (@%s)" % user["username"]
    return name


HELP = (
    "Бот задач приложения «МоиСтраницы».\n\n"
    "Просто напишите, что нужно изменить (можно со скриншотом) — появится задача, "
    "я её выполню при следующей проверке и пришлю ответ.\n"
    "Ответьте на моё сообщение про задачу — ответ добавится к этой задаче.\n\n"
    "Команды:\n"
    "/status — состояние и частота проверки\n"
    "/tasks — открытые задачи\n"
    "/freq 1|2|3|6|12|24 — проверять раз в N часов\n"
    "/pause, /resume — приостановить и возобновить обработку\n"
    "/admins — список администраторов\n"
    "/removeadmin <id> — убрать администратора\n"
    "/admin <код> — стать администратором"
)


# ------------------------------------------------------------------ входящие

def open_tasks():
    issues = gh("GET", "/repos/%s/issues?state=open&labels=%s&per_page=50" % (REPO, LABEL)) or []
    return [i for i in issues if "pull_request" not in i]


def handle_command(state, msg, text):
    chat = msg["chat"]["id"]
    mid = msg["message_id"]
    cmd, _, arg = text.partition(" ")
    cmd = cmd.split("@")[0].lower()
    arg = arg.strip()
    if cmd in ("/start", "/help"):
        send(chat, HELP, mid)
    elif cmd == "/status":
        tasks = open_tasks()
        last = state["last_run"]
        last_txt = time.strftime("%d.%m %H:%M", time.gmtime(last + 3 * 3600)) + " МСК" if last else "ещё не было"
        send(chat, "Проверка: %s\nЧастота: раз в %d ч\nПоследний разбор: %s\nОткрытых задач: %d" % (
            "на паузе ⏸" if state["paused"] else "включена ▶️", state["interval_hours"], last_txt, len(tasks)), mid)
    elif cmd == "/tasks":
        tasks = open_tasks()
        if not tasks:
            send(chat, "Открытых задач нет ✨", mid)
        else:
            send(chat, "\n".join("#%d %s" % (t["number"], t["title"]) for t in tasks[:30]), mid)
    elif cmd == "/freq":
        try:
            n = int(re.sub(r"\D", "", arg))
        except ValueError:
            n = 0
        if n not in ALLOWED_FREQ:
            send(chat, "Укажите часы: " + ", ".join(map(str, ALLOWED_FREQ)) + ". Например: /freq 6", mid)
        else:
            state["interval_hours"] = n
            send(chat, "Готово: проверяю задачи раз в %d ч." % n, mid)
    elif cmd == "/pause":
        state["paused"] = True
        send(chat, "⏸ Обработка задач приостановлена. Задачи продолжают приниматься. /resume — возобновить.", mid)
    elif cmd == "/resume":
        state["paused"] = False
        send(chat, "▶️ Обработка возобновлена, раз в %d ч." % state["interval_hours"], mid)
    elif cmd == "/admins":
        send(chat, "\n".join("%s — %s" % (k, v) for k, v in state["admins"].items()) or "Пока никого", mid)
    elif cmd == "/removeadmin":
        if arg in state["admins"]:
            state["admins"].pop(arg)
            send(chat, "Администратор %s удалён." % arg, mid)
        else:
            send(chat, "Нет такого id. Список: /admins", mid)
    else:
        send(chat, "Неизвестная команда. /help — список команд.", mid)


def handle_task(state, msg, text, photos):
    chat = msg["chat"]["id"]
    mid = msg["message_id"]
    user = msg.get("from", {})
    image = save_photo(photos) if photos else None
    # ответ на сообщение бота про задачу #N — комментарий к этой задаче
    replied = (msg.get("reply_to_message") or {}).get("text") or ""
    m = re.search(r"#(\d+)", replied)
    body = (text or "").strip()
    if image:
        body += "\n\n![скриншот](%s)" % image
    meta = "<!-- tg chat=%s msg=%s from=%s -->" % (chat, mid, user.get("id"))
    if m:
        num = int(m.group(1))
        res = gh("POST", "/repos/%s/issues/%d/comments" % (REPO, num), {
            "body": "**%s** (Telegram):\n\n%s\n\n%s" % (who(user), body or "(без текста)", meta)})
        if res:
            gh("POST", "/repos/%s/issues/%d/labels" % (REPO, num), {"labels": [LABEL]})
            gh("PATCH", "/repos/%s/issues/%d" % (REPO, num), {"state": "open"})
            send(chat, "💬 Добавил к задаче #%d." % num, mid)
        return
    title = re.sub(r"\s+", " ", text or "Скриншот").strip()[:70] or "Задача из Telegram"
    issue = gh("POST", "/repos/%s/issues" % REPO, {
        "title": title,
        "body": "**От:** %s (Telegram)\n\n%s\n\n%s" % (who(user), body or "(без текста)", meta),
        "labels": [LABEL],
    })
    if issue:
        when = "на паузе — обработаю после /resume" if state["paused"] else "обработаю в течение ~%d ч" % state["interval_hours"]
        send(chat, "📝 Задача #%d принята, %s." % (issue["number"], when), mid)
    else:
        send(chat, "Не удалось создать задачу, попробуйте ещё раз позже.", mid)


def process_updates(state):
    updates = tg("getUpdates", {"offset": state["offset"], "timeout": 0, "allowed_updates": ["message"]}) or []
    print("Новых сообщений:", len(updates))
    for u in updates:
        state["offset"] = u["update_id"] + 1
        msg = u.get("message")
        if not msg:
            continue
        uid = str(msg.get("from", {}).get("id"))
        text = msg.get("text") or msg.get("caption") or ""
        photos = msg.get("photo") or []
        # стать администратором по коду
        if text.startswith("/admin ") or text == "/admin":
            code = text[len("/admin"):].strip()
            if ADMIN_CODE and code == ADMIN_CODE:
                state["admins"][uid] = who(msg.get("from", {}))
                send(msg["chat"]["id"], "✅ Вы администратор. /help — что я умею.", msg["message_id"])
            else:
                send(msg["chat"]["id"], "Неверный код.", msg["message_id"])
            continue
        if uid not in state["admins"]:
            send(msg["chat"]["id"], "Этот бот только для администраторов приложения «МоиСтраницы».\nВаш Telegram id: %s" % uid)
            continue
        if text.startswith("/"):
            handle_command(state, msg, text)
        elif text or photos:
            handle_task(state, msg, text, photos)


# ------------------------------------------------------------------ исходящие

def process_replies(state):
    """Комментарии Claude с меткой tg-reply → в Telegram автору задачи."""
    since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 3 * 24 * 3600))
    comments = gh("GET", "/repos/%s/issues/comments?since=%s&per_page=100" % (REPO, since)) or []
    sent = set(state["sent_comments"])
    chats = {}
    for c in comments:
        if c["id"] in sent or REPLY_MARK not in (c.get("body") or ""):
            continue
        num = int(c["issue_url"].rsplit("/", 1)[1])
        if num not in chats:
            issue = gh("GET", "/repos/%s/issues/%d" % (REPO, num)) or {}
            m = re.search(r"<!-- tg chat=(-?\d+) msg=(\d+)", issue.get("body") or "")
            chats[num] = (int(m.group(1)), int(m.group(2))) if m else None
        target = chats[num]
        state["sent_comments"].append(c["id"])
        if not target:
            continue
        text = c["body"].replace(REPLY_MARK, "").strip()
        send(target[0], "Задача #%d:\n%s" % (num, text), target[1])


def main():
    me = tg("getMe") or {}
    print("Бот: @%s" % me.get("username", "?"))
    hook = tg("getWebhookInfo") or {}
    if hook.get("url"):
        print("Найден webhook, отключаю:", hook.get("url"))
        tg("deleteWebhook")
    state = load_state()
    ensure_label()
    process_updates(state)
    process_replies(state)
    save_state(state)


if __name__ == "__main__":
    main()
