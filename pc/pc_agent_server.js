/**
 * PC Agent 任务服务 v2 —— 手机把任务派给 PC 上的 dsh 执行，支持会话复用。
 *
 * 手机端接口（经 VPS 的 /agent/ 路径）：
 *   POST /task    {"prompt": "...", "session": "work"}   -> {"id": "..."}
 *   GET  /task/<id>                                      -> {"status","output","session"}
 *   GET  /sessions                                       -> 会话列表（id/轮次）
 *   POST /reset   {"session": "work"}                    -> 丢弃该会话（下次重新开）
 *   GET  /health
 *
 * 会话复用：
 *   同一 session 键的连续任务复用同一个 dsh Session，从而"记得"上下文。
 *   轮次超过 MAX_TURNS 或手动 /reset 时自动开新会话，防止上下文膨胀。
 *
 * ⚠️ 等价于「远程在 PC 上执行任意指令」，只通过带 token 的 HTTPS 暴露。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const args = {};
process.argv.slice(2).forEach((v, i, a) => {
  if (v.startsWith('--')) args[v.slice(2)] = a[i + 1];
});

// 配置文件优先（token 不在命令行里出现）
let CFG = {};
if (args.config) {
  try { CFG = JSON.parse(fs.readFileSync(args.config, 'utf8')); }
  catch (e) { console.error('读取配置失败: ' + e); }
}

const PORT = parseInt(args.port || CFG.agentPort || '8091', 10);
const TOKEN = args.token || CFG.agentToken || 'dsh-pc-agent';
const TIMEOUT_MS = parseInt(args.timeout || '600000', 10);
const MAX_TURNS = parseInt(args['max-turns'] || '20', 10);
const STATE_DIR = path.join(__dirname, 'agent_state');
const SESSION_FILE = path.join(STATE_DIR, 'sessions.json');

if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });

const tasks = new Map();

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  console.log(line);
  try { fs.appendFileSync(path.join(__dirname, 'pc_agent_server.log'), line + '\n'); } catch (_) {}
}

function loadSessions() {
  try { return JSON.parse(fs.readFileSync(SESSION_FILE, 'utf8')); } catch (_) { return {}; }
}
function saveSessions(s) {
  try { fs.writeFileSync(SESSION_FILE, JSON.stringify(s, null, 2), 'utf8'); } catch (_) {}
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => resolve(data));
  });
}

/** 解析 dsh --json 的 NDJSON 输出，取出最终答复 */
function parseEvents(stdout) {
  let sessionId = null;
  const texts = [];
  let finalText = null;
  for (const line of stdout.split('\n')) {
    const s = line.trim();
    if (!s.startsWith('{')) continue;
    let ev;
    try { ev = JSON.parse(s); } catch (_) { continue; }
    if (ev.type === 'session' && ev.sessionId) sessionId = ev.sessionId;
    else if (ev.type === 'text' && ev.text) texts.push(ev.text);
    else if (ev.type === 'final' && typeof ev.text === 'string') finalText = ev.text;
  }
  return { sessionId, text: finalText != null ? finalText : texts.join('\n') };
}

function runTask(id) {
  const t = tasks.get(id);
  if (!t) return;
  t.status = 'running';
  t.started = Date.now();

  const sessions = loadSessions();
  let entry = sessions[t.sessionKey];
  if (!entry || entry.turns >= MAX_TURNS) {
    entry = { sessionId: null, turns: 0 };
    sessions[t.sessionKey] = entry;
  }

  log('task ' + id + ' start sess=' + t.sessionKey +
      ' resume=' + (entry.sessionId || 'new') + ' turn=' + entry.turns);

  const promptFile = path.join(STATE_DIR, id + '.prompt.txt');
  try {
    fs.writeFileSync(promptFile, t.prompt, 'utf8');
  } catch (e) {
    t.status = 'error'; t.output = 'write prompt failed: ' + e; return;
  }

  const resume = entry.sessionId ? ('--session-id ' + entry.sessionId + ' ') : '';
  const psCmd = [
    '[Console]::OutputEncoding=[Text.Encoding]::UTF8;',
    '$OutputEncoding=[Text.Encoding]::UTF8;',
    '$k=[Environment]::GetEnvironmentVariable(\'CMAIOT_API_KEY\',\'User\');',
    'if($k){$env:CMAIOT_API_KEY=$k};',
    '$p = Get-Content -Raw -Encoding UTF8 \'' + promptFile.replace(/'/g, "''") + '\';',
    '& dsh headless --json ' + resume + '$p',
  ].join(' ');

  let stdout = '';
  let stderr = '';
  let child;
  try {
    child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', psCmd], {
      cwd: 'D:\\DSH', windowsHide: true,
    });
  } catch (e) {
    t.status = 'error'; t.output = 'spawn failed: ' + e; return;
  }

  child.stdout.on('data', (d) => { stdout += d.toString('utf8'); });
  child.stderr.on('data', (d) => { stderr += d.toString('utf8'); });

  let finalized = false;
  const finish = (status, output) => {
    if (finalized) return;
    finalized = true;
    t.status = status;
    t.output = output;
    t.finished = Date.now();
    try { fs.writeFileSync(path.join(STATE_DIR, id + '.json'), JSON.stringify(t, null, 2), 'utf8'); } catch (_) {}
    log('task ' + id + ' ' + status + ' (' + Math.round((t.finished - t.started) / 1000) + 's)');
  };

  const timer = setTimeout(() => {
    try { child.kill(); } catch (_) {}
    finish('error', (t.output || '') + '\n[超时 ' + Math.round(TIMEOUT_MS / 1000) + ' 秒，已终止]');
  }, TIMEOUT_MS);

  child.on('error', (e) => { clearTimeout(timer); finish('error', '进程错误：' + e); });

  child.on('close', (code) => {
    clearTimeout(timer);
    const parsed = parseEvents(stdout);
    if (parsed.sessionId) {
      const s = loadSessions();
      const cur = s[t.sessionKey] || { turns: 0 };
      s[t.sessionKey] = { sessionId: parsed.sessionId, turns: (cur.turns || 0) + 1 };
      saveSessions(s);
      t.sessionId = parsed.sessionId;
      t.turn = s[t.sessionKey].turns;
    }
    if (parsed.text && parsed.text.trim()) {
      finish('done', parsed.text.trim());
    } else if (code === 0) {
      finish('done', '（无文本输出）');
    } else {
      finish('error', '退出码 ' + code + '\n' + (stderr || stdout).slice(-4000));
    }
  });
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.headers['x-token'] !== TOKEN) return json(res, 401, { error: 'unauthorized' });
    const u = new URL(req.url, 'http://localhost');

    if (u.pathname === '/health') {
      return json(res, 200, {
        ok: true, timeout_sec: TIMEOUT_MS / 1000, max_turns: MAX_TURNS,
        sessions: Object.keys(loadSessions()),
      });
    }

    if (u.pathname === '/task' && req.method === 'POST') {
      const body = await readBody(req);
      let payload;
      try { payload = JSON.parse(body); } catch (e) { return json(res, 400, { error: 'bad json' }); }
      const prompt = (payload.prompt || '').trim();
      if (!prompt) return json(res, 400, { error: 'prompt required' });
      const key = (payload.session || 'default').replace(/[^A-Za-z0-9_-]/g, '') || 'default';
      const id = (payload.id || ('t' + Date.now())).replace(/[^A-Za-z0-9_-]/g, '');
      tasks.set(id, { id, prompt, sessionKey: key, status: 'queued', output: '', queued: Date.now() });
      setImmediate(() => runTask(id));
      return json(res, 200, { ok: true, id, session: key, hint: 'GET /task/' + id });
    }

    if (u.pathname.startsWith('/task/')) {
      const id = u.pathname.slice('/task/'.length);
      const t = tasks.get(id);
      if (!t) {
        const f = path.join(STATE_DIR, id + '.json');
        if (fs.existsSync(f)) return json(res, 200, JSON.parse(fs.readFileSync(f, 'utf8')));
        return json(res, 404, { error: 'unknown task' });
      }
      return json(res, 200, {
        id: t.id, status: t.status, session: t.sessionKey, turn: t.turn || 0,
        elapsed_sec: t.started ? Math.round(((t.finished || Date.now()) - t.started) / 1000) : 0,
        output: t.output,
      });
    }

    if (u.pathname === '/sessions') {
      const s = loadSessions();
      return json(res, 200, {
        max_turns: MAX_TURNS,
        items: Object.entries(s).map(([k, v]) => ({ key: k, sessionId: v.sessionId, turns: v.turns })),
      });
    }

    if (u.pathname === '/reset' && req.method === 'POST') {
      const body = await readBody(req);
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch (_) {}
      const key = (payload.session || 'default').replace(/[^A-Za-z0-9_-]/g, '') || 'default';
      const s = loadSessions();
      const had = !!s[key];
      delete s[key];
      saveSessions(s);
      return json(res, 200, { ok: true, session: key, cleared: had });
    }

    json(res, 404, { error: 'unknown endpoint' });
  } catch (e) {
    log('handler error: ' + (e && e.stack || e));
    try { json(res, 500, { error: String(e) }); } catch (_) {}
  }
});

process.on('uncaughtException', (e) => log('uncaught: ' + (e && e.stack || e)));

server.listen(PORT, '127.0.0.1', () => {
  log('pc-agent-server v2 on http://127.0.0.1:' + PORT + '  maxTurns=' + MAX_TURNS);
});
