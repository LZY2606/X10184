'use strict';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const SAMPLE = `name: demo_timer
version: "1.2"
address_bits: 32
word_bytes: 4
endianness: le
registers:
  - name: CTRL
    address: 0x4000
    width_bits: 32
    fields:
      - name: EN
        bits: 0
        access: rw
        description: timer enable
      - name: MODE
        bits: [2, 1]
        access: rw
        reset: 1
      - name: UPDATE
        bits: 3
        access: w1s
        description: write-one triggers SHADOW->ACTIVE
      - name: LOCKED
        bits: 4
        access: ro
        reset: 0
  - name: CTRL_SET
    address: 0x4000
    width_bits: 32
    access: w1s
    alias_of: CTRL
  - name: CTRL_CLR
    address: 0x4000
    width_bits: 32
    access: w1c
    alias_of: CTRL
  - name: STATUS
    address: 0x4008
    width_bits: 32
    fields:
      - name: DONE
        bits: 0
        access: rc
        description: read-clear completion flag
      - name: OVF
        bits: 1
        access: w1c
      - name: SPARE
        bits: [31, 16]
        access: rsvd
        reset: 0x5a
  - name: PAIR
    address: 0x4010
    width_bits: 64
    read_order: low-first
    fields:
      - name: COUNT
        bits: [47, 0]
        access: ro
      - name: NEW
        bits: [55, 48]
        access: rw
  - name: SHADOW
    address: 0x4020
    width_bits: 32
    fields:
      - name: LIMIT
        bits: [15, 0]
        access: rw
  - name: ACTIVE
    address: 0x4024
    width_bits: 32
    fields:
      - name: LIMIT
        bits: [15, 0]
        access: ro
effects:
  - trigger: CTRL.UPDATE
    action: set
    target: CTRL.LOCKED
  - trigger: CTRL.UPDATE
    action: set
    target: SHADOW.LIMIT
locks:
  - target: SHADOW
    master: CTRL.LOCKED
`;

async function api(path, options) {
  const res = await fetch(path, options);
  return res.json();
}
function post(path, body) {
  return api(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body || {})
  });
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
function issueHtml(issues) {
  if (!issues || !issues.length) return '<div class="issue" style="background:rgba(52,199,138,.12);border:1px solid rgba(52,199,138,.4)">通过：未发现问题</div>';
  return issues.map((i) => `<div class="issue ${i.severity}"><span class="code">${esc(i.code)}</span><span>${esc(i.message)}</span></div>`).join('');
}

// ---- tabs -----------------------------------------------------------------
$$('.tabs button').forEach((btn) => {
  btn.addEventListener('click', () => {
    $$('.tabs button').forEach((b) => b.classList.remove('active'));
    $$('.tab').forEach((t) => t.classList.remove('active'));
    btn.classList.add('active');
    $('#tab-' + btn.dataset.tab).classList.add('active');
    if (btn.dataset.tab !== 'edit') refreshRevisions();
  });
});

// ---- edit tab -------------------------------------------------------------
$('#btn-sample').addEventListener('click', () => { $('#yaml-input').value = SAMPLE; });
$('#btn-validate').addEventListener('click', async () => {
  const panel = $('#validate-panel');
  panel.classList.remove('hidden');
  panel.innerHTML = '校验中…';
  const r = await post('/api/validate', { yaml: $('#yaml-input').value });
  let html = issueHtml(r.issues);
  if (r.hash) html += `<div class="issue" style="background:rgba(91,140,255,.1);border:1px solid rgba(91,140,255,.35)"><span class="code">CANONICAL</span><span>SHA-256 <span class="mono">${esc(r.hash)}</span>（相同语义、不同键序得到相同哈希）</span></div>`;
  panel.innerHTML = html;
});
$('#btn-import').addEventListener('click', async () => {
  const status = $('#edit-status');
  status.textContent = '导入中…';
  const r = await post('/api/models', { yaml: $('#yaml-input').value });
  if (r.ok) {
    status.textContent = `已保存为修订 #${r.revisionId}${r.reused ? '（语义已存在，复用）' : ''}`;
    $('#validate-panel').classList.remove('hidden');
    $('#validate-panel').innerHTML = issueHtml(r.issues);
    await refreshRevisions();
  } else {
    status.textContent = '导入被校验阻止';
    const panel = $('#validate-panel');
    panel.classList.remove('hidden');
    panel.innerHTML = issueHtml(r.issues);
  }
});

// ---- revisions ------------------------------------------------------------
async function refreshRevisions() {
  const list = await api('/api/models');
  const opts = list.map((r) => `<option value="${r.id}">#${r.id} ${esc(r.name)} ${esc(r.version)}</option>`).join('');
  ['#layout-revision', '#conflict-revision', '#sim-revision', '#gen-revision'].forEach((sel) => {
    const el = $(sel);
    const prev = el.value;
    el.innerHTML = opts;
    if (prev) el.value = prev;
  });
  const opts2 = list.map((r) => `<option value="${r.id}">#${r.id} ${esc(r.name)} ${esc(r.version)}</option>`).join('');
  $('#diff-a').innerHTML = opts2;
  $('#diff-b').innerHTML = opts2;
  if (list.length >= 2) { $('#diff-a').value = list[list.length - 2].id; $('#diff-b').value = list[list.length - 1].id; }
  const tbody = $('#rev-table tbody');
  tbody.innerHTML = list.map((r) => `<tr><td>${r.id}</td><td>${esc(r.name)}</td><td>${esc(r.version)}</td><td class="mono">${esc(String(r.hash).slice(0, 16))}…</td><td>${esc(r.created_at)}</td></tr>`).join('');
  if (list.length) { renderLayout($('#layout-revision').value); renderConflicts($('#conflict-revision').value); populateSimRegs(); }
}
$('#rev-refresh').addEventListener('click', refreshRevisions);

// ---- layout tab -----------------------------------------------------------
const ACCESS_CLASS = { rw: 'ac-rw', ro: 'ac-ro', rc: 'ac-rc', w1c: 'ac-w1c', w1s: 'ac-w1s', rsvd: 'ac-rsvd' };
async function renderLayout(id) {
  if (!id) { $('#layout-view').innerHTML = '<p class="status">尚无修订，请先导入 YAML。</p>'; return; }
  const model = await api(`/api/models/${id}`);
  $('#layout-meta').textContent = `${model.name} v${model.version} · ${model.addressBits} 位地址 · ${model.wordBytes} 字节字 · ${model.endianness.toUpperCase()} · sha ${model.hash.slice(0, 12)}`;
  $('#layout-view').innerHTML = model.registers.map((r) => registerBlock(r)).join('');
  $$('#layout-view .field-cell').forEach((cell) => {
    cell.addEventListener('click', () => showFieldDetail(model, cell.dataset.reg, cell.dataset.field));
  });
}
function registerBlock(r) {
  // Build display segments high->low: fields plus reserved gaps.
  const sorted = r.fields.slice().sort((a, b) => b.lsb - a.lsb);
  const segs = [];
  let cursor = r.widthBits - 1;
  for (const f of sorted) {
    if (f.msb < cursor) {
      segs.push({ reserved: true, width: cursor - f.msb });
    }
    segs.push(f);
    cursor = f.lsb - 1;
  }
  if (cursor >= 0) {
    segs.push({ reserved: true, width: cursor + 1 });
  }
  const cells = segs.map((f) => {
    if (f.reserved) {
      return `<div class="field-cell ac-rsvd" style="flex:${f.width}" title="未声明位（按保留处理）"><span class="fname">${f.width > 3 ? 'reserved' : ''}</span></div>`;
    }
    const label = f.width > 3 ? f.name : '';
    return `<div class="field-cell ${ACCESS_CLASS[f.access] || ''}" style="flex:${f.width}" data-reg="${esc(r.name)}" data-field="${esc(f.name)}" title="${esc(f.name)} [${f.msb}:${f.lsb}] ${f.access}"><span class="fname">${esc(label)}</span><span class="frange">${f.width > 1 ? f.msb + ':' + f.lsb : f.lsb}</span></div>`;
  }).join('');
  const ruler = Array.from({ length: r.widthBits }, (_, i) => `<span>${r.widthBits - 1 - i}</span>`).join('');
  return `<div class="reg-block">
    <div class="reg-head"><strong>${esc(r.name)}</strong><span class="addr">${esc(r.address)}</span>
      <span class="tag">${r.widthBits} 位</span><span class="tag">${esc(r.access)}</span>
      ${r.aliasOf ? `<span class="tag">alias → ${esc(r.aliasOf)}</span>` : ''}
      ${r.readOrder ? `<span class="tag">读序 ${esc(r.readOrder)}</span>` : ''}
    </div>
    <div class="bits">${cells}</div>
    <div class="bit-ruler">${ruler}</div>
  </div>`;
}
async function showFieldDetail(model, regName, fieldName) {
  const r = model.registers.find((x) => x.name === regName);
  const f = r.fields.find((x) => x.name === fieldName);
  const mask = ((BigInt(f.width) === 64n ? ~0n : ((1n << BigInt(f.width)) - 1n)) << BigInt(f.lsb));
  const pfx = model.name.replace(/[^A-Za-z0-9]+/g, '_').toLowerCase();
  const sym = `${pfx}_${regName.replace(/[^A-Za-z0-9]+/g, '_').toLowerCase()}_${fieldName.replace(/[^A-Za-z0-9]+/g, '_').toLowerCase()}`;
  const panel = $('#field-detail');
  panel.classList.remove('hidden');
  panel.innerHTML = `
    <h3>${esc(regName)}.${esc(fieldName)}</h3>
    <table>
      <tr><th>位</th><td>${f.msb}..${f.lsb}（宽 ${f.width}）</td></tr>
      <tr><th>访问</th><td>${esc(f.access)}</td></tr>
      <tr><th>复位</th><td class="mono">${esc(f.reset)}</td></tr>
      <tr><th>掩码</th><td class="mono">0x${mask.toString(16)}</td></tr>
      <tr><th>说明</th><td>${esc(f.description || '')}</td></tr>
      <tr><th>生成物追溯</th><td class="mono">${sym}_shift / _mask / _reset @ model ${esc(model.hash)}</td></tr>
    </table>`;
}
$('#layout-revision').addEventListener('change', (e) => renderLayout(e.target.value));

// ---- conflicts tab --------------------------------------------------------
$('#conflict-revision').addEventListener('change', (e) => renderConflicts(e.target.value));
async function renderConflicts(id) {
  if (!id) { $('#conflict-view').innerHTML = ''; return; }
  const model = await api(`/api/models/${id}`);
  const errors = (model.issues || []).filter((i) => i.severity === 'error');
  const warnings = (model.issues || []).filter((i) => i.severity === 'warning');
  const graph = [];
  model.effects.forEach((e) => graph.push(`<div class="chain">${esc(e.trigger)} ──<span class="${e.action}">${e.action}</span>──▶ ${esc(e.target)}</div>`));
  const lockHtml = model.locks.map((l) => `<div class="chain">🔒 ${esc(l.target)} 写入被阻止，当 ${esc(l.master)} ≠ 0</div>`).join('');
  $('#conflict-view').innerHTML = `
    <h3>校验结果（${errors.length} 错误 / ${warnings.length} 警告）</h3>
    ${issueHtml(model.issues)}
    <h3>副作用图</h3>${graph.join('') || '<p class="status">无副作用</p>'}
    <h3>锁关系</h3>${lockHtml || '<p class="status">无锁</p>'}`;
  $$('#conflict-view .issue.error').forEach((el) => {
    if (/SIDE_EFFECT/.test(el.textContent)) el.style.borderColor = 'rgba(232,107,107,.8)';
  });
}

// ---- simulation tab -------------------------------------------------------
let simSessionId = null;
let simModel = null;
$('#sim-revision').addEventListener('change', populateSimRegs);
async function populateSimRegs() {
  const id = $('#sim-revision').value;
  if (!id) return;
  simModel = await api(`/api/models/${id}`);
  $('#sim-reg').innerHTML = simModel.registers.map((r) => `<option value="${esc(r.name)}">${esc(r.name)} @ ${esc(r.address)}</option>`).join('');
}
$('#sim-start').addEventListener('click', async () => {
  const id = $('#sim-revision').value;
  const s = await post(`/api/models/${id}/sim`);
  simSessionId = s.id;
  $('#sim-session').textContent = `会话 #${s.id}（修订 #${id}）`;
  renderSimState(s);
});
$('#sim-reset').addEventListener('click', async () => {
  if (!simSessionId) return;
  const s = await post(`/sim/${simSessionId}/reset`);
  renderSimState(s);
});
$('#sim-step').addEventListener('click', async () => {
  if (!simSessionId) { alert('请先开始预演会话'); return; }
  const op = $('#sim-op').value;
  const reg = $('#sim-reg').value;
  let r;
  if (op === 'write') {
    r = await post(`/sim/${simSessionId}/write`, { register: reg, value: $('#sim-value').value });
  } else if (op === 'read') {
    r = await post(`/sim/${simSessionId}/read`, { register: reg, word: Number($('#sim-word').value || 0) });
  } else if (op === 'peek') {
    r = await api(`/sim/${simSessionId}/peek/${encodeURIComponent(reg)}`);
  } else {
    r = await post(`/sim/${simSessionId}/atomic`, {
      register: reg,
      setMask: $('#sim-value').value,
      clearMask: $('#sim-clear').value
    });
  }
  if (op === 'peek') {
    const state = await api(`/sim/${simSessionId}`);
    renderSimState(state);
    $('#sim-log').insertAdjacentHTML('afterbegin',
      `<div class="log-entry">peek ${esc(reg)} → <b>${esc(r.value)}</b><div class="ev">${esc(r.note)}</div></div>`);
  } else {
    renderSimState({ registers: r.state, log: [] });
    renderLogEntry(r);
  }
});
function renderLogEntry(r) {
  const evs = (r.events || []).map((e) => {
    const ch = [e.type, e.detail].join(' ');
    return `<div class="ev ${e.type}">▸ ${esc(ch)}`
      + (e.before !== undefined && e.after !== undefined ? ` <span class="mono">[${esc(e.before)} → ${esc(e.after)}]</span>` : '')
      + `</div>`;
  }).join('');
  $('#sim-log').insertAdjacentHTML('afterbegin',
    `<div class="log-entry">${esc(r.op)} ${esc(r.target)}${r.input ? ' ' + esc(r.input) : ''} → <b>${esc(r.value)}</b>${r.error ? ' <span style="color:var(--bad)">' + esc(r.error) + '</span>' : ''}${evs}</div>`);
}
function renderSimState(s) {
  const rows = Object.entries(s.registers || {}).map(([k, v]) =>
    `<div class="regline"><span class="rn">${esc(k)}</span><span>${esc(v)}</span></div>`).join('');
  $('#sim-state').innerHTML = rows;
  const fullLog = (s.log || []).slice().reverse().map((r) => {
    const evs = (r.events || []).map((e) => `<div class="ev ${e.type}">▸ ${esc([e.type, e.detail].join(' '))}</div>`).join('');
    return `<div class="log-entry">${esc(r.op)} ${esc(r.target)} → <b>${esc(r.value)}</b>${evs}</div>`;
  }).join('');
  $('#sim-log').innerHTML = fullLog;
}

// ---- diff tab -------------------------------------------------------------
$('#diff-run').addEventListener('click', async () => {
  const a = $('#diff-a').value, b = $('#diff-b').value;
  const r = await api(`/api/diff/${a}/${b}`);
  if (!r.entries.length) {
    $('#diff-view').innerHTML = '<div class="panel">两个修订的 ABI 与行为完全一致。</div>';
    return;
  }
  const rows = r.entries.map((e) => `
    <tr>
      <td>${e.kind === 'abi' ? 'ABI' : '行为'}</td>
      <td>${esc(e.severity)}</td>
      <td>${esc(e.message)}
        ${e.affectedApis && e.affectedApis.length ? `<div class="apis">受影响生成 API：${e.affectedApis.slice(0, 12).map(esc).join(', ')}${e.affectedApis.length > 12 ? ' …' : ''}</div>` : ''}
      </td>
    </tr>`).join('');
  $('#diff-view').innerHTML = `
    <h3>差异（${r.entries.length} 项）</h3>
    <table><thead><tr><th>类型</th><th>级别</th><th>说明 / 受影响 API</th></tr></thead><tbody>${rows}</tbody></table>
    <h3>新增 API（${r.addedApis.length}）</h3><div class="apis">${r.addedApis.map(esc).join(', ') || '—'}</div>
    <h3>删除 API（${r.removedApis.length}）</h3><div class="apis">${r.removedApis.map(esc).join(', ') || '—'}</div>
    <h3>全部受影响 API（${r.impactedApis.length}）</h3><div class="apis">${r.impactedApis.map(esc).join(', ') || '—'}</div>`;
});

// ---- generation tab -------------------------------------------------------
$('#gen-run').addEventListener('click', async () => {
  const id = $('#gen-revision').value;
  const dir = $('#gen-dir').value.trim();
  $('#gen-view').innerHTML = '<p class="status">生成中…</p>';
  const r = await post(`/api/models/${id}/generate`, dir ? { targetDir: dir } : {});
  if (r.installed) {
    $('#gen-view').innerHTML = `<div class="panel">已原子安装到 <span class="mono">${esc(r.targetDir)}</span><br>${r.installed.map((p) => '<div class="mono">' + esc(p) + '</div>').join('')}</div>`;
    return;
  }
  if (!r.files) { $('#gen-view').innerHTML = '<pre>' + esc(JSON.stringify(r)) + '</pre>'; return; }
  const tabs = r.files.map((f, i) => `<button data-i="${i}" class="gen-file-btn">${esc(f.path)}</button>`).join('');
  $('#gen-view').innerHTML = `<div class="status">模型 SHA-256 <span class="mono">${esc(r.hash)}</span>，每个文件头/清单都带此版本</div>
    <div class="file-tabs">${tabs}</div><pre id="gen-pre"></pre>`;
  const pre = $('#gen-pre');
  const show = (i) => { pre.textContent = r.files[i].content; };
  $$('.gen-file-btn').forEach((b) => b.addEventListener('click', () => show(Number(b.dataset.i))));
  show(0);
});

refreshRevisions();
