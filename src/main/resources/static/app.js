const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

// ---- tabs ----
$$('.tabs button').forEach((b) => b.addEventListener('click', () => {
  $$('.tabs button').forEach((x) => x.classList.remove('active'));
  $$('.tab').forEach((x) => x.classList.remove('active'));
  b.classList.add('active');
  $('#tab-' + b.dataset.tab).classList.add('active');
  if (b.dataset.tab === 'layout') renderLayout();
}));

const SAMPLE = `name: demo_soc
version: "1.2.0"
description: 演示用寄存器集合，覆盖跨字位域、别名、保留位、RC/W1C/W1S、锁与副作用
address_space:
  bits: 32
  word_bits: 32
  endian: little
registers:
  - name: CTRL
    address: 0x1000
    width: 32
    reset: 0x0
    set_alias: CTRLSET
    clr_alias: CTRLCLR
    fields:
      - name: ENABLE
        bits: 0
        access: rw
        reset: 0
        effects:
          - on_write:
              set: STATUS.RUNNING
      - name: SOFT_RESET
        bits: 1
        access: w1
        effects:
          - on_write:
              clear: STATUS.EVENT
      - name: MODE
        bits: "4:3"
        access: rw
      - name: RSVD
        bits: "31:8"
        access: reserved
  - name: CTRLSET
    address: 0x1004
    width: 32
    alias_of: CTRL
    description: 原子置位入口，写 1 的位在 CTRL 中置位
  - name: CTRLCLR
    address: 0x1008
    width: 32
    alias_of: CTRL
    description: 原子清零入口
  - name: STATUS
    address: 0x1010
    width: 32
    fields:
      - name: RUNNING
        bits: 0
        access: ro
      - name: ERRCNT
        bits: "7:4"
        access: rw
      - name: LATCHED_MODE
        bits: "11:8"
        access: rw
      - name: EVENT_FLAG
        bits: 16
        access: rc
        description: 读取即清零；预览读不会消费
      - name: RSVD
        bits: "31:17"
        access: reserved
  - name: HWCFG
    address: 0x1020
    width: 64
    read_order: low_first
    fields:
      - name: CROSS_FIELD
        bits: "40:24"
        access: rw
        reset: 0
        description: 跨两个 32 位字的位域
      - name: RSVD
        bits: "63:41"
        access: reserved
  - name: LOCK
    address: 0x1030
    width: 32
    fields:
      - name: LOCKEN
        bits: 0
        access: rw
  - name: CALIB
    address: 0x1040
    width: 32
    locked_by: LOCK.LOCKEN
    lock_level: "1"
    fields:
      - name: GAIN
        bits: "7:0"
        access: rw
      - name: SNAPSHOT
        bits: "15:8"
        access: rw
        effects:
          - on_write:
              latch: HWCFG.CROSS_FIELD
`;

$('#btn-sample').onclick = () => { $('#yaml').value = SAMPLE; };

function renderIssues(issues, target) {
  const el = target || $('#issues');
  if (!issues || issues.length === 0) {
    el.innerHTML = '<div class="issue" style="border-color:var(--ok)">✔ 未发现问题</div>';
    return;
  }
  el.innerHTML = issues.map((i) => `
    <div class="issue ${i.severity}">
      <span class="code">[${i.severity}] ${i.code}</span>${i.message}
      ${i.path && i.path.length ? `<div class="path">${i.path.join(' → ')}</div>` : ''}
    </div>`).join('');
}

$('#btn-validate').onclick = async () => {
  const r = await api('/api/validate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ yaml: $('#yaml').value })
  });
  renderIssues(r.issues);
};

$('#btn-import').onclick = async () => {
  const r = await api('/api/import', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ yaml: $('#yaml').value })
  });
  renderIssues(r.issues);
  alert(r.message + (r.revision ? '（修订 #' + r.revision + '）' : ''));
  await loadRevisions();
};

async function loadRevisions() {
  const models = await api('/api/models');
  const opts = models.map((m) => `<option value="${m.id}">#${m.id} ${m.name} ${m.version} (${m.hash.slice(0, 8)})</option>`).join('');
  ['#rev-select', '#layout-rev', '#sim-rev', '#diff-a', '#diff-b', '#gen-rev'].forEach((sel) => {
    const el = $(sel);
    const keep = el.value;
    el.innerHTML = opts;
    if (keep) el.value = keep;
  });
  if (models.length) {
    $('#diff-b').value = models[models.length - 1].id;
    if (models.length > 1) $('#diff-a').value = models[0].id;
  }
}

$('#rev-select').onchange = async (e) => {
  const m = await api('/api/models/' + e.target.value);
  $('#yaml').value = m.yaml;
};

// ---- clickable bit layout ----
async function renderLayout() {
  const rev = $('#layout-rev').value;
  if (!rev) return;
  const m = await api('/api/models/' + rev);
  const L = m.layout;
  const root = $('#layout');
  const colors = ['RW','RO','RC','W1C','W1S','W1','RESERVED'];
  $('#field-detail').textContent = JSON.stringify({ model: L.name, endian: L.endian, wordBits: L.wordBits }, null, 2);
  let html = '<div class="legend">' + colors.map((c) =>
    `<span><i class="acc-${c}"></i>${c}</span>`).join('') + '</div>';
  for (const reg of L.registers) {
    html += `<div class="register"><h3>${reg.name} @ ${reg.address}</h3>
      <div class="meta">width=${reg.width} reset=${reg.reset} order=${reg.readOrder}
        ${reg.aliasOf ? ' alias→' + reg.aliasOf : ''} ${reg.lockedBy ? ' lock=' + reg.lockedBy : ''}
        ${reg.setAlias ? ' set=' + reg.setAlias : ''} ${reg.clrAlias ? ' clr=' + reg.clrAlias : ''}</div><div class="bits">`;
    for (let bit = reg.width - 1; bit >= 0; bit--) {
      const f = (reg.fields || []).find((x) => bit >= x.lsb && bit <= x.msb);
      if (f) {
        const label = bit === f.msb ? f.name : '';
        const gap = (L.wordBits > 0 && bit % L.wordBits === 0) ? ' wordgap' : '';
        html += `<div class="cell acc-${f.access}${gap}" data-reg='${reg.name}' data-field='${f.name}'>${label}</div>`;
      } else {
        const gap = (L.wordBits > 0 && bit % L.wordBits === 0) ? ' wordgap' : '';
        html += `<div class="cell${gap}" style="background:#2a3448"></div>`;
      }
    }
    html += '</div><div class="ruler">';
    for (let bit = reg.width - 1; bit >= 0; bit--) {
      if (bit === reg.width - 1 || bit === 0 || bit % L.wordBits === 0) {
        html += `<span style="flex:${bit === reg.width - 1 ? 1 : 1}">${bit}</span>`;
      }
    }
    html += '</div></div>';
  }
  root.innerHTML = html;
  root.querySelectorAll('.cell[data-field]').forEach((c) => c.addEventListener('click', () => {
    const reg = L.registers.find((x) => x.name === c.dataset.reg);
    const f = reg.fields.find((x) => x.name === c.dataset.field);
    $('#field-detail').textContent = JSON.stringify({ register: reg.name, ...f }, null, 2);
  }));
}
$('#layout-rev').onchange = renderLayout;

// ---- simulator ----
let simSession = null;
$('#sim-start').onclick = async () => {
  const rev = $('#sim-rev').value;
  const r = await api(`/api/sim/${rev}/start`, { method: 'POST' });
  simSession = r.session;
  const m = await api('/api/models/' + rev);
  $('#sim-target').innerHTML = m.layout.registers.map((x) => `<option>${x.name}</option>`).join('');
  $('#sim-state').textContent = formatState(r.state);
  $('#sim-events').innerHTML = '<div class="event">会话 ' + simSession + ' 已开始（已复位到 reset 值）</div>';
};
$('#sim-reset').onclick = async () => {
  if (!simSession) return;
  const r = await api(`/api/sim/${simSession}/reset`, { method: 'POST' });
  $('#sim-state').textContent = formatState(r.state);
};
$('#sim-step').onclick = async () => {
  if (!simSession) { alert('请先开始会话'); return; }
  const r = await api(`/api/sim/${simSession}/step`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      type: $('#sim-type').value,
      target: $('#sim-target').value,
      value: $('#sim-value').value
    })
  });
  const box = $('#sim-events');
  let line = `<div class="event">#${r.step} ${r.type} ${r.target}`;
  if (r.readWords && r.readWords.length) line += ` ⇒ [${r.readWords.join(', ')}]`;
  line += '</div>';
  line += r.events.map((e) => `<div class="event ${e.kind}">· [${e.kind}] ${e.detail}</div>`).join('');
  box.insertAdjacentHTML('beforeend', line);
  box.scrollTop = box.scrollHeight;
  $('#sim-state').textContent = formatState(r.state);
};
function formatState(state) {
  return Object.entries(state).map(([k, v]) =>
    `${k.padEnd(12)} = 0x${(BigInt(v) < 0 ? BigInt(v) >>> 0n : BigInt(v)).toString(16).toUpperCase()}`).join('\n');
}

// ---- diff ----
$('#diff-run').onclick = async () => {
  const r = await api(`/api/diff/${$('#diff-a').value}/${$('#diff-b').value}`);
  if (!r.diff.length) {
    $('#diff-out').innerHTML = '<div class="issue" style="border-color:var(--ok)">两个修订的 ABI 与行为完全一致</div>';
    return;
  }
  $('#diff-out').innerHTML = r.diff.map((d) => `
    <div class="diff-item ${d.change}">
      <span class="tag">[${d.kind}/${d.change}]</span><code>${d.symbol}</code><br>
      ${d.detail}
      ${d.impactedApis && d.impactedApis.length ? `<div class="hint">受影响 API: ${[...new Set(d.impactedApis)].map((a) => `<code>${a}</code>`).join(' ')}</div>` : ''}
    </div>`).join('');
};

// ---- generated files ----
$('#gen-rev').onchange = loadGenerated;
async function loadGenerated() {
  const rev = $('#gen-rev').value;
  if (!rev) return;
  const files = await api('/api/generate/' + rev);
  $('#gen-files').innerHTML = files.map((f, i) =>
    `<li data-i="${i}">${f.path}</li>`).join('');
  $$('#gen-files li').forEach((li) => li.addEventListener('click', () => {
    $$('#gen-files li').forEach((x) => x.classList.remove('active'));
    li.classList.add('active');
    $('#gen-content').textContent = files[+li.dataset.i].content;
  }));
  if (files.length) $('#gen-files li').click();
}

// boot
(async function init() {
  $('#yaml').value = SAMPLE;
  await loadRevisions();
})();
