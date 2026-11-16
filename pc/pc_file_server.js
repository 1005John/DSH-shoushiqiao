/**
 * PC 文件服务 —— 给手机端的 DSH Go Agent 提供文件查询/读取能力。
 *
 * 只监听 127.0.0.1（配合 adb reverse 或 SSH 隧道使用）。
 * 所有请求需要 X-Token 头。
 *
 * 接口：
 *   GET /health
 *   GET /search?name=关键字[&root=D:\][&max=30]     按文件名搜索
 *   GET /stat?path=完整路径                          查是否存在/大小/时间
 *   GET /file?path=完整路径                          下载文件
 *   GET /list?path=目录路径                          列目录
 *
 * 启动：node pc_file_server.js --port 8088 --token <token>
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const idx = require('./pc_index');

const args = {};
process.argv.slice(2).forEach((v, i, a) => {
  if (v.startsWith('--')) args[v.slice(2)] = a[i + 1];
});

// 配置文件优先（token / root / exclude 都在里面，避免命令行泄露）
let CFG = {};
if (args.config) {
  try { CFG = JSON.parse(fs.readFileSync(args.config, 'utf8')); }
  catch (e) { console.error('读取配置失败: ' + e); }
}

const PORT = parseInt(args.port || CFG.filePort || '8088', 10);
const TOKEN = args.token || CFG.fileToken || 'dsh-pc-file';
const ROOTS = (args.roots ? args.roots.split('|') : (CFG.roots || [args.root || CFG.root || 'D:\\']))
  .filter(Boolean).map((r) => path.resolve(r));
const DEFAULT_ROOT = ROOTS[0];
const EXCLUDE = (args.exclude ? args.exclude.split(',') : (CFG.exclude || []))
  .map((s) => s.toLowerCase());
const BIND = args.bind || CFG.bind || '127.0.0.1';
const MAX_DEPTH = 8;

/** 本机所有局域网 IPv4，供客户端优先走局域网 */
function lanUrls() {
  const os = require('os');
  const out = [];
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    for (const a of ifs[name] || []) {
      if (a.family === 'IPv4' && !a.internal) out.push('http://' + a.address + ':' + PORT);
    }
  }
  return out;
}

/**
 * 路径安全检查：必须落在 ROOT 之内，且不含任何排除目录。
 * 返回规范化后的绝对路径；不合法返回 null。
 */
function safePath(p) {
  if (!p) return null;
  let full;
  try { full = path.resolve(p); } catch (_) { return null; }
  let rel = null;
  for (const root of ROOTS) {
    const r = path.relative(root, full);
    if (r === '' || (!r.startsWith('..') && !path.isAbsolute(r))) { rel = r; break; }
  }
  if (rel === null) return null;                      // 不在任何根目录内
  const segs = rel.split(path.sep).filter(Boolean);
  for (const s of segs) {
    if (EXCLUDE.includes(s.toLowerCase())) return null;
  }
  return full;
}

function isExcludedName(name) {
  return EXCLUDE.includes(String(name).toLowerCase());
}

function auth(req) {
  return req.headers['x-token'] === TOKEN;
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

/** 按文件名递归搜索（限制深度与结果数） */
function search(root, keyword, max, cb) {
  const out = [];
  const kw = keyword.toLowerCase();
  let stopped = false;

  function walk(dir, depth) {
    if (stopped || depth > MAX_DEPTH || out.length >= max) return;
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
      return; // 权限不足等，跳过
    }
    for (const ent of entries) {
      if (stopped || out.length >= max) return;
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        // 跳过系统目录和排除目录，加速
        if (/^\$|^System Volume Information$|^Windows$/i.test(ent.name)) continue;
        if (isExcludedName(ent.name)) continue;
        walk(full, depth + 1);
      } else if (ent.name.toLowerCase().includes(kw)) {
        try {
          const st = fs.statSync(full);
          out.push({ path: full, name: ent.name, size: st.size, mtime: st.mtimeMs });
        } catch (e) { /* ignore */ }
      }
    }
  }

  const t = setTimeout(() => { stopped = true; cb(out, true); }, 25000);
  setImmediate(() => {
    walk(root, 0);
    if (!stopped) {
      clearTimeout(t);
      cb(out, false);
    }
  });
}

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  console.log(line);
  try { fs.appendFileSync(path.join(__dirname, 'pc_file_server.log'), line + '\n'); } catch (_) {}
}

process.on('uncaughtException', (e) => { log('uncaught: ' + (e && e.stack || e)); });
process.on('unhandledRejection', (e) => { log('unhandledRejection: ' + e); });

const server = http.createServer((req, res) => {
 try {
  if (!auth(req)) return json(res, 401, { error: 'unauthorized' });

  const u = url.parse(req.url, true);
  const q = u.query;

  if (u.pathname === '/health') {
    return json(res, 200, {
      ok: true, root: DEFAULT_ROOT, roots: ROOTS, platform: process.platform,
      exclude_count: EXCLUDE.length,
      lan_urls: lanUrls(),
      index: idx.isReady() ? { count: idx.status().count } : null,
    });
  }

  if (u.pathname === '/search') {
    const name = q.name || '';
    if (!name) return json(res, 400, { error: 'name required' });
    const max = parseInt(q.max || '30', 10);

    // 指定了子目录 -> 现场遍历；否则优先用索引（毫秒级）
    if (!q.root && idx.isReady() && idx.rootMatches(ROOTS)) {
      const list = idx.search(name, max);
      return json(res, 200, {
        root: DEFAULT_ROOT, keyword: name, count: list.length,
        truncated: list.length >= max, source: 'index', items: list,
      });
    }

    const root = q.root ? safePath(q.root) : DEFAULT_ROOT;
    if (!root) return json(res, 403, { error: 'path outside allowed root or excluded' });
    return search(root, name, max, (list, truncated) =>
      json(res, 200, {
        root, keyword: name, count: list.length, truncated,
        source: idx.isReady() ? 'walk' : 'walk-no-index', items: list,
      }));
  }

  if (u.pathname === '/index/status') {
    return json(res, 200, idx.status());
  }

  if (u.pathname === '/reindex' && req.method === 'POST') {
    idx.rebuild(ROOTS, EXCLUDE, (ok, count, ms) => {
      json(res, 200, { ok, count, seconds: Math.round(ms / 1000) });
    });
    return;
  }

  if (u.pathname === '/stat') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    try {
      const st = fs.statSync(p);
      return json(res, 200, {
        exists: true, path: p, dir: st.isDirectory(),
        size: st.size, mtime: st.mtimeMs,
      });
    } catch (e) {
      return json(res, 200, { exists: false, path: p });
    }
  }

  if (u.pathname === '/list') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    try {
      const items = fs.readdirSync(p, { withFileTypes: true })
        .filter((e) => !isExcludedName(e.name))
        .map((e) => {
          const full = path.join(p, e.name);
          let size = 0, mtime = 0;
          try { const st = fs.statSync(full); size = st.size; mtime = st.mtimeMs; } catch (_) {}
          return { name: e.name, dir: e.isDirectory(), size, mtime };
        });
      return json(res, 200, { path: p, count: items.length, items });
    } catch (e) {
      return json(res, 500, { error: String(e) });
    }
  }

  if (u.pathname === '/file') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    let st;
    try { st = fs.statSync(p); } catch (e) { return json(res, 404, { error: 'not found' }); }
    if (st.isDirectory()) return json(res, 400, { error: 'is a directory' });
    try {
      // 不能把中文文件名直接塞进响应头（Node 会抛 ERR_INVALID_CHAR），
      // 用 RFC 5987 的 filename* 形式，百分号编码。
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': st.size,
        'Content-Disposition':
          "attachment; filename*=UTF-8''" + encodeURIComponent(path.basename(p)),
      });
      const rs = fs.createReadStream(p);
      rs.on('error', (e) => { log('read error: ' + e.message); try { res.destroy(); } catch (_) {} });
      res.on('error', (e) => { log('res error: ' + e.message); try { rs.destroy(); } catch (_) {} });
      rs.pipe(res);
    } catch (e) {
      log('file handler error: ' + e);
      try { res.destroy(); } catch (_) {}
    }
    return;
  }

  // 上传：原始字节写到 path 指定的位置（自动建父目录）
  //   POST /upload?path=D:\xxx\y.docx   body = 文件内容
  if (u.pathname === '/upload' && req.method === 'POST') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    try {
      const dir = path.dirname(p);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      const tmp = p + '.part';
      const ws = fs.createWriteStream(tmp);
      let size = 0;
      req.on('data', (c) => { size += c.length; });
      req.on('error', (e) => { log('upload req error: ' + e.message); try { ws.destroy(); } catch (_) {} });
      ws.on('error', (e) => { log('upload write error: ' + e.message); try { res.destroy(); } catch (_) {} });
      ws.on('finish', () => {
        try {
          fs.renameSync(tmp, p);   // 原子替换，避免写一半被读到
          log('uploaded ' + p + ' (' + size + ' bytes)');
          json(res, 200, { ok: true, path: p, size });
        } catch (e) {
          json(res, 500, { error: String(e) });
        }
      });
      req.pipe(ws);
    } catch (e) {
      json(res, 500, { error: String(e) });
    }
    return;
  }

  // 新建目录
  if (u.pathname === '/mkdir' && req.method === 'POST') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    try {
      fs.mkdirSync(p, { recursive: true });
      return json(res, 200, { ok: true, path: p });
    } catch (e) {
      return json(res, 500, { error: String(e) });
    }
  }

  // 删除（默认移到回收站目录，不直接抹掉）
  if (u.pathname === '/delete' && req.method === 'POST') {
    const p = safePath(q.path);
    if (!p) return json(res, 403, { error: 'path outside allowed root or excluded' });
    try {
      const trash = path.join(__dirname, 'trash');
      if (!fs.existsSync(trash)) fs.mkdirSync(trash, { recursive: true });
      const dest = path.join(trash, Date.now() + '_' + path.basename(p));
      fs.renameSync(p, dest);
      log('moved to trash: ' + p);
      return json(res, 200, { ok: true, trash: dest });
    } catch (e) {
      return json(res, 500, { error: String(e) });
    }
  }

  json(res, 404, { error: 'unknown endpoint' });
 } catch (e) {
  log('handler error: ' + (e && e.stack || e));
  try { json(res, 500, { error: String(e) }); } catch (_) {}
 }
});

server.listen(PORT, BIND, () => {
  console.log(`[pc-file-server] http://127.0.0.1:${PORT}  root=${DEFAULT_ROOT}`);
  // 后台构建文件索引（只读扫描），之后每 30 分钟刷新一次
  idx.start(ROOTS, EXCLUDE, 30 * 60 * 1000);
});
