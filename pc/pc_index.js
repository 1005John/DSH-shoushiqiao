/**
 * 文件索引 —— 只读扫描，绝不修改被扫描的目录。支持多个根目录。
 *
 * 索引内容：{ p: 完整路径, n: 文件名, s: 大小, m: 修改时间 }
 * 索引文件：<本目录>/index/files.json （在被扫描目录之外）
 *
 * 用法：
 *   const idx = require('./pc_index');
 *   idx.start([rootA, rootB], exclude);
 *   idx.search(kw, max) / idx.status() / idx.rebuild(roots, exclude)
 */
const fs = require('fs');
const path = require('path');

const IDX_DIR = path.join(__dirname, 'index');
const IDX_FILE = path.join(IDX_DIR, 'files.json');
const MAX_DEPTH = 40;   // 深层目录（媒体缓存）可能超过 20 层

let files = [];
let ready = false;
let building = false;
let builtAt = 0;
let lastMs = 0;
let indexRoots = [];
let timer = null;

function log(msg) {
  const line = new Date().toISOString() + ' [index] ' + msg;
  console.log(line);
  try { fs.appendFileSync(path.join(__dirname, 'pc_index.log'), line + '\n'); } catch (_) {}
}

function norm(roots) {
  const arr = Array.isArray(roots) ? roots : [roots];
  return arr.filter(Boolean).map((r) => path.resolve(r));
}

function sameRoots(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  const sa = [...a].sort();
  const sb = [...b].sort();
  return sa.every((v, i) => v === sb[i]);
}

function loadFromDisk(roots) {
  try {
    const d = JSON.parse(fs.readFileSync(IDX_FILE, 'utf8'));
    if (d && Array.isArray(d.files) && sameRoots(d.roots, roots)) {
      files = d.files;
      builtAt = d.builtAt || 0;
      indexRoots = d.roots;
      ready = files.length > 0;
      log('已加载索引 ' + files.length + ' 条（' + d.roots.length + ' 个根，构建于 '
          + new Date(builtAt).toLocaleString() + '）');
      return true;
    }
  } catch (_) { /* 没有索引 */ }
  return false;
}

function saveToDisk() {
  try {
    if (!fs.existsSync(IDX_DIR)) fs.mkdirSync(IDX_DIR, { recursive: true });
    const tmp = IDX_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ roots: indexRoots, builtAt, files }), 'utf8');
    fs.renameSync(tmp, IDX_FILE);
  } catch (e) {
    log('保存索引失败: ' + e.message);
  }
}

/** 只读遍历：readdir + stat，没有任何写操作 */
function walk(roots, exclude) {
  const out = [];
  let dirs = 0;
  const ex = (exclude || []).map((s) => String(s).toLowerCase());

  function rec(dir, depth) {
    if (depth > MAX_DEPTH) return;
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { return; }
    dirs++;
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ex.includes(ent.name.toLowerCase())) continue;
      if (ent.isDirectory()) {
        rec(full, depth + 1);
      } else {
        let s = 0, m = 0;
        try { const st = fs.statSync(full); s = st.size; m = st.mtimeMs; } catch (_) {}
        out.push({ p: full, n: ent.name, s, m });
      }
    }
  }

  for (const r of roots) rec(r, 0);
  return { files: out, dirs };
}

function rebuild(roots, exclude, cb) {
  const rs = norm(roots);
  if (building) { if (cb) cb(false, files.length, 0); return; }
  building = true;
  const t0 = Date.now();
  log('开始构建索引: ' + rs.join(' | '));
  setImmediate(() => {
    try {
      const res = walk(rs, exclude);
      files = res.files;
      indexRoots = rs;
      builtAt = Date.now();
      lastMs = builtAt - t0;
      ready = true;
      saveToDisk();
      log('索引完成: ' + files.length + ' 个文件 / ' + res.dirs
          + ' 个目录，耗时 ' + Math.round(lastMs / 1000) + ' 秒');
      if (cb) cb(true, files.length, lastMs);
    } catch (e) {
      log('构建失败: ' + e.message);
      if (cb) cb(false, files.length, 0);
    } finally {
      building = false;
    }
  });
}

function start(roots, exclude, intervalMs) {
  const rs = norm(roots);
  if (!loadFromDisk(rs)) rebuild(rs, exclude);
  if (timer) clearInterval(timer);
  timer = setInterval(() => rebuild(rs, exclude), intervalMs || 30 * 60 * 1000);
  if (timer.unref) timer.unref();
}

function isReady() { return ready; }

function rootMatches(roots) {
  return sameRoots(indexRoots, norm(roots));
}

function search(kw, max) {
  const needle = String(kw).toLowerCase();
  const lim = max || 30;
  const out = [];
  for (let i = 0; i < files.length && out.length < lim; i++) {
    const f = files[i];
    if (f.n.toLowerCase().includes(needle)) {
      out.push({ path: f.p, name: f.n, size: f.s, mtime: f.m });
    }
  }
  return out;
}

function status() {
  return {
    ready, building, count: files.length, builtAt,
    builtAtText: builtAt ? new Date(builtAt).toLocaleString() : null,
    lastBuildSec: Math.round(lastMs / 1000),
    roots: indexRoots,
  };
}

module.exports = { start, rebuild, isReady, rootMatches, search, status, loadFromDisk };