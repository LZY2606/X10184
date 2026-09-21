'use strict';

const $ = (id) => document.getElementById(id);
const state = { versions: [], models: new Map() };

function toast(msg, kind) {
  const div = document.createElement('div');
  div.className = 'toast-item ' + (kind || '');
  div.textContent = msg;
  $('toast').appendChild(div);
  setTimeout(() => div.remove(), 4200);
}

async function api(path, options) {
  const res = await fetch(path, options || {});
  if (!res.ok) {
    let text = await res.text();
    try { text = JSON.parse(text).message || text; } catch (e) { /* keep */ }
    throw new Error('HTTP ' + res.status + ': ' + text);
  }
  return res.json();
}

function switchTab(name) {
  document.querySelectorAll('nav button').forEach((b) =>
    b.classList.toggle('active', b.dataset.tab === name));
  ['versions', 'layout', 'conflicts', 'simulate', 'diff', 'codegen'].forEach((t) =>
    $('tab-' + t).hidden = t !== name);
  if (name === 'layout' || name === 'conflicts' || name === 'simulate'
      || name === 'diff' || name === 'codegen') {
    refreshVersionSelects();
  }
}

document.querySelectorAll('nav button').forEach((b) =>
  b.addEventListener('click', () => switchTab(b.dataset.tab)));

async function loadVersions() {
  state.versions = await api('/api/versions');
  const tbody = document.querySelector('#versionTable tbody');
  tbody.innerHTML = '';
  state.versions.forEach((v) => {
    const tr = document.createElement('tr');
    const counts = v.errorCount > 0
      ? `<span class="tag err">${v.errorCount} 错误</span> `
      : '<span class="tag ok">通过</span> ';
    tr.innerHTML = `<td>${v.id}</td><td>${escapeHtml(v.name)}</td>
      <td><code>${v.hash}</code></td><td>${counts}</td>
      <td>${v.warningCount} 警告 / ${v.infoCount} 提示</td>
      <td class="muted">${v.createdAt}</td>`;
    tbody.appendChild(tr);
  });
  fillSelects();
}

function fillSelects() {
  const opts = state.versions.map((v) =>
    `<option value="${v.id}">#${v.id} ${escapeHtml(v.name)} ${v.hash}</option>`).join('');
  ['layoutVersion', 'conflictVersion', 'simVersion', 'genVersion']
    .forEach((id) => { $(id).innerHTML = opts; });
  $('diffFrom').innerHTML = opts;
  $('diffTo').innerHTML = opts;
  if (state.versions.length > 1) {
    $('diffFrom').value = state.versions[state.versions.length - 2].id;
    $('diffTo').value = state.versions[state.versions.length - 1].id;
  }
}

async function refreshVersionSelects() {
  await loadVersions();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function hexMask(mask, lsb, width) {
  const digits = Math.max(1, Math.ceil(width / 4));
  return '0x' + (mask >>> 0).toString(16).padStart(Math.min(digits, 8), '0')
    + (width > 32 ? '…' : '');
}

async function getModel(id) {
  if (!state.models.has(id)) {
    state.models.set(id, await api('/api/versions/' + id + '/model'));
  }
  return state.models.get(id);
}

async function renderLayout() {
  const id = $('layoutVersion').value;
  const model = await getModel(id);
  const version = state.versions.find((v) => v.id == id);
  const host = $('layoutHost');
  host.innerHTML = '';
  model.addressSpaces.forEach((space) => {
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `<h3 style="margin-top:0">地址空间 ${escapeHtml(space.name)}
      <span class="muted">base ${space.base} · 端序 ${model.endianness} · 模型 ${version.hash}</span></h3>`;
    space.registers.forEach((reg) => card.appendChild(registerBlock(reg, version.hash)));
    host.appendChild(card);
  });
}

function registerBlock(reg, hash) {
  const wrap = document.createElement('div');
  const title = document.createElement('div');
  title.style.marginTop = '14px';
  title.innerHTML = `<strong>${escapeHtml(reg.name)}</strong>
    <span class="muted">@ ${reg.address} · ${reg.width} bit · reset ${reg.reset}
    ${reg.wordOrder && reg.width > 32 ? ' · ' + reg.wordOrder : ''}
    ${reg.aliasOf ? ' · aliasOf ' + reg.aliasOf : ''}
    ${reg.lockedBy ? ' · lockedBy ' + reg.lockedBy : ''}</span>`;
  wrap.appendChild(title);

  const byBit = new Array(reg.width).fill(null);
  reg.fields.forEach((f) => {
    for (let b = f.lsb; b <= f.msb; b++) { if (b < reg.width) byBit[b] = f; }
  });

  const grid = document.createElement('div');
  grid.className = 'reggrid';
  for (let b = reg.width - 1; b >= 0; b--) {
    const f = byBit[b];
    const cell = document.createElement('span');
    cell.className = 'bit ' + (f ? f.access : 'unclaimed');
    cell.textContent = b;
    cell.title = f ? `${reg.name}.${f.name} [${f.msb}:${f.lsb}] ${f.access}` : `bit ${b} 未声明`;
    cell.addEventListener('click', () => {
      wrap.querySelectorAll('.field-detail').forEach((n) => n.remove());
      const d = document.createElement('div');
      d.className = 'field-detail';
      if (f) {
        const mask = fieldMaskText(f);
        d.innerHTML = `<strong>${reg.name}.${escapeHtml(f.name)}</strong>
          [${f.msb}:${f.lsb}] · ${f.access} · 掩码 ${mask}<br>
          <span class="muted">${accessHelp(f.access)} · 可追溯模型版本 ${hash}
          ${f.description ? '<br>' + escapeHtml(f.description) : ''}</span>`;
      } else {
        d.innerHTML = `位 ${b} 未声明任何位域，写入按保留位处理（维持读取值） · 模型 ${hash}`;
      }
      wrap.appendChild(d);
    });
    grid.appendChild(cell);
  }
  wrap.appendChild(grid);

  const legend = document.createElement('div');
  legend.className = 'bitlabels';
  ['RW', 'RO', 'RC', 'W1C', 'W1S', 'RESERVED'].forEach((a) => {
    legend.innerHTML += `<span><span class="swatch bit ${a}"></span>${a}</span>`;
  });
  wrap.appendChild(legend);
  return wrap;
}

function fieldMaskText(f) {
  const width = f.msb - f.lsb + 1;
  let mask = 0n;
  for (let i = 0; i < width; i++) { mask |= 1n << BigInt(f.lsb + i); }
  const digits = Math.max(8, Math.ceil((f.msb + 1) / 4));
  return '0x' + mask.toString(16).padStart(digits, '0');
}

function accessHelp(access) {
  return ({
    RW: '普通读写', RO: '只读，写入忽略', RC: '读清零，peek 预览不消费',
    W1C: '写一清零', W1S: '写一置位', RESERVED: '保留位，写回维持读取值'
  })[access] || access;
}

['layoutVersion', 'conflictVersion', 'simVersion', 'genVersion', 'diffFrom', 'diffTo']
  .forEach((id) => $(id).addEventListener('change', () => {
    if (id === 'layoutVersion') { renderLayout().catch((e) => toast(e.message, 'err')); }
    if (id === 'conflictVersion') { renderConflicts(); }
  }));

async function renderConflicts() {
  const id = $('conflictVersion').value;
  const data = await api('/api/versions/' + id + '/conflicts');
  const renderList = (arr, el, empty) => {
    if (!arr.length) { $(el).innerHTML = '<p class="muted">' + empty + '</p>'; return; }
    $(el).innerHTML = '<table><thead><tr><th>代码</th><th>说明</th><th>路径</th></tr></thead><tbody>'
      + arr.map((d) => `<tr><td><code>${d.code}</code></td><td>${escapeHtml(d.message)}</td>
        <td class="muted">${(d.path || []).map(escapeHtml).join(' → ')}</td></tr>`).join('')
      + '</tbody></table>';
  };
  renderList(data.errors, 'errList', '无错误：模型可以生成与预演');
  renderList(data.warnings, 'warnList', '无警告');
  renderList(data.infos, 'infoList', '无提示');
}

async function runSimulation() {
  const id = $('simVersion').value;
  const steps = $('simScript').value.split('\n').map((line) => {
    const parts = line.trim().split(/\s+/).filter(Boolean);
    if (!parts.length || parts[0].startsWith('#')) { return null; }
    const type = parts[0].toLowerCase() === 'write' ? 'WRITE'
      : parts[0].toLowerCase() === 'peek' ? 'PEEK' : 'READ';
    if (type === 'WRITE' && parts.length < 3) {
      throw new Error('write 步骤需要寄存器和数值: ' + line);
    }
    if (type !== 'WRITE' && parts.length < 2) {
      throw new Error('read/peek 步骤需要寄存器: ' + line);
    }
    return type === 'WRITE'
      ? { type, register: parts[1], value: parts[2] }
      : { type, register: parts[1] };
  }).filter(Boolean);
  const data = await api('/api/versions/' + id + '/simulate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ steps })
  });
  const regs = Object.keys(data.steps.length ? data.steps[data.steps.length - 1].stateAfter : {});
  let html = `<h3 style="margin-top:0">逐步状态（模型 ${data.hash}）</h3>
    <table><thead><tr><th>#</th><th>操作</th><th>读出</th><th>事件</th>
    ${regs.map((r) => `<th>${r}</th>`).join('')}</tr></thead><tbody>`;
  data.steps.forEach((s) => {
    const opDesc = s.op.type === 'WRITE'
      ? `write ${s.op.register} ${s.op.value}` : `${s.op.type.toLowerCase()} ${s.op.register}`;
    const readText = s.readValue
      ? `${s.readValue}${s.readWords && s.readWords.length > 1
        ? '<br><span class="muted">字序: ' + s.readWords.join(', ') + '</span>' : ''}`
      : '';
    const accepted = s.accepted
      ? '' : ' <span class="tag err">被拒绝</span>';
    html += `<tr><td>${s.index}</td><td>${opDesc}${accepted}</td><td>${readText}</td>
      <td class="muted">${(s.log || []).map(escapeHtml).join('<br>')}</td>`;
    regs.forEach((r) => {
      const v = s.stateAfter[r];
      const prev = s.index > 0 ? data.steps[s.index - 1].stateAfter[r] : null;
      html += `<td class="${prev && prev !== v ? 'tag ok' : ''}">${v}</td>`;
    });
    html += '</tr>';
  });
  $('simResult').innerHTML = html + '</tbody></table>';
}

async function runDiff() {
  const data = await api(`/api/diff?from=${$('diffFrom').value}&to=${$('diffTo').value}`);
  let html = '';
  const simple = (title, arr, fmt) => `<div class="card"><h3 style="margin-top:0">${title}</h3>
    ${arr.length ? '<ul>' + arr.map(fmt).join('') + '</ul>' : '<p class="muted">无</p>'}</div>`;
  html += simple('新增寄存器', data.addedRegisters, (r) => `<li>${r}</li>`);
  html += simple('删除寄存器', data.removedRegisters, (r) => `<li>${r}</li>`);
  html += `<div class="card"><h3 style="margin-top:0">ABI 差异（受影响的生成 API）</h3>`;
  if (!data.abiChanges.length) {
    html += '<p class="muted">无地址、端序、宽度或布局差异</p>';
  } else {
    html += '<table><thead><tr><th>寄存器</th><th>类型</th><th>变化</th><th>受影响 API</th></tr></thead><tbody>';
    html += data.abiChanges.map((c) => `<tr><td>${c.register}</td><td><code>${c.kind}</code></td>
      <td>${escapeHtml(c.detail)}</td>
      <td>${c.affectedApis.map((a) => '<code>' + a + '</code>').join('<br>')}</td></tr>`).join('');
    html += '</tbody></table>';
  }
  html += '</div>';
  html += simple('行为差异（访问模式 / 复位值 / 锁存 / 副作用）', data.behaviorChanges,
    (b) => '<li>' + escapeHtml(b) + '</li>');
  $('diffResult').innerHTML = html;
}

async function previewCode() {
  const id = $('genVersion').value;
  const lang = $('genLang').value;
  const text = await fetch(`/api/versions/${id}/codegen?lang=${lang}`).then((r) => {
    if (!r.ok) { throw new Error('生成失败（HTTP ' + r.status + '）'); }
    return r.text();
  });
  $('genPreview').textContent = text;
}

$('reloadVersions').addEventListener('click',
  () => loadVersions().then(fillSelects).catch((e) => toast(e.message, 'err')));
$('btnImport').addEventListener('click', async () => {
  try {
    const data = await api('/api/versions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yaml: $('yamlInput').value })
    });
    toast(data.valid
      ? `已导入修订 #${data.id}（${data.hash}）`
      : `修订 #${data.id} 已保留，但存在冲突`, data.valid ? 'ok' : 'err');
    await loadVersions();
  } catch (e) { toast(e.message, 'err'); }
});
$('btnLoadDemo').addEventListener('click', async () => {
  const id = $('layoutVersion').value || state.versions.at(-1)?.id;
  if (!id) { toast('暂无版本', 'err'); return; }
  const v = await api('/api/versions/' + id);
  $('yamlInput').value = v.yaml;
});
$('btnSimRun').addEventListener('click',
  () => runSimulation().catch((e) => toast(e.message, 'err')));
$('btnSimReset').addEventListener('click', () => { $('simResult').innerHTML = ''; });
$('btnDiff').addEventListener('click', () => runDiff().catch((e) => toast(e.message, 'err')));
$('btnGenPreview').addEventListener('click',
  () => previewCode().catch((e) => toast(e.message, 'err')));
$('btnGenDownload').addEventListener('click', () => {
  const id = $('genVersion').value;
  const lang = $('genLang').value;
  window.location = `/api/versions/${id}/codegen?lang=${lang}&download=1`;
});

loadVersions().then(() => {
  $('layoutVersion').value = state.versions.at(-1)?.id;
  $('conflictVersion').value = state.versions.at(-1)?.id;
  $('simVersion').value = state.versions.at(-1)?.id;
  $('genVersion').value = state.versions.at(-1)?.id;
  renderLayout().catch((e) => toast(e.message, 'err'));
}).catch((e) => toast(e.message, 'err'));
