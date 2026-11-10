#!/usr/bin/env python3
"""harness 하위 세션(claude -p)의 대화를 브라우저에서 실시간으로 본다.

execute.py는 --output-format json이라 세션이 끝나야 출력을 받지만, Claude Code는 실행 중에도
~/.claude/projects/<인코딩된 cwd>/<session>.jsonl에 대화를 한 줄씩 기록한다. 이 파일을 이어 읽는다.

사용법: python3 scripts/session_viewer.py [--port 8765]  →  http://127.0.0.1:8765
"""

import argparse
import json
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CLAUDE_PROJECTS = Path.home() / ".claude" / "projects"
LIVE_SECONDS = 60
MAX_TEXT = 20000
STEP_RE = re.compile(r"^# Step (\d+): (\S+)", re.M)


def worktrees() -> list[dict]:
    out = subprocess.run(
        ["git", "worktree", "list", "--porcelain"], cwd=PROJECT_ROOT, capture_output=True, text=True
    ).stdout
    result, current = [], {}
    for line in out.splitlines() + [""]:
        if line.startswith("worktree "):
            current = {"path": line[len("worktree "):]}
        elif line.startswith("branch "):
            current["branch"] = line.removeprefix("branch refs/heads/")
        elif not line and current:
            # Claude Code는 cwd의 영숫자가 아닌 문자를 '-'로 바꿔 기록 디렉토리 이름을 만든다.
            current["log_dir"] = CLAUDE_PROJECTS / re.sub(r"[^a-zA-Z0-9]", "-", current["path"])
            current.setdefault("branch", "(detached)")
            result.append(current)
            current = {}
    return result


def session_meta(path: Path) -> dict:
    meta = {"entrypoint": None, "step": None, "retry": False, "title": None, "model": None}
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i > 200 and meta["entrypoint"] and meta["step"] is not None:
                break
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            meta["entrypoint"] = meta["entrypoint"] or o.get("entrypoint")
            if o.get("type") == "ai-title":
                meta["title"] = o.get("aiTitle") or meta["title"]
            msg = o.get("message") or {}
            if o.get("type") == "assistant" and not meta["model"]:
                meta["model"] = msg.get("model")
            if o.get("type") == "user" and meta["step"] is None and isinstance(msg.get("content"), str):
                text = msg["content"]
                m = STEP_RE.search(text)
                meta["step"] = f"step{int(m.group(1)):02d} {m.group(2)}" if m else ""
                meta["retry"] = "[RETRY CONTEXT]" in text
                if not meta["title"]:
                    meta["title"] = text.strip().splitlines()[0][:80] if text.strip() else ""
    return meta


def list_sessions(include_all: bool) -> list[dict]:
    now = time.time()
    sessions = []
    for wt in worktrees():
        if not wt["log_dir"].is_dir():
            continue
        for path in wt["log_dir"].glob("*.jsonl"):
            st = path.stat()
            meta = session_meta(path)
            if not include_all and meta["entrypoint"] != "sdk-cli":
                continue
            sessions.append({
                "id": path.stem, "worktree": Path(wt["path"]).name, "branch": wt["branch"],
                "mtime": st.st_mtime, "size": st.st_size, "live": now - st.st_mtime < LIVE_SECONDS, **meta,
            })
    sessions.sort(key=lambda s: s["mtime"], reverse=True)
    return sessions


def find_session(session_id: str) -> Path | None:
    if not re.fullmatch(r"[0-9a-f-]{36}", session_id):
        return None
    for wt in worktrees():
        path = wt["log_dir"] / f"{session_id}.jsonl"
        if path.is_file():
            return path
    return None


def clip(text: str) -> str:
    return text if len(text) <= MAX_TEXT else text[:MAX_TEXT] + f"\n… ({len(text) - MAX_TEXT}자 생략)"


def to_events(o: dict) -> list[dict]:
    """JSONL 한 줄을 화면에 그릴 이벤트로 바꾼다. 내부 기록(api-request 등)은 버린다."""
    kind, msg, ts = o.get("type"), o.get("message") or {}, o.get("timestamp")
    if kind not in ("user", "assistant") or o.get("isMeta"):
        return []
    content = msg.get("content")
    if isinstance(content, str):
        return [{"kind": kind, "text": clip(content), "ts": ts}]
    events = []
    for b in content or []:
        t = b.get("type")
        if t == "text" and b.get("text", "").strip():
            events.append({"kind": kind, "text": clip(b["text"]), "ts": ts})
        elif t == "thinking" and b.get("thinking", "").strip():
            events.append({"kind": "thinking", "text": clip(b["thinking"]), "ts": ts})
        elif t == "tool_use":
            events.append({"kind": "tool_use", "name": b.get("name"), "id": b.get("id"),
                           "input": clip(json.dumps(b.get("input"), ensure_ascii=False, indent=2)), "ts": ts})
        elif t == "tool_result":
            c = b.get("content")
            if isinstance(c, list):
                c = "\n".join(x.get("text", f"[{x.get('type')}]") for x in c)
            events.append({"kind": "tool_result", "id": b.get("tool_use_id"), "error": bool(b.get("is_error")),
                           "text": clip(str(c or "")), "ts": ts})
    return events


def read_session(path: Path, offset: int) -> dict:
    events = []
    with open(path, "rb") as f:
        f.seek(offset)
        data = f.read()
    # 쓰는 중인 마지막 줄은 다음 폴링에서 읽는다.
    end = data.rfind(b"\n") + 1
    for line in data[:end].splitlines():
        try:
            events.extend(to_events(json.loads(line)))
        except json.JSONDecodeError:
            continue
    st = path.stat()
    return {"events": events, "offset": offset + end, "live": time.time() - st.st_mtime < LIVE_SECONDS}


def phase_status() -> list[dict]:
    result = []
    for wt in worktrees():
        for index in sorted(Path(wt["path"]).glob("phases/*/index.json")):
            try:
                steps = json.loads(index.read_text(encoding="utf-8"))["steps"]
            except (OSError, ValueError, KeyError):
                continue
            result.append({"worktree": Path(wt["path"]).name, "phase": index.parent.name,
                           "steps": [{"step": s["step"], "name": s["name"], "status": s["status"]} for s in steps]})
    return result


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args) -> None:
        pass

    def send(self, body: bytes, ctype: str, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def json(self, data, status: int = 200) -> None:
        self.send(json.dumps(data, ensure_ascii=False).encode(), "application/json; charset=utf-8", status)

    def do_GET(self) -> None:
        url = urlparse(self.path)
        q = parse_qs(url.query)
        if url.path == "/":
            self.send(PAGE.encode(), "text/html; charset=utf-8")
        elif url.path == "/api/sessions":
            self.json(list_sessions(q.get("all", ["0"])[0] == "1"))
        elif url.path == "/api/phases":
            self.json(phase_status())
        elif url.path.startswith("/api/sessions/"):
            path = find_session(url.path.rsplit("/", 1)[1])
            if not path:
                self.json({"error": "not found"}, 404)
                return
            self.json(read_session(path, int(q.get("offset", ["0"])[0])))
        else:
            self.json({"error": "not found"}, 404)


PAGE = r"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Harness Sessions</title>
<style>
:root{--bg:#f7f7f5;--panel:#fff;--fg:#1d1d1b;--muted:#6b6b66;--line:#e3e2dd;--accent:#c96442;--user:#eef3fb;
--tool:#f4f1ea;--ok:#2f7d4f;--err:#b3261e;--live:#2f7d4f;--mono:ui-monospace,SFMono-Regular,Menlo,monospace}
@media (prefers-color-scheme:dark){:root{--bg:#1a1a18;--panel:#232321;--fg:#ecebe6;--muted:#9b9a93;--line:#3a3935;
--user:#25303f;--tool:#2c2a25;--ok:#6cc08b;--err:#f2867d;--live:#6cc08b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,system-ui,sans-serif}
.app{display:grid;grid-template-columns:340px 1fr;height:100vh}
aside{border-right:1px solid var(--line);overflow:auto;background:var(--panel)}
header{padding:12px 14px;border-bottom:1px solid var(--line);display:flex;gap:8px;align-items:center;position:sticky;top:0;background:var(--panel);z-index:1}
header h1{font-size:15px;margin:0;flex:1}label{color:var(--muted);font-size:12px}
.phases{padding:10px 14px;border-bottom:1px solid var(--line);font-size:12px}
.phase{margin-bottom:6px}.phase b{font-weight:600}.dots{display:flex;gap:3px;margin-top:3px;flex-wrap:wrap}
.dot{width:14px;height:14px;border-radius:3px;background:var(--line)}.dot.completed{background:var(--ok)}
.dot.error{background:var(--err)}.dot.blocked{background:#d9a400}.dot.pending{background:var(--line)}
.item{padding:10px 14px;border-bottom:1px solid var(--line);cursor:pointer}.item:hover{background:var(--bg)}
.item.sel{background:var(--bg);box-shadow:inset 3px 0 var(--accent)}
.item .t{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.item .m{color:var(--muted);font-size:12px;display:flex;gap:8px;flex-wrap:wrap}
.badge{font-size:11px;padding:0 6px;border-radius:9px;border:1px solid var(--line)}
.badge.live{color:var(--live);border-color:var(--live)}.badge.retry{color:var(--err);border-color:var(--err)}
main{overflow:auto;padding:16px 24px 80px}#empty{color:var(--muted);margin-top:40px;text-align:center}
.ev{max-width:980px;margin:0 0 10px}.ev .h{font-size:11px;color:var(--muted);margin-bottom:2px}
.bubble{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px 12px;white-space:pre-wrap;word-break:break-word}
.user .bubble{background:var(--user)}.thinking .bubble{color:var(--muted);font-style:italic}
details.tool{border:1px solid var(--line);border-radius:8px;background:var(--tool)}
details.tool summary{padding:6px 10px;cursor:pointer;font-family:var(--mono);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
details.tool pre{margin:0;padding:8px 10px;border-top:1px solid var(--line);font:12px/1.45 var(--mono);white-space:pre-wrap;word-break:break-word;max-height:480px;overflow:auto}
.res-err summary{color:var(--err)}.res-ok summary{color:var(--ok)}
#follow{position:fixed;right:20px;bottom:16px}
button{font:inherit;border:1px solid var(--line);background:var(--panel);color:var(--fg);border-radius:6px;padding:4px 10px;cursor:pointer}
@media (max-width:760px){.app{grid-template-columns:1fr;grid-template-rows:40vh 1fr}aside{border-right:0;border-bottom:1px solid var(--line)}main{padding:12px 16px 80px}}
</style></head><body>
<div class="app">
<aside>
  <header><h1>Harness 세션</h1><label><input type="checkbox" id="all"> 전체 세션</label></header>
  <div class="phases" id="phases"></div>
  <div id="list"></div>
</aside>
<main><div id="empty">왼쪽에서 세션을 고르세요. 실행 중인 세션은 <span class="badge live">live</span>로 표시되고 자동으로 갱신됩니다.</div><div id="log"></div></main>
</div>
<button id="follow">↓ 따라가기: 켬</button>
<script>
const $ = s => document.querySelector(s);
let selected = null, offset = 0, follow = true, toolNames = {};
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const ago = t => { const s = Math.round(Date.now()/1000 - t); return s < 60 ? s+"초 전" : s < 3600 ? Math.round(s/60)+"분 전" : new Date(t*1000).toLocaleString(); };
const time = ts => ts ? new Date(ts).toLocaleTimeString() : "";

async function loadList() {
  const [sessions, phases] = await Promise.all([
    fetch("/api/sessions?all=" + ($("#all").checked ? 1 : 0)).then(r => r.json()),
    fetch("/api/phases").then(r => r.json())]);
  $("#phases").innerHTML = phases.map(p => `<div class="phase"><b>${esc(p.phase)}</b> <span style="color:var(--muted)">${esc(p.worktree)}</span>
    <div class="dots">${p.steps.map(s => `<span class="dot ${esc(s.status)}" title="step${s.step} ${esc(s.name)}: ${esc(s.status)}"></span>`).join("")}</div></div>`).join("");
  $("#list").innerHTML = sessions.map(s => `<div class="item ${s.id === selected ? "sel" : ""}" data-id="${s.id}">
    <div class="t">${esc(s.step || s.title || s.id)}</div>
    <div class="m">${s.live ? '<span class="badge live">live</span>' : ""}${s.retry ? '<span class="badge retry">retry</span>' : ""}
    <span>${esc(s.worktree)}</span><span>${ago(s.mtime)}</span><span>${(s.size/1024).toFixed(0)}KB</span>${s.model ? `<span>${esc(s.model)}</span>` : ""}</div></div>`).join("")
    || '<div class="item m">세션이 없습니다.</div>';
}

function render(e) {
  const d = document.createElement("div");
  d.className = "ev " + e.kind;
  if (e.kind === "tool_use") {
    toolNames[e.id] = e.name;
    const first = e.input.replace(/\s+/g, " ").slice(0, 140);
    d.innerHTML = `<details class="tool"><summary>▶ ${esc(e.name)} ${esc(first)}</summary><pre>${esc(e.input)}</pre></details>`;
  } else if (e.kind === "tool_result") {
    const lines = e.text.split("\n").length;
    d.innerHTML = `<details class="tool ${e.error ? "res-err" : "res-ok"}"><summary>${e.error ? "✗" : "✓"} ${esc(toolNames[e.id] || "result")} · ${lines}줄</summary><pre>${esc(e.text)}</pre></details>`;
  } else {
    const label = {user: "user", assistant: "assistant", thinking: "thinking"}[e.kind];
    d.innerHTML = `<div class="h">${label} · ${time(e.ts)}</div><div class="bubble">${esc(e.text)}</div>`;
  }
  return d;
}

async function poll() {
  if (!selected) return;
  const id = selected;
  const r = await fetch(`/api/sessions/${id}?offset=${offset}`).then(r => r.json());
  if (id !== selected || r.error) return;
  offset = r.offset;
  const frag = document.createDocumentFragment();
  r.events.forEach(e => frag.appendChild(render(e)));
  $("#log").appendChild(frag);
  if (follow && r.events.length) $("main").scrollTop = $("main").scrollHeight;
}

$("#list").addEventListener("click", e => {
  const item = e.target.closest(".item[data-id]");
  if (!item) return;
  selected = item.dataset.id; offset = 0; toolNames = {};
  $("#log").innerHTML = ""; $("#empty").style.display = "none";
  document.querySelectorAll(".item").forEach(x => x.classList.toggle("sel", x === item));
  poll();
});
$("#all").addEventListener("change", loadList);
$("#follow").addEventListener("click", () => { follow = !follow; $("#follow").textContent = "↓ 따라가기: " + (follow ? "켬" : "끔"); });
loadList(); setInterval(loadList, 5000); setInterval(poll, 2000);
</script></body></html>
"""


def main() -> None:
    parser = argparse.ArgumentParser(description="harness 하위 세션 대화를 브라우저에서 실시간으로 본다.")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    # 대화에는 코드와 프롬프트 원문이 들어 있으므로 로컬에서만 연다.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"http://127.0.0.1:{args.port}  (Ctrl+C로 종료)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
