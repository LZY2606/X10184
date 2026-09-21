const SAMPLE = `name: gpio_controller
description: 示例 GPIO 控制器，含别名、读清零、锁存与多字寄存器
data_width: 32
endianness: little
registers:
  - name: CTRL
    description: 控制寄存器
    offset: 0x00
    reset: 0x0
    fields:
      - {name: ENABLE, bits: 0, access: RW, reset: 0, description: 使能}
      - {name: MODE, bits: "[3:1]", access: RW, reset: 0}
      - {name: RSVD, bits: "[7:4]", access: RESERVED}
  - name: SET
    description: 写一置位原子入口
    offset: 0x00
    alias_of: CTRL
    alias_write: set
  - name: CLR
    description: 写一清零原子入口
    offset: 0x00
    alias_of: CTRL
    alias_write: clear
  - name: STATUS
    offset: 0x04
    description: 状态寄存器
    fields:
      - {name: DONE, bits: 0, access: RC, description: 读清零}
      - {name: ERROR, bits: 1, access: W1C}
      - {name: BUSY, bits: 2, access: RO}
  - name: LOCK
    offset: 0x08
    fields:
      - {name: LOCKED, bits: 0, access: RW, reset: 0}
  - name: SHADOW
    offset: 0x0C
    lock: {register: LOCK, bit: 0}
    fields:
      - {name: VALUE, bits: "[15:0]", access: RW, reset: 0}
      - {name: STAMP, bits: "[31:16]", access: RW, reset: 0}
  - name: BURST
    description: 64 位多字寄存器，含跨字位域
    offset: 0x10
    width: 64
    fields:
      - {name: PAYLOAD, bits: "[47:0]", access: RW}
      - {name: SEQ, bits: "[63:48]", access: RW}
effects:
  - name: start_done
    description: 使能写入触发 DONE 置位
    trigger: CTRL
    when_bits: [0]
    action: set
    target: {register: STATUS, field: DONE}
  - name: done_latches_stamp
    trigger: STATUS
    when_bits: [0]
    action: latch
    target: {register: SHADOW, field: STAMP}
    source: {register: BURST, field: SEQ}
`;

const $ = (id) => document.getElementById(id);
let analysis = null;
let ops = [];
let genResult = null;

document.querySelectorAll('nav button').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    btn.classList.add('active');
    $(btn.dataset.tab).classList.add('active');
  });
});

async function api(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body || {})
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

function hex(v, width) {
  const n = width ? Math.ceil(width / 4) : 8;
  try { return '0x' + BigInt(v === undefined || v === null ? 0 : v).toString(16).padStart(n, '0'); }
  catch (e) { return String(v); }
}

function renderDiagnostics(ds) {
  const box = $('diagnostics');
  if (!ds || ds.length === 0) {
    box.innerHTML = '<div class="diag INFO">无诊断信息。</div>';
    return;
  }
  box.innerHTML = ds.map(d => {
    const where = d.register ? ` <span class="code">[${d.register}${d.field ? '.' + d.field : ''}]</span>` : '';
    const path = d.path && d.path.length ? `<div class="path">最短冲突路径: ${d.path.join(' → ')}</div>` : '';
    return `<div class="diag ${d.severity}"><b>${d.severity}</b> <span class="code">${d.code}</span>${where}<div>${escapeHtml(d.message)}</div>${path}</div>`;
  }).join('');
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
}

async function analyze(persist) {
  const data = await api(persist ? '/api/revisions' : '/api/analyze', {yaml: $('yamlInput').value});
  analysis = data;
  $('metaInfo').innerHTML = `hash <b>${data.semanticHash}</b> rev ${data.revision}
    <span class="badge ${data.valid ? 'ok' : 'bad'}">${data.valid ? '有效' : '有错误'}</span>`;
  $('canonicalView').textContent = data.canonicalYaml;
  renderDiagnostics(data.diagnostics);
  renderLayoutControls();
  renderConflicts();
  renderOpRegs();
  return data;
}

$('loadSample').onclick = () => { $('yamlInput').value = SAMPLE; analyze(false).catch(showError); };
$('analyzeBtn').onclick = () => analyze(false).catch(showError);
$('saveRevision').onclick = async () => {
  await analyze(true);
  await loadHistory();
  alert('已保存修订 #' + analysis.revision);
};

function showError(e) {
  alert(e.message);
}

function currentRegs() {
  return analysis && analysis.registers ? analysis.registers : [];
}

function renderLayoutControls() {
  const sel = $('regSelect');
  const prev = sel.value;
  sel.innerHTML = currentRegs().map(r => `<option value="${r.name}">${r.name} @ ${r.offsetHex} (${r.width}b)</option>`).join('');
  if (prev) sel.value = prev;
  renderBitLayout();
}

$('regSelect').onchange = renderBitLayout;

function fieldAt(reg, bit) {
  return reg.fields.find(f => bit >= f.bitStart && bit <= f.bitEnd);
}

function renderBitLayout() {
  const name = $('regSelect').value;
  const reg = currentRegs().find(r => r.name === name);
  const box = $('bitLayout');
  const detail = $('fieldDetail');
  if (!reg) { box.innerHTML = ''; detail.textContent = ''; return; }
  const cells = [];
  for (let b = reg.width - 1; b >= 0; b--) {
    const f = fieldAt(reg, b);
    const label = f && f.bitStart === b ? f.name : '';
    cells.push(`<div class="bitcell access-${f ? f.access : 'EMPTY'}" data-bit="${b}" data-field="${f ? f.name : ''}">
      <span>${escapeHtml(label)}</span><small>${b}</small></div>`);
  }
  box.innerHTML = `<div class="pane-head">${reg.name} — 从高位到低位（可点击）</div><div class="bitrow">${cells.join('')}</div>`;
  box.querySelectorAll('.bitcell').forEach(c => c.onclick = () => {
    box.querySelectorAll('.bitcell').forEach(x => x.classList.remove('selected'));
    c.classList.add('selected');
    const f = fieldAt(reg, Number(c.dataset.bit));
    if (!f) { detail.textContent = `bit ${c.dataset.bit}: 未覆盖（隐式保留/未定义）`; return; }
    detail.innerHTML = `<b>${f.name}</b> [${f.bitEnd}:${f.bitStart}] access=${f.access} reset=${f.resetHex}
      mask=${f.maskHex}${f.latchFrom ? ' latch_from=' + f.latchFrom : ''}<br>${escapeHtml(f.description || '')}`;
  });
}

function renderConflicts() {
  const ds = analysis ? analysis.diagnostics : [];
  const serious = ds.filter(d => d.severity !== 'INFO');
  $('conflictList').innerHTML = serious.length === 0
    ? '<div class="diag INFO">没有错误或警告。</div>'
    : serious.map(d => {
      const path = d.path && d.path.length
        ? `<div class="path">${d.path.join(' → ')}（环闭合于效果 ${escapeHtml(d.effect || '')}）</div>` : '';
      return `<div class="diag ${d.severity}"><b>${d.severity}</b> ${d.code}: ${escapeHtml(d.message)}${path}</div>`;
    }).join('');
}

function renderOpRegs() {
  const opts = currentRegs().map(r => `<option value="${r.name}">${r.name}</option>`).join('');
  $('opReg').innerHTML = opts;
}

$('addOp').onclick = () => {
  const raw = $('opValue').value.trim();
  let value = null;
  if (raw) value = raw.startsWith('0x') ? parseInt(raw, 16) : Number(raw);
  const wordRaw = $('opWord').value.trim();
  ops.push({type: $('opType').value, register: $('opReg').value, value,
    word: wordRaw === '' ? null : Number(wordRaw)});
  renderOps();
};
$('resetOps').onclick = () => { ops = []; renderOps(); $('simTrace').innerHTML = ''; };

function renderOps() {
  $('opTable').querySelector('tbody').innerHTML = ops.map((o, i) =>
    `<tr><td>${i}</td><td>${o.type}</td><td>${o.register}</td>
     <td>${o.value === null ? '' : hex(o.value)}</td><td>${o.word ?? ''}</td>
     <td><button data-i="${i}" class="delop">删</button></td></tr>`).join('');
  document.querySelectorAll('.delop').forEach(b => b.onclick = () => {
    ops.splice(Number(b.dataset.i), 1); renderOps();
  });
}

$('runOps').onclick = async () => {
  const data = await api('/api/simulate', {yaml: $('yamlInput').value, operations: ops});
  $('simTrace').innerHTML = data.steps.map((s, i) => {
    const evs = (s.events || []).map(e => {
      const ch = Object.keys(e.changes || {}).map(k => `${k} xor ${hex(e.changes[k])}`).join(', ');
      return `<div class="ev">▸ ${e.kind}: ${escapeHtml(e.detail || '')} ${escapeHtml(ch)}</div>`;
    }).join('');
    const state = Object.entries(s.state || {}).map(([k, v]) => `<span>${k}=${v}</span>`).join('');
    const valLine = s.type === 'write' ? `写入 ${hex(s.written)} → ${hex(s.value)}` :
      `${s.type}${s.word !== null && s.word !== undefined ? ' word ' + s.word : ''} = ${hex(s.value)}`;
    return `<div class="step ${s.blocked ? 'blocked' : ''}">#${i} <b>${s.type}</b> ${s.register}
      ${s.word !== null && s.word !== undefined ? '(word ' + s.word + ')' : ''} — ${valLine}
      ${s.note ? '<div class="ev">' + escapeHtml(s.note) + '</div>' : ''}${evs}
      <div class="state-grid">${state}</div></div>`;
  }).join('') + `<div class="step">最终状态<div class="state-grid">${
    Object.entries(data.finalState || {}).map(([k, v]) => `<span>${k}=${v}</span>`).join('')}</div></div>`;
};

$('runDiff').onclick = async () => {
  const data = await api('/api/diff', {yamlA: $('yamlA').value, yamlB: $('yamlB').value});
  if (!data.entries.length) { $('diffView').innerHTML = '<div class="diag INFO">两个版本语义相同。</div>'; return; }
  $('diffView').innerHTML = data.entries.map(e => `
    <div class="diff-entry ${e.kind}"><b>${e.kind}</b> · ${e.scope} — ${escapeHtml(e.detail)}
      <div>${e.from ? '从 <code>' + escapeHtml(e.from) + '</code> 到 <code>' + escapeHtml(e.to) + '</code>' : ''}</div>
      ${e.affectedApis && e.affectedApis.length ? `<div class="api-list">受影响 API: ${[...new Set(e.affectedApis)].map(escapeHtml).join(' · ')}</div>` : ''}
    </div>`).join('');
};

$('genBtn').onclick = async () => {
  genResult = await api('/api/generate', {yaml: $('yamlInput').value});
  renderGenerated();
};
$('dlZip').onclick = async () => {
  const res = await fetch('/api/generate.zip', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({yaml: $('yamlInput').value})});
  if (!res.ok) { alert(await res.text()); return; }
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'register-mold-generated.zip'; a.click();
};
$('twiceBtn').onclick = async () => {
  const a = await api('/api/generate', {yaml: $('yamlInput').value});
  const b = await api('/api/generate', {yaml: $('yamlInput').value});
  const same = JSON.stringify(a.files) === JSON.stringify(b.files);
  $('genMeta').innerHTML = `<div class="diag ${same ? 'INFO' : 'ERROR'}">两次生成${same ? '逐字节一致（确定性）' : '不一致！'}</div>`;
};

function renderGenerated() {
  $('genMeta').innerHTML = `semantic-hash: <b>${genResult.semanticHash}</b>，文件: ${Object.keys(genResult.files).join(', ')}`;
  $('genFiles').innerHTML = `<div class="file-tabs">${Object.keys(genResult.files).map((f, i) =>
    `<button data-f="${f}" class="gentab">${f}</button>`).join('')}</div><pre class="genfile" id="genFileBody"></pre>`;
  const show = (f) => $('genFileBody').textContent = genResult.files[f];
  document.querySelectorAll('.gentab').forEach((b, i) => b.onclick = () => show(b.dataset.f));
  show(Object.keys(genResult.files)[0]);
}

async function loadHistory() {
  const res = await fetch('/api/models');
  const models = await res.json();
  const view = $('historyView');
  if (!models.length) { view.innerHTML = '<div class="diag INFO">还没有保存任何修订。</div>'; syncRevSelects([]); return; }
  view.innerHTML = models.map(m => `
    <div class="diff-entry"><b>${escapeHtml(m.name)}</b>
      <span class="badge ${m.latest_hash ? 'ok' : 'bad'}">${m.latest_hash || '无'}</span>
      共 ${m.revisions} 个修订
      <div class="api-list"><button data-model="${escapeHtml(m.name)}" class="showrevs">查看修订</button></div>
      <div id="revs-${escapeHtml(m.name)}"></div></div>`).join('');
  document.querySelectorAll('.showrevs').forEach(btn => btn.onclick = async () => {
    const name = btn.dataset.model;
    const res = await fetch(`/api/models/${encodeURIComponent(name)}/revisions`);
    const revs = await res.json();
    const box = $(`revs-${name}`);
    box.innerHTML = revs.map(r => `
      <div class="api-list">#${r.revision} hash=${r.semantic_hash}
      valid=${r.valid === 1} @ ${r.created_at}
      <button data-model="${escapeHtml(name)}" data-rev="${r.revision}" class="loadA">→A</button>
      <button data-model="${escapeHtml(name)}" data-rev="${r.revision}" class="loadB">→B</button>
      <button data-model="${escapeHtml(name)}" data-rev="${r.revision}" class="loadEditor">→编辑器</button></div>`).join('');
    syncRevSelects(models);
    box.querySelectorAll('.loadA').forEach(b => b.onclick = () => loadRevisionInto(b, 'yamlA', 'revA'));
    box.querySelectorAll('.loadB').forEach(b => b.onclick = () => loadRevisionInto(b, 'yamlB', 'revB'));
    box.querySelectorAll('.loadEditor').forEach(b => b.onclick = async () => {
      const row = await fetchRevision(b.dataset.model, b.dataset.rev);
      $('yamlInput').value = row.original_yaml;
      analyze(false).catch(showError);
    });
  });
  syncRevSelects(models);
}

function syncRevSelects(models) {
  // revision dropdowns are populated lazily from history buttons; kept for future quick switching
}

async function fetchRevision(model, rev) {
  const res = await fetch(`/api/models/${encodeURIComponent(model)}/revisions/${rev}`);
  return res.json();
}

async function loadRevisionInto(btn, textareaId, selectId) {
  const row = await fetchRevision(btn.dataset.model, btn.dataset.rev);
  $(textareaId).value = row.original_yaml;
}

$('refreshHistory').onclick = () => loadHistory().catch(showError);

$('yamlInput').value = SAMPLE;
analyze(false).catch(showError);
loadHistory().catch(() => {});
