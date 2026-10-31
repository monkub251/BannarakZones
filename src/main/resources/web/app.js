/* BANNARAKX staff console */
(() => {
  'use strict';

  const $  = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  const gate     = $('#gate');
  const app      = $('#app');
  const gateForm = $('#gate-form');
  const codeBox  = $('#code');
  const gateErr  = $('#gate-error');
  const gateGo   = $('#gate-go');

  let timer = null;
  let search = '';
  let sheetFor = null;
  let lastPlayers = [];

  // ---------- helpers ----------

  const num = n => (n == null ? '—' : Number(n).toLocaleString('th-TH'));
  const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function toast(text, bad) {
    const el = document.createElement('div');
    el.className = 'toast' + (bad ? ' bad' : '');
    el.textContent = text;
    $('#toasts').append(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 320); }, 3400);
  }

  async function api(path, opts) {
    const res = await fetch(path, Object.assign({ credentials: 'same-origin' }, opts));
    if (res.status === 401) { signedOut(); throw new Error('session'); }
    const text = await res.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch { /* leave empty */ }
    if (!res.ok) throw new Error(data.error || 'เรียกใช้ไม่สำเร็จ');
    return data;
  }

  function signedOut() {
    if (timer) { clearInterval(timer); timer = null; }
    app.hidden = true;
    gate.hidden = false;
    gateErr.textContent = 'เซสชันหมดอายุ ขอรหัสใหม่ในเกม';
    codeBox.value = '';
  }

  function pulse(ok) {
    $('#pulse-dot').className = 'dot ' + (ok ? 'ok' : 'bad');
    $('#pulse-text').textContent = ok ? 'เชื่อมต่ออยู่' : 'เชื่อมต่อไม่ได้';
  }

  const face = name => `<span class="avatar" style="display:grid;place-items:center;
    font-family:var(--num);font-size:12px;color:var(--gold-hi)">${esc(name[0] || '?')}</span>`;

  // ---------- ล็อกอิน ----------

  codeBox.addEventListener('input', () => {
    codeBox.value = codeBox.value.replace(/\D/g, '').slice(0, 6);
    gateErr.textContent = '';
    if (codeBox.value.length === 6) gateForm.requestSubmit();
  });

  gateForm.addEventListener('submit', async e => {
    e.preventDefault();
    const code = codeBox.value.trim();
    if (code.length !== 6) { gateErr.textContent = 'ใส่รหัส 6 หลัก'; return; }
    gateGo.disabled = true;
    try {
      const out = await api('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code })
      });
      $('#who-name').textContent = out.name || '—';
      gate.hidden = true;
      app.hidden = false;
      start();
    } catch (err) {
      gateErr.textContent = err.message;
      codeBox.value = '';
      codeBox.focus();
    } finally {
      gateGo.disabled = false;
    }
  });

  // ---------- แท็บ ----------

  $$('.nav-item').forEach(b => b.addEventListener('click', () => {
    $$('.nav-item').forEach(x => x.classList.toggle('is-on', x === b));
    $$('.tab').forEach(t => t.classList.toggle('is-on', t.dataset.pane === b.dataset.tab));
    refresh();
  }));

  $('#player-search').addEventListener('input', e => {
    search = e.target.value.trim().toLowerCase();
    drawPlayers(lastPlayers);
  });

  // ---------- การสั่งงาน ----------

  async function act(action, target, value, confirmText) {
    if (confirmText && !confirm(confirmText)) return;
    try {
      const out = await api('/api/action', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, target: target || null, value: value || null })
      });
      toast(out.message || 'เรียบร้อย');
      refresh();
    } catch (err) {
      if (err.message !== 'session') toast(err.message, true);
    }
  }

  document.addEventListener('click', e => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const target = b.dataset.target ? ($(b.dataset.target)?.value || '').trim()
                 : b.dataset.player || '';
    const value  = b.dataset.from ? ($(b.dataset.from)?.value || '').trim()
                 : b.dataset.value || '';
    if (b.dataset.target && !target) { toast('ใส่ชื่อผู้เล่นก่อน', true); return; }
    act(b.dataset.act, target, value, b.dataset.confirm);
    if (b.dataset.from) { const f = $(b.dataset.from); if (f && f.tagName === 'TEXTAREA') f.value = ''; }
  });

  // ---------- แผ่นผู้เล่นรายคน ----------

  const SHEET_BUTTONS = [
    ['heal',         'รักษา',                  '',       null],
    ['freeze',       'แช่แข็ง / ปลด',          '',       null],
    ['gamemode',     'โหมดสร้างสรรค์',        'creative', null],
    ['gamemode',     'โหมดเอาชีวิตรอด',       'survival', null],
    ['money',        'ปรับเงิน (ใช้ค่าด้านบน)', 'FROM',   null],
    ['resetDungeon', 'ล้างคูลดาวน์ดันเจียน',   '',       null],
    ['warn',         'เตือน',                  'FROM',   null],
    ['mute',         'ปิดปาก',                 'FROM',   null],
    ['unmute',       'ปลดปิดปาก',              '',       null],
    ['kick',         'เตะ',                    'FROM',   'เตะคนนี้ออกจากเซิร์ฟ?'],
    ['ban',          'แบน',                    'FROM',   'แบนคนนี้?']
  ];

  function openSheet(p) {
    sheetFor = p.name;
    $('#sheet-name').textContent = p.name;
    $('#sheet-meta').innerHTML =
      `ยศ ${esc(p.rank)} · เงิน ${num(p.money)} · ปิง ${p.ping} ms<br>` +
      `${esc(p.world)} ที่ ${p.x}, ${p.y}, ${p.z} · โหมด ${esc(p.gamemode)}` +
      (p.muted ? '<br><span class="pill bad">ถูกปิดปาก</span>' : '') +
      (p.frozen ? ' <span class="pill warn">ถูกแช่แข็ง</span>' : '');
    $('#sheet-value').value = '';
    const row = $('#sheet-actions');
    row.innerHTML = '';
    for (const [action, label, value, confirmText] of SHEET_BUTTONS) {
      const b = document.createElement('button');
      b.className = 'btn sm' + (action === 'ban' ? ' danger' : action === 'kick' ? ' warn' : '');
      b.textContent = label;
      b.addEventListener('click', () => {
        const v = value === 'FROM' ? $('#sheet-value').value.trim() : value;
        act(action, sheetFor, v, confirmText);
      });
      row.append(b);
    }
    $('#sheet').hidden = false;
  }

  $('#sheet').addEventListener('click', e => {
    if (e.target.closest('[data-close]')) $('#sheet').hidden = true;
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') $('#sheet').hidden = true;
  });

  // ---------- การวาด ----------

  function drawStatus(s) {
    const tps = Number(s.tps || 0);
    $('#s-tps').textContent = tps.toFixed(2);
    $('#s-tps').style.color = tps >= 19 ? 'var(--good)' : tps >= 16 ? 'var(--warn)' : 'var(--bad)';
    $('#s-ram').textContent = `${num(s.ramUsed)} / ${num(s.ramMax)}`;
    $('#s-ram-bar').style.width = (s.ramMax ? (s.ramUsed / s.ramMax * 100) : 0) + '%';
    $('#s-players').textContent = `${s.players} / ${s.maxPlayers}`;
    $('#s-entities').textContent = num(s.entities);
    $('#s-chunks').textContent = num(s.chunks);
    $('#s-version').textContent = s.version || '—';

    $('#d-tps').textContent = tps.toFixed(2);
    $('#d-tps-note').textContent = tps >= 19 ? 'ลื่นดี' : tps >= 16 ? 'เริ่มหนัก' : 'หนักมาก';
    $('#d-ram').textContent = num(s.ramUsed) + ' MB';
    $('#d-ram-note').textContent = 'จาก ' + num(s.ramMax) + ' MB';
    $('#d-zones').textContent = num(s.zones);
    $('#d-dungeons').textContent = num(s.dungeons);
    $('#d-listings').textContent = num(s.listings);
    $('#d-money').textContent = num(s.totalMoney);
  }

  function drawPlayers(list) {
    lastPlayers = list;
    $('#badge-players').textContent = list.length;

    const chips = $('#d-players');
    chips.innerHTML = list.length
      ? list.map(p => `<button class="chip" data-chip="${esc(p.name)}">${face(p.name)}
          ${esc(p.name)} <small>${num(p.money)}</small></button>`).join('')
      : '<p class="empty">ยังไม่มีใครออนไลน์</p>';
    $$('[data-chip]', chips).forEach(b => b.addEventListener('click',
      () => openSheet(list.find(p => p.name === b.dataset.chip))));

    const shown = search ? list.filter(p => p.name.toLowerCase().includes(search)) : list;
    const body = $('#player-rows');
    if (!shown.length) {
      body.innerHTML = `<tr><td colspan="10" class="empty">${
        search ? 'ไม่พบชื่อนี้' : 'ยังไม่มีใครออนไลน์'}</td></tr>`;
      return;
    }
    body.innerHTML = shown.map(p => `<tr>
      <td><span class="who-cell">${face(p.name)}<b>${esc(p.name)}</b>${
        p.op ? ' <span class="pill gold">ทีมงาน</span>' : ''}</span></td>
      <td>${esc(p.rank)}</td>
      <td><span class="hp"><i><b style="width:${p.health / 20 * 100}%"></b></i>${p.health}</span></td>
      <td>${esc(p.world)}</td>
      <td class="num">${p.x}, ${p.y}, ${p.z}</td>
      <td>${esc(p.gamemode)}</td>
      <td class="num">${num(p.money)}</td>
      <td class="num">${p.ping}</td>
      <td>${p.muted ? '<span class="pill bad">ปิดปาก</span> ' : ''}${
        p.frozen ? '<span class="pill warn">แช่แข็ง</span>' : ''}${
        !p.muted && !p.frozen ? '<span class="pill ok">ปกติ</span>' : ''}</td>
      <td><button class="btn sm" data-open="${esc(p.name)}">จัดการ</button></td>
    </tr>`).join('');
    $$('[data-open]', body).forEach(b => b.addEventListener('click',
      () => openSheet(list.find(p => p.name === b.dataset.open))));
  }

  function drawWorlds(list) {
    $('#world-rows').innerHTML = list.length ? list.map(w => `<tr>
      <td><b>${esc(w.name)}</b></td>
      <td class="num">${w.players}</td>
      <td class="num">${num(w.entities)}</td>
      <td class="num">${num(w.chunks)}</td>
      <td class="num">${String(Math.floor((w.time / 1000 + 6) % 24)).padStart(2, '0')}:${
        String(Math.floor(w.time % 1000 / 1000 * 60)).padStart(2, '0')}</td>
      <td>${w.storm ? '<span class="pill warn">ฝนตก</span>' : '<span class="pill ok">ฟ้าใส</span>'}</td>
    </tr>`).join('') : '<tr><td colspan="6" class="empty">ไม่มีโลก</td></tr>';
  }

  function drawEconomy(e) {
    $('#e-total').textContent = num(e.total);
    $('#e-accounts').textContent = num(e.accounts);
    $('#e-avg').textContent = e.accounts ? num(Math.round(e.total / e.accounts)) : '—';
    const top = e.top || [];
    $('#e-top').innerHTML = top.length
      ? top.map(t => `<li>${face(t.name)} ${esc(t.name)} <b>${num(t.money)}</b></li>`).join('')
      : '<li class="empty">ยังไม่มีข้อมูล</li>';
  }

  function drawBans(list) {
    $('#badge-bans').textContent = list.length;
    $('#ban-rows').innerHTML = list.length ? list.map(b => `<tr>
      <td><span class="who-cell">${face(b.name)}<b>${esc(b.name)}</b></span></td>
      <td><span class="pill ${b.type === 'BAN' ? 'bad' : 'warn'}">${
        b.type === 'BAN' ? 'แบน' : 'ปิดปาก'}</span></td>
      <td>${esc(b.reason)}</td>
      <td>${esc(b.staff)}</td>
      <td>${esc(b.left)}</td>
      <td><button class="btn sm" data-act="${b.type === 'BAN' ? 'unban' : 'unmute'}"
        data-player="${esc(b.name)}">ปลด</button></td>
    </tr>`).join('') : '<tr><td colspan="6" class="empty">ไม่มีใครถูกลงโทษอยู่</td></tr>';
  }

  // ---------- วงรอบดึงข้อมูล ----------

  const tabNow = () => $('.nav-item.is-on')?.dataset.tab || 'dash';

  async function refresh() {
    try {
      const jobs = [api('/api/status').then(drawStatus)];
      const tab = tabNow();
      if (tab === 'dash' || tab === 'players') jobs.push(api('/api/players').then(drawPlayers));
      if (tab === 'worlds')  jobs.push(api('/api/worlds').then(drawWorlds));
      if (tab === 'economy') jobs.push(api('/api/economy').then(drawEconomy));
      jobs.push(api('/api/bans').then(drawBans));
      await Promise.all(jobs);
      pulse(true);
    } catch (err) {
      if (err.message !== 'session') pulse(false);
    }
  }

  function start() {
    refresh();
    timer = setInterval(() => { if (!document.hidden) refresh(); }, 4000);
  }

  codeBox.focus();
})();
