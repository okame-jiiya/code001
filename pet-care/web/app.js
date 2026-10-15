// うちの子ごはん帳 Web 版（家族共有用）。「きょう」と「カレンダー」で記録・閲覧ができる。
import * as backend from './backend.js';
import {
  COLLECTIONS, EXCRETION, MOODS, HEALTH, petIcon, petColor, newId, docId, dateKey, parseDate, timeText, daysBetween, targetKcal, kcalPerUnit, plan, formatAmount, formatKcal, cycleText, isAbnormal, excretionDetail,
  TREAT_LIMIT_RATIO,
} from './model.js';

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch { /* 保存できなくても動く */ } },
};

const S = {
  phase: 'loading', // loading | not-configured | no-family | invite | ready
  familyId: store.get('familyId'),
  inviteId: null,
  data: Object.fromEntries(COLLECTIONS.map((c) => [c, new Map()])),
  loaded: new Set(),
  error: null,
  petId: Number(store.get('petId')) || null,
  tab: store.get('tab') || 'today',
  month: (() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); })(),
  selected: dateKey(new Date()),
  slot: 'NOW',
  calMode: 'FOOD',
};
let unsubscribe = null;

const list = (c) => [...S.data[c].values()];
const pets = () => list('pets').sort((a, b) => a.id - b.id);
const currentPet = () => pets().find((p) => p.id === S.petId) || pets()[0];
const foodById = (id) => S.data.foods.get(String(id));
const sameDay = (ms, key) => dateKey(new Date(ms)) === key;

// ---------------- 書き込み ----------------

function put(c, item) {
  S.data[c].set(docId(c, item), item); // すぐ画面に出す（クラウドからの通知でも同じ内容が届く）
  render();
  backend.put(S.familyId, c, docId(c, item), item).catch(() => toast('送信できませんでした。通信状態を確かめてください'));
}

function remove(c, item) {
  S.data[c].delete(docId(c, item));
  render();
  backend.remove(S.familyId, c, docId(c, item)).catch(() => toast('削除できませんでした'));
}

function recorded(message, c, item) {
  toast(message, '取り消す', () => remove(c, item));
}

function addFeed(pet, food, amount, time) {
  const log = {
    id: newId(), petId: pet.id, foodId: food.id, foodName: food.name, kind: food.kind,
    amount, unitLabel: food.unitLabel, kcal: amount * kcalPerUnit(food), time,
  };
  put('feedLogs', log);
  recorded(`${food.name}を記録しました`, 'feedLogs', log);
}

function addToilet(pet, type, time) {
  const log = { id: newId(), petId: pet.id, type, time, condition: null, flags: [], note: '' };
  put('excretionLogs', log);
  recorded(`${EXCRETION[type].label}を記録しました`, 'excretionLogs', log);
}

function setMood(pet, date, mood) {
  const cur = S.data.moods.get(`${pet.id}_${date}`);
  if (cur && cur.mood === mood) remove('moods', cur);
  else put('moods', { petId: pet.id, date, mood });
}

// ---------------- 起動 ----------------

async function start() {
  const m = location.hash.match(/join=([A-Za-z0-9]{12,40})/);
  if (m) history.replaceState(null, '', location.pathname + location.search);
  try {
    await backend.init();
  } catch {
    S.phase = 'not-configured';
    return render();
  }
  if (m && m[1] !== S.familyId) {
    S.inviteId = m[1];
    S.phase = 'invite';
    return render();
  }
  if (!S.familyId) {
    S.phase = 'no-family';
    return render();
  }
  connect();
}

async function connect() {
  S.phase = 'ready';
  S.loaded.clear();
  render();
  try {
    unsubscribe?.();
    unsubscribe = await backend.subscribe(S.familyId, (c, items) => {
      S.data[c] = items;
      S.loaded.add(c);
      render();
    }, (kind) => {
      S.error = kind === 'denied'
        ? '家族の記録を読めませんでした。招待リンクから参加し直してください。'
        : '通信できませんでした。電波のよいところで開き直してください。';
      render();
    });
  } catch {
    S.error = '通信できませんでした。電波のよいところで開き直してください。';
    render();
  }
}

async function joinFamily(fid) {
  const btn = $('#join-btn');
  if (btn) { btn.disabled = true; btn.textContent = '参加しています…'; }
  try {
    await backend.join(fid);
    S.familyId = fid;
    store.set('familyId', fid);
    S.inviteId = null;
    connect();
  } catch (e) {
    if (btn) { btn.disabled = false; btn.textContent = '参加する'; }
    toast(e?.code === 'permission-denied' || e?.code === 'not-found'
      ? '招待リンクが見つかりませんでした。リンクを確かめてください'
      : '参加できませんでした。通信状態を確かめてください');
  }
}

function leave() {
  if (!confirm('この端末で家族の記録を見るのをやめますか？（家族の記録は消えません）')) return;
  unsubscribe?.();
  S.familyId = null;
  store.set('familyId', null);
  S.data = Object.fromEntries(COLLECTIONS.map((c) => [c, new Map()]));
  S.phase = 'no-family';
  render();
}

// ---------------- 画面 ----------------

function render() {
  const top = $('#top');
  const main = $('#main');
  const tabs = $('#tabs');
  tabs.hidden = true;
  top.innerHTML = '<div class="brand">うちの子ごはん帳</div>';

  if (S.phase === 'loading') { main.innerHTML = '<p class="hint center">読み込み中…</p>'; return; }
  if (S.phase === 'not-configured') {
    main.innerHTML = card('', '<p>家族共有はまだ準備中です。設定が終わると、ここで記録を見られるようになります。</p>');
    return;
  }
  if (S.phase === 'invite') { main.innerHTML = inviteView(); return; }
  if (S.phase === 'no-family') { main.innerHTML = noFamilyView(); return; }

  const pet = currentPet();
  if (S.error) {
    main.innerHTML = card('', `<p class="error">${esc(S.error)}</p><button class="btn ghost" data-act="leave">この端末での共有をやめる</button>`);
    return;
  }
  if (!pet) {
    const waiting = S.loaded.size < COLLECTIONS.length;
    main.innerHTML = card('', waiting ? '<p class="hint">家族の記録を読み込んでいます…</p>'
      : '<p>まだペットが登録されていません。アプリでうちの子を登録すると、ここにも表示されます。</p>');
    return;
  }
  top.innerHTML += `<div class="chips">${pets().map((p) => `
    <button class="chip ${p.id === pet.id ? 'on' : ''}" data-act="pet" data-id="${p.id}">
      <span class="avatar" style="background:${petColor(p)}">${petIcon(p)}</span>${esc(p.name)}
    </button>`).join('')}</div>`;
  tabs.hidden = false;
  tabs.innerHTML = [['today', '🏠', 'きょう'], ['calendar', '📅', 'カレンダー']].map(([k, e, l]) =>
    `<button class="${S.tab === k ? 'on' : ''}" data-act="tab" data-tab="${k}"><span>${e}</span>${l}</button>`).join('');
  main.innerHTML = S.tab === 'calendar' ? calendarView(pet) : todayView(pet);
}

const card = (title, body, cls = '') => `<section class="card ${cls}">${title ? `<h2>${title}</h2>` : ''}${body}</section>`;

function inviteView() {
  const android = /Android/i.test(navigator.userAgent);
  return card('👨‍👩‍👧 家族の記録に参加', `
      <p>家族から招待されています。参加すると、家族と同じ「うちの子ごはん帳」の記録を見たり書いたりできます。</p>
      <button id="join-btn" class="btn" data-act="join" data-id="${esc(S.inviteId)}">参加する</button>
      ${android ? `<p class="hint">Android でアプリを入れている場合は、アプリで開くこともできます。</p>
        <a class="btn ghost" href="petcare://join?f=${esc(S.inviteId)}">アプリで開く</a>` : ''}
      <p class="hint">iPhone の方は、参加したあと Safari の共有ボタン →「ホーム画面に追加」でアプリのように使えます。</p>`);
}

function noFamilyView() {
  return card('👨‍👩‍👧 家族と共有', `
      <p>家族のアプリに表示される QR コードを読み取るか、届いた招待リンクを開いてください。</p>
      <label class="field">招待リンクを貼り付けて参加
        <input id="invite-input" placeholder="https://…#join=…" autocomplete="off"></label>
      <button class="btn" data-act="paste-join">参加する</button>`);
}

function planLines(pet) {
  const items = list('plans').filter((i) => i.petId === pet.id).sort((a, b) => a.id - b.id)
    .map((item) => ({ item, food: foodById(item.foodId) })).filter((x) => x.food);
  return plan(targetKcal(pet), items);
}

function moodPicker(pet, date) {
  const cur = S.data.moods.get(`${pet.id}_${date}`)?.mood;
  return `<div class="row wrap"><span class="label">きげん</span>${Object.entries(MOODS).map(([k, m]) =>
    `<button class="chip ${cur === k ? 'on' : ''}" data-act="mood" data-date="${date}" data-mood="${k}">${m.emoji} ${m.label}</button>`).join('')}</div>`;
}

function healthAlerts(pet) {
  const now = Date.now();
  const alerts = [];
  const logs = list('excretionLogs').filter((l) => l.petId === pet.id);
  const pees = logs.filter((l) => l.type === 'PEE');
  if (pees.length) {
    const last = Math.max(...pees.map((l) => l.time));
    const hours = Math.floor((now - last) / 3600000);
    const recent = pees.filter((l) => now - l.time < 3 * 86400000).length >= 2;
    if (hours >= 24 && recent) {
      alerts.push(`最後のおしっこから${hours}時間たっています。` + (pet.species === 'CAT'
        ? '猫は24時間以上出ないと危険なことがあるので、早めに動物病院へ相談してください。'
        : '記録漏れでなければ、動物病院へ相談してください。'));
    }
  }
  for (const type of ['POOP', 'PEE']) {
    const bad = logs.filter((l) => l.type === type && l.time >= now - 86400000 && isAbnormal(l));
    if (bad.length) {
      const what = [...new Set(bad.flatMap((l) => [...(l.flags || []), ...(l.condition === '下痢' ? ['下痢'] : [])]))];
      alerts.push(`24時間以内の${EXCRETION[type].label}に「${what.join('・')}」の記録があります。続くようなら受診を検討してください。`);
    }
  }
  return alerts;
}

function healthText(r, today) {
  const days = daysBetween(today, parseDate(r.date));
  const at = r.time ? ` ${r.time.slice(0, 5).replace(/^0/, '')}` : '';
  const when = days === 0 ? `今日${at}` : days === 1 ? `明日${at}` : `あと${days}日（${parseDate(r.date).getMonth() + 1}/${parseDate(r.date).getDate()}${at}）`;
  return `<div class="row"><span class="dot" style="background:${HEALTH[r.kind]?.color}"></span>${HEALTH[r.kind]?.emoji || ''} ${esc(r.title)}：${when}</div>`;
}

function todayView(pet) {
  const today = new Date();
  const key = dateKey(today);
  const feeds = list('feedLogs').filter((l) => l.petId === pet.id && sameDay(l.time, key));
  const toilets = list('excretionLogs').filter((l) => l.petId === pet.id && sameDay(l.time, key));
  const target = targetKcal(pet);
  const eaten = feeds.reduce((s, l) => s + l.kcal, 0);
  const treats = feeds.filter((l) => l.kind === 'TREAT').reduce((s, l) => s + l.kcal, 0);
  const ratio = target > 0 ? eaten / target : 0;
  const diff = target - eaten;
  let html = '';

  const alerts = healthAlerts(pet);
  const upcoming = list('health').filter((r) => r.petId === pet.id && daysBetween(today, parseDate(r.date)) >= 0 && daysBetween(today, parseDate(r.date)) <= 14)
    .sort((a, b) => (a.date + (a.time || '09:00')).localeCompare(b.date + (b.time || '09:00')));
  if (alerts.length || upcoming.length) {
    html += card('', alerts.map((a) => `<p class="warn">⚠ ${esc(a)}</p>`).join('') + upcoming.map((r) => healthText(r, today)).join(''), alerts.length ? 'peach' : 'lavender');
  }

  html += card('', `
    <div class="label">${today.getMonth() + 1}/${today.getDate()} の ${esc(pet.name)}</div>
    <div class="kcal"><b>${Math.round(eaten)}</b> / ${formatKcal(target)}</div>
    <div class="bar"><i style="width:${Math.min(100, ratio * 100)}%;${ratio > 1.1 ? 'background:var(--peach)' : ''}"></i></div>
    <p>${diff > 0.5 ? `あと ${formatKcal(diff)} あげられます` : diff < -target * 0.1 ? `目安より ${formatKcal(-diff)} 多めです` : '今日の目安に届きました 🎉'}</p>
    ${treats > 0 ? `<p class="small ${treats > target * TREAT_LIMIT_RATIO ? 'error' : ''}">おやつ ${formatKcal(treats)}（目安の10% = ${formatKcal(target * TREAT_LIMIT_RATIO)} まで）${treats > target * TREAT_LIMIT_RATIO ? ' ⚠ あげすぎ' : ''}</p>` : ''}`, 'pink');

  const lines = planLines(pet);
  html += card('🍚 ごはんをあげた', (lines.length ? lines.map((ln) => {
    const given = feeds.filter((l) => l.foodId === ln.food.id).length;
    return `<div class="row">
      <div class="grow"><b>${esc(ln.food.name)}</b>
        <div class="small muted">1回 ${formatAmount(ln.amount)} ${esc(ln.food.unitLabel)}・${cycleText(ln.item.timesPerCycle, ln.item.cycleDays)}</div>
        <div class="small accent">今日 ${given}${ln.item.cycleDays === 1 ? `／${ln.item.timesPerCycle}` : ''} 回</div></div>
      <button class="btn" data-act="feed" data-plan="${ln.item.id}" ${ln.amount > 0 ? '' : 'disabled'}>あげた</button></div>`;
  }).join('') : '<p class="hint">ごはんプランはアプリの「ごはん」タブで作れます。</p>') +
    (list('foods').length ? `<button class="link" data-act="feed-custom" data-date="${key}">＋ 量を変えて・プラン外のごはんやおやつを記録</button>` : ''));

  html += card('🚽 トイレ・きげん', moodPicker(pet, key) + `
    <div class="row gap">${Object.entries(EXCRETION).map(([k, e]) =>
      `<button class="btn big ${k.toLowerCase()}" data-act="toilet" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
    ${Object.entries(EXCRETION).map(([k, e]) => {
      const l = toilets.filter((t) => t.type === k);
      const last = l.length ? Math.max(...l.map((t) => t.time)) : null;
      return `<div class="small muted">${e.emoji} 今日 ${l.length} 回${last ? `（最後 ${timeText(last)}）` : ''}</div>`;
    }).join('')}`);

  html += card('📝 今日の記録', recordList(feeds, toilets) || '<p class="hint">まだ記録がありません。トイレの記録をタップすると状態（かたさ・血が混じる など）を残せます。</p>');
  html += `<p class="hint center">家族と共有中 ・ <button class="link" data-act="leave">この端末での共有をやめる</button></p>`;
  return html;
}

function recordList(feeds, toilets) {
  const entries = [...feeds.map((l) => ['feed', l]), ...toilets.map((l) => ['toilet', l])].sort((a, b) => b[1].time - a[1].time);
  return entries.map(([kind, l]) => kind === 'feed'
    ? `<div class="row rec"><span class="time">${timeText(l.time)}</span>
        <button class="grow plain" data-act="edit-feed" data-id="${l.id}">${l.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(l.foodName)}
          <div class="small muted">${formatAmount(l.amount)} ${esc(l.unitLabel)}・${formatKcal(l.kcal)}</div></button>
        <button class="link muted" data-act="del" data-c="feedLogs" data-id="${l.id}">削除</button></div>`
    : `<div class="row rec"><span class="time">${timeText(l.time)}</span>
        <button class="grow plain" data-act="edit-toilet" data-id="${l.id}"><span class="pill ${l.type.toLowerCase()}">${EXCRETION[l.type].emoji} ${EXCRETION[l.type].label}</span>
          <div class="small ${isAbnormal(l) ? 'error' : 'muted'}">${esc(excretionDetail(l)) || 'タップで状態を記録'}</div></button>
        <button class="link muted" data-act="del" data-c="excretionLogs" data-id="${l.id}">削除</button></div>`).join('');
}

const SLOTS = { NOW: ['いまの時刻', null], M: ['朝7:00', [7, 0]], N: ['昼12:00', [12, 0]], E: ['夕18:00', [18, 0]], P: ['夜22:00', [22, 0]] };

function slotTime() {
  const d = parseDate(S.selected);
  const t = SLOTS[S.slot][1];
  const now = new Date();
  d.setHours(t ? t[0] : now.getHours(), t ? t[1] : now.getMinutes(), 0, 0);
  return d.getTime();
}

function kcalClass(kcal, target) {
  if (target <= 0) return 'k-pink';
  const r = kcal / target;
  return r < 0.8 ? 'k-lemon' : r <= 1.1 ? 'k-mint' : 'k-peach';
}

function calendarView(pet) {
  const month = S.month;
  const todayKey = dateKey(new Date());
  const target = targetKcal(pet);
  const feeds = list('feedLogs').filter((l) => l.petId === pet.id);
  const toilets = list('excretionLogs').filter((l) => l.petId === pet.id);
  const health = list('health').filter((r) => r.petId === pet.id);
  const byDay = (arr, f) => arr.reduce((m, x) => { const k = f(x); (m[k] ||= []).push(x); return m; }, {});
  const feedsByDay = byDay(feeds, (l) => dateKey(new Date(l.time)));
  const toiletsByDay = byDay(toilets, (l) => dateKey(new Date(l.time)));
  const healthByDay = byDay(health, (r) => r.date);
  const offset = month.getDay();
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  let cells = '';
  for (let i = 0; i < offset; i++) cells += '<div></div>';
  for (let d = 1; d <= days; d++) {
    const key = dateKey(new Date(month.getFullYear(), month.getMonth(), d));
    const mood = S.data.moods.get(`${pet.id}_${key}`);
    let inner = '';
    if (S.calMode === 'FOOD') {
      const kcal = (feedsByDay[key] || []).reduce((s, l) => s + l.kcal, 0);
      if (kcal > 0) inner = `<span class="kc ${kcalClass(kcal, target)}">${Math.round(kcal)}</span>`;
    } else {
      const l = toiletsByDay[key] || [];
      const poop = l.filter((t) => t.type === 'POOP').length;
      const pee = l.filter((t) => t.type === 'PEE').length;
      inner = (poop ? `<span>💩${poop}</span>` : '') + (pee ? `<span>💧${pee}</span>` : '') + (l.some(isAbnormal) ? '<span class="error">⚠</span>' : '');
    }
    const kinds = [...new Set((healthByDay[key] || []).map((r) => r.kind))].slice(0, 4);
    cells += `<button class="day ${key === S.selected ? 'sel' : ''} ${key === todayKey ? 'today' : ''}" data-act="day" data-date="${key}">
      <span class="num">${d}${mood ? `<sup>${MOODS[mood.mood]?.emoji || ''}</sup>` : ''}</span>${inner}
      ${kinds.length ? `<span class="dots">${kinds.map((k) => `<i style="background:${HEALTH[k]?.color}"></i>`).join('')}</span>` : ''}</button>`;
  }

  const sel = parseDate(S.selected);
  const dayFeeds = feedsByDay[S.selected] || [];
  const dayToilets = toiletsByDay[S.selected] || [];
  const lines = planLines(pet);
  return card('', `
    <div class="row wrap">${[['FOOD', '🍚 ごはん'], ['TOILET', '🚽 トイレ']].map(([k, l]) =>
      `<button class="chip ${S.calMode === k ? 'on' : ''}" data-act="calmode" data-mode="${k}">${l}</button>`).join('')}</div>
    <div class="row month"><button class="link" data-act="month" data-d="-1">◀</button>
      <b>${month.getFullYear()}年${month.getMonth() + 1}月</b><button class="link" data-act="month" data-d="1">▶</button></div>
    <div class="grid week">${[...'日月火水木金土'].map((w) => `<span>${w}</span>`).join('')}</div>
    <div class="grid">${cells}</div>
    ${S.calMode === 'FOOD' ? '<div class="row wrap"><span class="kc k-lemon">少なめ</span><span class="kc k-mint">ちょうど</span><span class="kc k-peach">多め</span></div>' : ''}
    <div class="row wrap legend">${Object.values(HEALTH).map((h) => `<span><i style="background:${h.color}"></i>${h.label}</span>`).join('')}</div>`) +
    card(`⚡ ${sel.getMonth() + 1}/${sel.getDate()} にワンタップで記録`, `
      <div class="row wrap">${Object.entries(SLOTS).map(([k, [l]]) => `<button class="chip ${S.slot === k ? 'on' : ''}" data-act="slot" data-slot="${k}">${l}</button>`).join('')}</div>
      <div class="row wrap">${lines.filter((ln) => ln.amount > 0).map((ln) =>
        `<button class="btn tonal" data-act="feed-at" data-plan="${ln.item.id}">🍚 ${esc(ln.food.name)} ${formatAmount(ln.amount)}${esc(ln.food.unitLabel)}</button>`).join('')}
        ${list('foods').length ? `<button class="btn ghost" data-act="feed-custom" data-date="${S.selected}">＋ ほかのごはん</button>` : ''}</div>
      <div class="row gap">${Object.entries(EXCRETION).map(([k, e]) =>
        `<button class="btn ${k.toLowerCase()}" data-act="toilet-at" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
      ${moodPicker(pet, S.selected)}`, 'mint') +
    card(`📝 ${sel.getMonth() + 1}/${sel.getDate()} の記録`, recordList(dayFeeds, dayToilets) || '<p class="hint">この日の記録はありません。</p>') +
    ((healthByDay[S.selected] || []).length ? card('🩺 この日の予定・通院', healthByDay[S.selected].map((r) =>
      `<div class="row"><span class="dot" style="background:${HEALTH[r.kind]?.color}"></span>${HEALTH[r.kind]?.emoji || ''} ${esc(r.title)}${r.time ? ` ${r.time.slice(0, 5)}` : ''}${r.note ? `<div class="small muted">${esc(r.note)}</div>` : ''}</div>`).join('')) : '');
}

// ---------------- ダイアログ ----------------

function openDialog(html, onSubmit) {
  const box = $('#dialog');
  box.innerHTML = `<form class="sheet">${html}<div class="row end">
    <button type="button" class="link" data-close>キャンセル</button><button class="btn">保存</button></div></form>`;
  box.hidden = false;
  const form = box.querySelector('form');
  form.addEventListener('submit', (e) => { e.preventDefault(); if (onSubmit(new FormData(form), form) !== false) closeDialog(); });
  box.querySelector('[data-close]').addEventListener('click', closeDialog);
}
function closeDialog() { const box = $('#dialog'); box.hidden = true; box.innerHTML = ''; }

const hm = (ms) => { const d = new Date(ms); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };
function withTime(dateKeyStr, hhmm) {
  const d = parseDate(dateKeyStr);
  const [h, m] = (hhmm || '00:00').split(':').map(Number);
  d.setHours(h, m, 0, 0);
  return d.getTime();
}

/** ごはん・おやつを量や時刻を選んで記録（editing があれば修正） */
function feedDialog(pet, dateStr, editing) {
  const foods = list('foods').sort((a, b) => (a.kind === b.kind ? a.id - b.id : a.kind === 'MEAL' ? -1 : 1));
  const first = editing ? foodById(editing.foodId) || foods[0] : foods[0];
  const defAmount = (f) => (f.divisible ? f.baseAmount : 1);
  openDialog(`
    <h2>${editing ? '記録を修正' : 'ごはん・おやつを記録'}</h2>
    <label class="field">フード<select name="food">${foods.map((f) =>
      `<option value="${f.id}" ${first && f.id === first.id ? 'selected' : ''}>${f.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(f.name)}</option>`).join('')}
      ${editing && !foodById(editing.foodId) ? `<option value="" selected>${esc(editing.foodName)}（削除済み）</option>` : ''}</select></label>
    <label class="field">量（<span id="unit">${esc(editing ? editing.unitLabel : first?.unitLabel)}</span>）
      <input name="amount" type="number" inputmode="decimal" step="any" min="0" required value="${editing ? editing.amount : first ? defAmount(first) : ''}"></label>
    <label class="field">時刻<input name="time" type="time" required value="${editing ? hm(editing.time) : hm(Date.now())}"></label>`,
  (fd) => {
    const amount = Number(fd.get('amount'));
    if (!(amount > 0)) { toast('量を入れてください'); return false; }
    const food = foodById(fd.get('food'));
    const time = withTime(editing ? dateKey(new Date(editing.time)) : dateStr, fd.get('time'));
    if (editing) {
      const f = food || { id: editing.foodId, name: editing.foodName, kind: editing.kind, unitLabel: editing.unitLabel, baseAmount: editing.amount, kcalPerBase: editing.kcal };
      put('feedLogs', { ...editing, foodId: f.id, foodName: f.name, kind: f.kind, unitLabel: f.unitLabel, amount, kcal: amount * kcalPerUnit(f), time });
    } else if (food) {
      addFeed(pet, food, amount, time);
    }
    return true;
  });
  const sel = $('#dialog select[name=food]');
  sel.addEventListener('change', () => {
    const f = foodById(sel.value);
    if (!f) return;
    $('#unit').textContent = f.unitLabel;
    if (!editing) $('#dialog input[name=amount]').value = defAmount(f);
  });
}

/** トイレの状態（かたさ・量、気になる症状、メモ、時刻） */
function toiletDialog(log) {
  const e = EXCRETION[log.type];
  openDialog(`
    <h2>${e.emoji} ${e.label}の状態</h2>
    <div class="row wrap">${e.conditions.map((c) =>
      `<label class="chip"><input type="radio" name="condition" value="${c}" ${log.condition === c ? 'checked' : ''}>${c}</label>`).join('')}</div>
    <div class="col">${e.flags.map((f) =>
      `<label class="check"><input type="checkbox" name="flags" value="${f}" ${(log.flags || []).includes(f) ? 'checked' : ''}>${f}</label>`).join('')}</div>
    <label class="field">メモ<input name="note" value="${esc(log.note)}"></label>
    <label class="field">時刻<input name="time" type="time" required value="${hm(log.time)}"></label>`,
  (fd) => {
    put('excretionLogs', {
      ...log,
      condition: fd.get('condition') || null,
      flags: fd.getAll('flags'),
      note: String(fd.get('note') || ''),
      time: withTime(dateKey(new Date(log.time)), fd.get('time')),
    });
    return true;
  });
  // 同じ選択肢をもう一度押すと外せるように
  document.querySelectorAll('#dialog input[name=condition]').forEach((r) => r.addEventListener('click', () => {
    if (r.dataset.was === '1') { r.checked = false; r.dataset.was = ''; } else {
      document.querySelectorAll('#dialog input[name=condition]').forEach((o) => { o.dataset.was = ''; });
      r.dataset.was = '1';
    }
  }));
  const checked = document.querySelector('#dialog input[name=condition]:checked');
  if (checked) checked.dataset.was = '1';
}

// ---------------- トースト ----------------

let toastTimer;
function toast(message, actionLabel, action) {
  const t = $('#toast');
  t.innerHTML = `<span>${esc(message)}</span>${actionLabel ? `<button class="link">${esc(actionLabel)}</button>` : ''}`;
  t.hidden = false;
  t.querySelector('button')?.addEventListener('click', () => { t.hidden = true; action(); });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}

// ---------------- 操作 ----------------

document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-act]');
  if (!el) return;
  const pet = currentPet();
  const a = el.dataset;
  const planLine = () => planLines(pet).find((ln) => String(ln.item.id) === a.plan);
  switch (a.act) {
    case 'join': joinFamily(a.id); break;
    case 'paste-join': {
      const m = ($('#invite-input')?.value || '').match(/(?:join=|[?&]f=)([A-Za-z0-9]{12,40})/);
      if (m) joinFamily(m[1]); else toast('招待リンクの形ではないようです');
      break;
    }
    case 'leave': leave(); break;
    case 'pet': S.petId = Number(a.id); store.set('petId', a.id); render(); break;
    case 'tab': S.tab = a.tab; store.set('tab', a.tab); render(); window.scrollTo(0, 0); break;
    case 'feed': { const ln = planLine(); if (ln) addFeed(pet, ln.food, ln.amount, Date.now()); break; }
    case 'feed-at': { const ln = planLine(); if (ln) addFeed(pet, ln.food, ln.amount, slotTime()); break; }
    case 'feed-custom': feedDialog(pet, a.date); break;
    case 'edit-feed': { const l = S.data.feedLogs.get(a.id); if (l) feedDialog(pet, null, l); break; }
    case 'edit-toilet': { const l = S.data.excretionLogs.get(a.id); if (l) toiletDialog(l); break; }
    case 'toilet': addToilet(pet, a.type, Date.now()); break;
    case 'toilet-at': addToilet(pet, a.type, slotTime()); break;
    case 'mood': setMood(pet, a.date, a.mood); break;
    case 'del': {
      const item = S.data[a.c].get(a.id);
      if (item) { remove(a.c, item); toast('削除しました', '元に戻す', () => put(a.c, item)); }
      break;
    }
    case 'day': S.selected = a.date; render(); break;
    case 'slot': S.slot = a.slot; render(); break;
    case 'calmode': S.calMode = a.mode; render(); break;
    case 'month': S.month = new Date(S.month.getFullYear(), S.month.getMonth() + Number(a.d), 1); render(); break;
    default: break;
  }
});

// 日付が変わったときや、しばらく閉じていたあとに開いたときに表示を更新
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });
window.addEventListener('hashchange', () => { if (/join=/.test(location.hash)) start(); });

start();
