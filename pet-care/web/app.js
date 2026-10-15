// うちの子ごはん帳 Web 版（家族共有用）。アプリと同じ画面（きょう・カレンダー・ごはん・うちの子・まとめ・設定）で記録・閲覧ができる。
import * as backend from './backend.js';
import * as prefs from './prefs.js';
import { lineChart, tapChart, lineColor, TOTAL_COLOR } from './chart.js';
import {
  COLLECTIONS, EXCRETION, MOODS, HEALTH, petIcon, petColor, newId, docId, dateKey, parseDate, timeText, daysBetween, targetKcal, kcalPerUnit, plan, formatAmount, formatKcal, cycleText, isAbnormal, excretionDetail,
  TREAT_LIMIT_RATIO, RECORD_ITEMS, DEFAULT_ITEMS, EXPENSE_CATS, yen, shortYen, recordValueText, ABNORMAL_CONDITIONS,
  energy, estimatedIdealWeight, SEX, BCS_TITLES, PACK_UNITS, CYCLE_PRESETS, ageText, durationText, slash, mealLabel, PDF_SECTIONS,
  SPECIES, PET_COLORS, addDays,
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
  invite: null, // 招待リンクで開かれたとき { fid, key }
  members: [],
  currentInvite: null,
  account: { google: false },
  data: Object.fromEntries(COLLECTIONS.map((c) => [c, new Map()])),
  loaded: new Set(),
  error: null,
  petId: Number(store.get('petId')) || null,
  tab: store.get('tab') === 'family' ? 'settings' : store.get('tab') || 'today',
  month: (() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); })(),
  selected: dateKey(new Date()),
  slot: null,
  calMode: ['RECORD', 'DIARY', 'SCHEDULE', 'EXPENSE'].includes(store.get('calMode')) ? store.get('calMode') : 'RECORD',
  calShow: (() => { try { return JSON.parse(store.get('calShow')) || null; } catch { return null; } })()
    || ['KCAL', 'TOILET', 'MOOD', 'RECORDS', 'SCHEDULE', 'DIARY', 'EXPENSE'],
  showOptions: false,
  petView: null, // 「うちの子」で開いている子（null なら一覧）
  trendKey: 'WEIGHT',
  trendRange: 'MONTH',
  weightRange: 'QUARTER',
  showAllWeights: false,
  showAllRecords: false,
  dashScope: 0, // 0 は全員
  dashRange: 'MONTH',
  layoutScreen: 'TABS',
  undo: [], // ワンタップで付けた記録の取り消し（新しいものが後ろ）
  pdf: null, // 印刷（PDF）の設定
};
let unsubscribe = null;

const list = (c) => [...S.data[c].values()];
const pets = () => list('pets').sort((a, b) => (a.order || 0) - (b.order || 0) || a.id - b.id);
/** 家族で決めた「記録する項目」 */
const items = () => S.data.settings.get('app')?.items || DEFAULT_ITEMS;
const isOn = (key) => items().includes(key);
const shows = (key) => S.calShow.includes(key);

// ---------------- 写真（家族のクラウドから必要なときだけ取ってくる） ----------------

const photoCache = new Map(); // id → data URL | 'loading' | 'missing'
function photoUrl(id) {
  const c = photoCache.get(id);
  if (c === undefined) {
    photoCache.set(id, 'loading');
    backend.getPhoto(S.familyId, id).then((b) => {
      photoCache.set(id, b ? `data:image/jpeg;base64,${b}` : 'missing');
      render();
    }, () => photoCache.set(id, 'missing'));
    return null;
  }
  return c === 'loading' || c === 'missing' ? null : c;
}
const photoTag = (id, cls = 'thumb', clickable = true) => {
  const url = photoUrl(id);
  return url ? `<img class="${cls}" src="${url}" alt=""${clickable ? ` data-act="photo" data-id="${esc(id)}"` : ''}>` : `<span class="${cls} ph">🖼</span>`;
};
// 写真があれば写真アイコンが標準（iconMode が emoji のときだけ絵文字）
const iconIsPhoto = (p) => (p.iconMode ? p.iconMode === 'photo' : true);
const avatarTag = (p) => (iconIsPhoto(p) && p.photoId && photoUrl(p.photoId)
  ? `<img class="avatar" src="${photoUrl(p.photoId)}" alt="">`
  : `<span class="avatar" style="background:${petColor(p)}">${petIcon(p)}</span>`);

/** 選んだ写真を、長い辺 800px・JPEG に縮めて Base64 にする（アプリのトイレの写真と同じくらい） */
async function compressPhoto(file, maxSide = 800, quality = 0.55, square = false) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  // square のときは真ん中を正方形に切り抜く（うちの子のトップ画像）
  const side = Math.min(bmp.width, bmp.height);
  const [sx, sy, sw, sh] = square ? [(bmp.width - side) / 2, (bmp.height - side) / 2, side, side] : [0, 0, bmp.width, bmp.height];
  const scale = Math.min(1, maxSide / Math.max(sw, sh));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(sw * scale);
  canvas.height = Math.round(sh * scale);
  canvas.getContext('2d').drawImage(bmp, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((r) => canvas.toBlob(r, 'image/jpeg', quality));
  const buf = new Uint8Array(await blob.arrayBuffer());
  let bin = '';
  for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return btoa(bin);
}
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

/**
 * ワンタップで付けた記録を知らせ、取り消しの履歴にも残す。
 * トーストが消えたあとも、開いている間は「↩ 取り消す」で新しい順に戻せる。
 */
function recorded(message, c, item) {
  const pet = S.data.pets.get(String(item.petId));
  const text = `${pet ? `${pet.name}：` : ''}${message}`;
  const entry = { label: text.replace(/を記録しました$/, ''), undo: () => remove(c, item) };
  S.undo.push(entry);
  if (S.undo.length > 30) S.undo.shift();
  render();
  toast(text, '取り消す', () => undoEntry(entry));
}
function undoEntry(entry) {
  const i = S.undo.indexOf(entry);
  if (i < 0) return;
  S.undo.splice(i, 1);
  entry.undo();
}
function undoBar() {
  const last = S.undo[S.undo.length - 1];
  if (!last) return '';
  return `<div class="row undo"><button class="btn ghost" data-act="undo-last">↩ 取り消す</button>
    <span class="grow small">直前：${esc(last.label)}${S.undo.length > 1 ? `（ほか${S.undo.length - 1}件も順に戻せます）` : ''}</span>
    <button class="link muted" data-act="undo-clear">閉じる</button></div>`;
}
const whenText = (time) => (sameDay(time, dateKey(new Date())) ? timeText(time) : `${md(time)} ${timeText(time)}`);

function addFeed(pet, food, amount, time) {
  const log = {
    id: newId(), petId: pet.id, foodId: food.id, foodName: food.name, kind: food.kind,
    amount, unitLabel: food.unitLabel, kcal: amount * kcalPerUnit(food), time,
  };
  put('feedLogs', log);
  recorded(`${whenText(time)} ${food.name}を記録しました`, 'feedLogs', log);
}

function addToilet(pet, type, time) {
  const log = { id: newId(), petId: pet.id, type, time, condition: null, flags: [], note: '', photoIds: [] };
  put('excretionLogs', log);
  recorded(`${whenText(time)} ${EXCRETION[type].label}を記録しました`, 'excretionLogs', log);
}

function setMood(pet, date, mood) {
  const cur = S.data.moods.get(`${pet.id}_${date}`);
  if (cur && cur.mood === mood) remove('moods', cur);
  else put('moods', { petId: pet.id, date, mood });
}

// ---------------- 起動 ----------------

const INVITE_RE = /(?:join=|[?&]f=)([A-Za-z0-9]{12,40})[.]([A-Za-z0-9]{12,40})/;

async function start() {
  const m = location.hash.match(INVITE_RE);
  if (/join=/.test(location.hash)) history.replaceState(null, '', location.pathname + location.search);
  try {
    await backend.init();
    S.account = backend.account();
  } catch {
    S.phase = 'not-configured';
    return render();
  }
  if (m && m[1] !== S.familyId) {
    S.invite = { fid: m[1], key: m[2] };
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
    unsubscribe = await backend.subscribe(S.familyId, {
      data: (c, items) => { S.data[c] = items; S.loaded.add(c); render(); },
      members: (m) => { S.members = m; if (S.tab === 'settings') render(); },
      invite: (i) => { S.currentInvite = i; if (S.tab === 'settings') render(); },
      error: (kind) => {
        S.error = kind === 'kicked' ? 'この端末は家族の共有から外されました。続けるには、家族にもう一度招待してもらってください。'
          : kind === 'denied' ? '家族の記録を読めませんでした。家族から外された可能性があります。招待リンクから参加し直してください。'
            : '通信できませんでした。電波のよいところで開き直してください。';
        if (kind === 'kicked') unsubscribe?.();
        render();
      },
    });
  } catch {
    S.error = '通信できませんでした。電波のよいところで開き直してください。';
    render();
  }
}

function useFamily(fid) {
  S.familyId = fid;
  store.set('familyId', fid);
  S.invite = null;
  S.error = null;
  connect();
}

async function joinFamily(fid, key) {
  const btn = $('#join-btn');
  if (btn) { btn.disabled = true; btn.textContent = '参加しています…'; }
  try {
    await backend.join(fid, key);
    S.account = backend.account();
    useFamily(fid);
  } catch (e) {
    if (btn) { btn.disabled = false; btn.textContent = '参加する'; }
    toast(e?.code === 'permission-denied'
      ? '招待の期限が切れているか、取り消されています。家族に新しい招待を出してもらってください'
      : '参加できませんでした。通信状態を確かめてください');
  }
}

async function googleLogin() {
  try {
    const fid = await backend.signInWithGoogle(S.familyId);
    S.account = backend.account();
    if (S.familyId) {
      toast('Google アカウントでログインしました');
      connect();
    } else if (fid) {
      if (confirm('この Google アカウントで共有していた家族の記録が見つかりました。表示しますか？')) useFamily(fid);
    } else {
      toast('この Google アカウントで参加している家族はまだありません。招待リンクから参加してください');
      render();
    }
  } catch (e) {
    if (e?.code === 'auth/popup-closed-by-user' || e?.code === 'auth/cancelled-popup-request') return;
    toast(e?.code === 'auth/popup-blocked'
      ? 'ログイン画面を開けませんでした。ポップアップを許可してもう一度お試しください'
      : 'Google アカウントでログインできませんでした');
  }
}

async function leave() {
  if (!confirm('この端末で家族の記録を見るのをやめますか？（家族の記録は消えません）')) return;
  unsubscribe?.();
  if (!S.error) await backend.leave(S.familyId).catch(() => {});
  S.familyId = null;
  S.error = null;
  S.members = [];
  S.currentInvite = null;
  store.set('familyId', null);
  S.data = Object.fromEntries(COLLECTIONS.map((c) => [c, new Map()]));
  S.phase = 'no-family';
  render();
}

// ---------------- 画面 ----------------

const TABS = {
  TAB_HOME: ['today', '🏠', 'きょう'],
  TAB_CALENDAR: ['calendar', '📅', 'カレンダー'],
  TAB_FOOD: ['food', '🍚', 'ごはん'],
  TAB_PET: ['pet', '🐾', 'うちの子'],
  TAB_DASHBOARD: ['dashboard', '📊', 'まとめ'],
  TAB_SETTINGS: ['settings', '⚙', '設定'],
};

function render() {
  const top = $('#top');
  const main = $('#main');
  const tabs = $('#tabs');
  document.documentElement.style.setProperty('--zoom', String(prefs.fontScale()));
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
  const visibleTabs = prefs.visible('TABS').map((k) => TABS[k]).filter(Boolean);
  if (!visibleTabs.some(([k]) => k === S.tab)) S.tab = visibleTabs[0][0];
  tabs.hidden = false;
  tabs.innerHTML = visibleTabs.map(([k, e, l]) =>
    `<button class="${S.tab === k ? 'on' : ''}" data-act="tab" data-tab="${k}"><span>${e}</span>${l}</button>`).join('');
  tabs.classList.toggle('many', visibleTabs.length > 5);

  if (!pet && S.tab !== 'settings') {
    const waiting = S.loaded.size < COLLECTIONS.length;
    main.innerHTML = card('', waiting ? '<p class="hint">家族の記録を読み込んでいます…</p>'
      : `<p>まだペットが登録されていません。</p><button class="btn" data-act="add-pet">＋ うちの子を登録</button>`);
    return;
  }
  // 「うちの子」「まとめ」「設定」は全員を扱う画面なので、上のペット切り替えは出さない
  if (pet && !['pet', 'dashboard', 'settings'].includes(S.tab)) {
    top.innerHTML += `<div class="chips">${pets().map((p) => `
      <button class="chip ${p.id === pet.id ? 'on' : ''}" data-act="pet" data-id="${p.id}">
        ${avatarTag(p)}${esc(p.name)}
      </button>`).join('')}</div>`;
  }
  main.innerHTML = S.tab === 'calendar' ? calendarView(pet)
    : S.tab === 'food' ? foodView(pet)
      : S.tab === 'pet' ? petTabView()
        : S.tab === 'dashboard' ? dashboardView()
          : S.tab === 'settings' ? settingsView()
            : todayView(pet);
}

const card = (title, body, cls = '') => `<section class="card ${cls}">${title ? `<h2>${title}</h2>` : ''}${body}</section>`;

function inviteView() {
  const android = /Android/i.test(navigator.userAgent);
  return card('👨‍👩‍👧 家族の記録に参加', `
      <p>家族から招待されています。参加すると、家族と同じ「うちの子ごはん帳」の記録を見たり書いたりできます。</p>
      <button id="join-btn" class="btn" data-act="join">参加する</button>
      ${android ? `<p class="hint">Android でアプリを入れている場合は、アプリで開くこともできます。</p>
        <a class="btn ghost" href="petcare://join?f=${esc(S.invite.fid)}&k=${esc(S.invite.key)}">アプリで開く</a>` : ''}
      <p class="hint">iPhone の方は、参加したあと Safari の共有ボタン →「ホーム画面に追加」でアプリのように使えます。</p>`);
}

function noFamilyView() {
  return card('👨‍👩‍👧 家族と共有', `
      <p>家族のアプリに表示される QR コードを読み取るか、届いた招待リンクを開いてください。</p>
      <label class="field">招待リンクを貼り付けて参加
        <input id="invite-input" placeholder="https://…#join=…" autocomplete="off"></label>
      <button class="btn" data-act="paste-join">参加する</button>`) +
    card('🔐 Google アカウントでログイン', `
      <p class="hint">前に Google アカウントでログインして家族と共有していた場合は、ログインすると家族の記録に戻れます。</p>
      <button class="btn ghost" data-act="google">Google でログイン</button>`);
}

const md = (ms) => { const d = new Date(ms); return `${d.getMonth() + 1}/${d.getDate()}`; };
const inviteUrl = (key) => `${location.origin}${location.pathname}#join=${S.familyId}.${key}`;

/** 家族：メンバー・招待・Google ログイン・共有をやめる */
function familyView() {
  const inv = S.currentInvite;
  const acc = S.account;
  return card('👨‍👩‍👧 メンバー', S.members.map((m) => `
      <div class="row rec"><div class="grow"><span${m.me ? ' class="label"' : ''}>${m.platform === 'web' ? '🌐' : '📱'} ${esc(m.name)}${m.me ? '（この端末）' : ''}</span>
        <div class="small muted">${[m.joinedAt ? `${md(m.joinedAt)} から参加` : '', m.google ? 'Google ログイン' : ''].filter(Boolean).join('・')}</div></div>
        ${m.me ? '' : `<button class="link muted" data-act="remove-member" data-uid="${esc(m.uid)}" data-name="${esc(m.name)}">外す</button>`}</div>`).join('')
      || '<p class="hint">読み込み中…</p>') +
    card('✉️ 家族を招待', inv ? `
      <p class="hint">このリンクを家族に送ると参加できます。${md(inv.expiresAt)} ${timeText(inv.expiresAt)} まで有効です。</p>
      <input class="copy" readonly value="${esc(inviteUrl(inv.key))}">
      <div class="row wrap"><button class="btn" data-act="copy-invite">リンクをコピー</button>
        ${navigator.share ? '<button class="btn ghost" data-act="share-invite">送る</button>' : ''}
        <button class="link" data-act="new-invite">作り直す</button><button class="link muted" data-act="revoke-invite">取り消す</button></div>
      <p class="hint">招待を知っている人は誰でも参加できます。家族以外に渡ってしまったら「取り消す」を押し、知らないメンバーは「外す」で外してください。</p>`
      : `<p class="hint">招待は 7 日で使えなくなります。家族を招待するときに作ってください。</p>
      <button class="btn" data-act="new-invite">招待リンクを作る</button>`) +
    card('🔐 Google アカウント', acc.google
      ? `<p>ログイン中：${esc(acc.name)} ${esc(acc.email)}</p><p class="hint">機種変更などのあとも、同じアカウントでログインすれば家族の記録に戻れます。</p>`
      : `<p class="hint">ログインしておくと、機種変更やブラウザのデータ削除のあとも、招待なしで家族の記録に戻れます。</p>
        <button class="btn ghost" data-act="google">Google でログイン</button>`) +
    `<p class="center"><button class="link muted" data-act="leave">この端末の共有をやめる</button></p>`;
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
      const what = [...new Set(bad.flatMap((l) => [...(l.flags || []), ...(ABNORMAL_CONDITIONS.includes(l.condition) ? [l.condition] : [])]))];
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

  const section = {
    HOME_UNDO: () => (S.undo.length ? card('', undoBar(), 'mint') : ''),
    HOME_ALERTS: () => {
      const alerts = healthAlerts(pet);
      const upcoming = list('health').filter((r) => r.petId === pet.id && daysBetween(today, parseDate(r.date)) >= 0 && daysBetween(today, parseDate(r.date)) <= 14)
        .sort((a, b) => (a.date + (a.time || '09:00')).localeCompare(b.date + (b.time || '09:00')));
      if (!alerts.length && !upcoming.length) return '';
      return card('', alerts.map((a) => `<p class="warn">⚠ ${esc(a)}</p>`).join('') + upcoming.map((r) => healthText(r, today)).join(''), alerts.length ? 'peach' : 'lavender');
    },
    HOME_KCAL: () => card('', `
      <div class="label">${today.getMonth() + 1}/${today.getDate()} の ${esc(pet.name)}</div>
      <div class="kcal"><b>${Math.round(eaten)}</b> / ${formatKcal(target)}</div>
      <div class="bar"><i style="width:${Math.min(100, ratio * 100)}%;${ratio > 1.1 ? 'background:var(--peach)' : ''}"></i></div>
      <p>${diff > 0.5 ? `あと ${formatKcal(diff)} あげられます` : diff < -target * 0.1 ? `目安より ${formatKcal(-diff)} 多めです` : '今日の目安に届きました 🎉'}</p>
      ${treats > 0 ? `<p class="small ${treats > target * TREAT_LIMIT_RATIO ? 'error' : ''}">おやつ ${formatKcal(treats)}（目安の10% = ${formatKcal(target * TREAT_LIMIT_RATIO)} まで）${treats > target * TREAT_LIMIT_RATIO ? ' ⚠ あげすぎ' : ''}</p>` : ''}`, 'pink'),
    HOME_FOOD: () => {
      if (!isOn('FOOD')) return '';
      const lines = planLines(pet);
      return card('🍚 ごはんをあげた', (lines.length ? lines.map((ln) => {
        const given = feeds.filter((l) => l.foodId === ln.food.id).length;
        return `<div class="row">
          <div class="grow"><b>${esc(ln.food.name)}</b>
            <div class="small muted">1回 ${formatAmount(ln.amount)} ${esc(ln.food.unitLabel)}・${cycleText(ln.item.timesPerCycle, ln.item.cycleDays)}</div>
            <div class="small accent">今日 ${given}${ln.item.cycleDays === 1 ? `／${ln.item.timesPerCycle}` : ''} 回</div></div>
          <button class="btn" data-act="feed" data-plan="${ln.item.id}" ${ln.amount > 0 ? '' : 'disabled'}>あげた</button></div>`;
      }).join('') : `<p class="hint">ごはんプランはまだありません。</p><button class="link" data-act="tab" data-tab="food">🍚「ごはん」でプランを作る</button>`) +
        (list('foods').length ? `<button class="link" data-act="feed-custom" data-date="${key}">＋ 量を変えて・プラン外のごはんやおやつを記録</button>` : ''));
    },
    HOME_TOILET: () => {
      const toiletTypes = Object.entries(EXCRETION).filter(([k]) => isOn(k));
      if (!toiletTypes.length && !isOn('MOOD')) return '';
      return card(toiletTypes.length ? '🚽 トイレ・きげん' : '☀️ きげん', `
        <div class="row gap">${toiletTypes.map(([k, e]) =>
          `<button class="btn big ${k.toLowerCase()}" data-act="toilet" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
        ${toiletTypes.map(([k, e]) => {
          const l = toilets.filter((t) => t.type === k);
          const last = l.length ? Math.max(...l.map((t) => t.time)) : null;
          return `<div class="small muted">${e.emoji} 今日 ${l.length} 回${last ? `（最後 ${timeText(last)}）` : ''}</div>`;
        }).join('')}` + (isOn('MOOD') ? moodPicker(pet, key) : ''));
    },
    HOME_OTHER: () => card('📝 ほかの記録', quickItems(key, 'now')),
    HOME_LOG: () => {
      const records = list('records').filter((r) => r.petId === pet.id && sameDay(r.time, key));
      return card('🗒 今日の記録', recordList(feeds, toilets, records) || '<p class="hint">まだ記録がありません。トイレの記録をタップすると状態（かたさ・血が混じる など）や写真を残せます。</p>');
    },
  };
  return prefs.visible('HOME').map((k) => section[k]?.() || '').join('');
}

/** 設定で選んだ「ほかの記録」のボタン。チェックの項目はワンタップで記録する */
function quickItems(dateStr, timeMode) {
  const keys = items().filter((k) => RECORD_ITEMS[k] && (!RECORD_ITEMS[k].special || k === 'WEIGHT'));
  if (!keys.length) return '<p class="hint">アプリの「設定」→「記録する項目」で、体温・散歩・薬などを追加できます。</p>';
  return `<div class="row wrap">${keys.map((k) => `<button class="chip" data-act="quick-item" data-item="${k}" data-date="${dateStr}" data-tm="${timeMode}">
    ${RECORD_ITEMS[k].emoji} ${RECORD_ITEMS[k].label}${RECORD_ITEMS[k].kind === 'CHECK' ? ' ✓' : ''}</button>`).join('')}</div>`;
}

function recordList(feeds, toilets, records = []) {
  const entries = [...feeds.map((l) => ['feed', l]), ...toilets.map((l) => ['toilet', l]), ...records.map((l) => ['record', l])]
    .sort((a, b) => b[1].time - a[1].time);
  return entries.map(([kind, l]) => kind === 'record'
    ? `<div class="row rec"><span class="time">${timeText(l.time)}</span>
        <button class="grow plain" data-act="edit-record" data-id="${l.id}">${RECORD_ITEMS[l.item]?.emoji || '📝'} ${esc(RECORD_ITEMS[l.item]?.label || l.item)}
          <div class="small muted">${esc(recordValueText(l))}</div></button>
        <button class="link muted" data-act="del" data-c="records" data-id="${l.id}">削除</button></div>`
    : kind === 'feed'
    ? `<div class="row rec"><span class="time">${timeText(l.time)}</span>
        <button class="grow plain" data-act="edit-feed" data-id="${l.id}">${l.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(l.foodName)}
          <div class="small muted">${formatAmount(l.amount)} ${esc(l.unitLabel)}・${formatKcal(l.kcal)}</div></button>
        <button class="link muted" data-act="del" data-c="feedLogs" data-id="${l.id}">削除</button></div>`
    : `<div class="row rec"><span class="time">${timeText(l.time)}</span>
        <button class="grow plain" data-act="edit-toilet" data-id="${l.id}"><span class="pill ${l.type.toLowerCase()}">${EXCRETION[l.type].emoji} ${EXCRETION[l.type].label}</span>
          <div class="small ${isAbnormal(l) ? 'error' : 'muted'}">${esc(excretionDetail(l)) || 'タップで状態を記録'}${(l.photoIds || []).length ? `　📷${l.photoIds.length}` : ''}</div></button>
        <button class="link muted" data-act="del" data-c="excretionLogs" data-id="${l.id}">削除</button></div>`).join('');
}

// ごはんの時刻（アプリの「設定」で家族共通に決めたもの）
const mealTimes = () => (S.data.settings.get('app')?.mealTimes || ['07:00', '12:00', '18:00', '22:00'])
  .map((t) => t.split(':').map(Number)).filter((t) => t.length >= 2 && !t.some(Number.isNaN));
function slots() {
  return [['NOW', 'いまの時刻', null], ...mealTimes().map((t, i) => [String(i), `${mealLabel(t[0])} ${t[0]}:${String(t[1]).padStart(2, '0')}`, t])];
}
function currentSlot() {
  const all = slots();
  if (S.slot && all.some(([k]) => k === S.slot)) return S.slot;
  if (!S.data.settings.get('app')?.quickAtMealTime || all.length < 2) return 'NOW';
  // いまに近いごはんの時刻
  const now = new Date();
  const m = now.getHours() * 60 + now.getMinutes();
  const dist = (t) => { const d = Math.abs(t[0] * 60 + t[1] - m); return Math.min(d, 1440 - d); };
  return all.slice(1).reduce((a, b) => (dist(b[2]) < dist(a[2]) ? b : a))[0];
}

function slotTime() {
  const d = parseDate(S.selected);
  const t = slots().find(([k]) => k === currentSlot())?.[2];
  const now = new Date();
  d.setHours(t ? t[0] : now.getHours(), t ? t[1] : now.getMinutes(), 0, 0);
  return d.getTime();
}

function kcalClass(kcal, target) {
  if (target <= 0) return 'k-pink';
  const r = kcal / target;
  return r < 0.8 ? 'k-lemon' : r <= 1.1 ? 'k-mint' : 'k-peach';
}

const CAL_MODES = [['RECORD', '🍚 ごはん・排泄'], ['DIARY', '📓 日記'], ['SCHEDULE', '🗓 予定'], ['EXPENSE', '💴 支出']];
const CAL_SHOW = [['KCAL', 'カロリー'], ['TOILET', 'うんち・おしっこの回数'], ['MOOD', '機嫌'], ['RECORDS', 'そのほかの記録'],
  ['SCHEDULE', '予定'], ['DIARY', '日記'], ['EXPENSE', '支出']];

/** 予定・記録・日記・支出をまとめたカレンダー（アプリと同じ） */
function calendarView(pet) {
  const month = S.month;
  const todayKey = dateKey(new Date());
  const target = targetKcal(pet);
  const byDay = (arr, f) => arr.reduce((m, x) => { const k = f(x); (m[k] ||= []).push(x); return m; }, {});
  const feedsByDay = byDay(list('feedLogs').filter((l) => l.petId === pet.id), (l) => dateKey(new Date(l.time)));
  const toiletsByDay = byDay(list('excretionLogs').filter((l) => l.petId === pet.id), (l) => dateKey(new Date(l.time)));
  const healthByDay = byDay(list('health').filter((r) => r.petId === pet.id), (r) => r.date);
  const recordsByDay = byDay(list('records').filter((r) => r.petId === pet.id), (r) => dateKey(new Date(r.time)));
  const weightsByDay = byDay(list('weights').filter((w) => w.petId === pet.id), (w) => w.date);
  const expensesByDay = byDay(list('expenses').filter((e) => e.petId == null || e.petId === pet.id), (e) => e.date);
  const diary = (key) => S.data.diaries.get(`${pet.id}_${key}`);
  const offset = month.getDay();
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const lineCount = ['KCAL', 'TOILET', 'RECORDS', 'EXPENSE'].filter(shows).length;
  let cells = '';
  for (let i = 0; i < offset; i++) cells += '<div></div>';
  for (let d = 1; d <= days; d++) {
    const key = dateKey(new Date(month.getFullYear(), month.getMonth(), d));
    const mood = shows('MOOD') && S.data.moods.get(`${pet.id}_${key}`);
    let inner = '';
    if (shows('KCAL')) {
      const kcal = (feedsByDay[key] || []).reduce((s, l) => s + l.kcal, 0);
      if (kcal > 0) inner += `<span class="kc ${kcalClass(kcal, target)}">${Math.round(kcal)}</span>`;
    }
    if (shows('TOILET')) {
      const l = toiletsByDay[key] || [];
      const poop = l.filter((t) => t.type === 'POOP').length;
      const pee = l.filter((t) => t.type === 'PEE').length;
      const t = (poop ? `💩${poop}` : '') + (pee ? `💧${pee}` : '') + (l.some(isAbnormal) ? '⚠' : '');
      if (t) inner += `<span>${t}</span>`;
    }
    if (shows('RECORDS')) {
      const e = [...new Set([...(recordsByDay[key] || []).map((r) => RECORD_ITEMS[r.item]?.emoji || '📝'), ...(weightsByDay[key] ? ['⚖'] : [])])];
      if (e.length) inner += `<span>${e.slice(0, 3).join('')}</span>`;
    }
    if (shows('EXPENSE')) {
      const sum = (expensesByDay[key] || []).reduce((s, x) => s + (x.amount || 0), 0);
      if (sum > 0) inner += `<span class="yen">${shortYen(sum)}</span>`;
    }
    const kinds = shows('SCHEDULE') ? [...new Set((healthByDay[key] || []).map((r) => r.kind))].slice(0, 3) : [];
    const hasDiary = shows('DIARY') && diary(key);
    cells += `<button class="day ${key === S.selected ? 'sel' : ''} ${key === todayKey ? 'today' : ''}" data-act="day" data-date="${key}">
      <span class="num">${d}${mood ? `<sup>${MOODS[mood.mood]?.emoji || ''}</sup>` : ''}</span>${inner}
      ${kinds.length || hasDiary ? `<span class="dots">${kinds.map((k) => `<i style="background:${HEALTH[k]?.color}"></i>`).join('')}${hasDiary ? '<b>📓</b>' : ''}</span>` : ''}</button>`;
  }

  const sel = parseDate(S.selected);
  const label = `${sel.getMonth() + 1}/${sel.getDate()}`;
  const inMonth = (key) => key.startsWith(dateKey(month).slice(0, 8));
  let summary = '';
  if (S.calMode === 'EXPENSE') {
    const l = Object.entries(expensesByDay).filter(([k]) => inMonth(k)).flatMap(([, v]) => v);
    const byCat = Object.entries(l.reduce((m, e) => { m[e.category] = (m[e.category] || 0) + e.amount; return m; }, {})).sort((a, b) => b[1] - a[1]);
    const all = list('expenses').filter((e) => inMonth(e.date));
    const mine = all.filter((e) => e.petId === pet.id).reduce((s, e) => s + e.amount, 0);
    const common = all.filter((e) => e.petId == null).reduce((s, e) => s + e.amount, 0);
    summary = `<div class="label">今月の支出</div><div class="row wrap"><span class="pill k-pink">${esc(pet.name)} ${yen(mine)}</span>
      ${common ? `<span class="pill k-mint">みんなの分 ${yen(common)}</span>` : ''}<span class="pill k-lemon">全体 ${yen(all.reduce((s, e) => s + e.amount, 0))}</span></div>
      <div class="small muted">${byCat.map(([c, v]) => `${EXPENSE_CATS[c]?.emoji || ''}${yen(v)}`).join('　')}</div>`;
  }

  let quick = '';
  if (S.calMode === 'RECORD') {
    const lines = planLines(pet);
    quick = card(`⚡ ${label} にワンタップで記録`, `${undoBar()}
      <div class="row wrap">${slots().map(([k, l]) => `<button class="chip ${currentSlot() === k ? 'on' : ''}" data-act="slot" data-slot="${k}">${l}</button>`).join('')}</div>
      ${isOn('FOOD') ? `<div class="row wrap">${lines.filter((ln) => ln.amount > 0).map((ln) =>
        `<button class="btn tonal" data-act="feed-at" data-plan="${ln.item.id}">🍚 ${esc(ln.food.name)} ${formatAmount(ln.amount)}${esc(ln.food.unitLabel)}</button>`).join('')}
        ${list('foods').length ? `<button class="btn ghost" data-act="feed-custom" data-date="${S.selected}">＋ ほかのごはん</button>` : ''}</div>` : ''}
      <div class="row gap">${Object.entries(EXCRETION).filter(([k]) => isOn(k)).map(([k, e]) =>
        `<button class="btn ${k.toLowerCase()}" data-act="toilet-at" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
      ${quickItems(S.selected, 'slot')}
      ${isOn('MOOD') ? moodPicker(pet, S.selected) : ''}
      <p class="hint small">押すとすぐ記録されます。まちがえたときは「↩ 取り消す」で戻せます。</p>`, 'mint');
  } else if (S.calMode === 'DIARY') {
    quick = card(`📓 ${label} の日記`, `
      <textarea id="diary-text" class="area" rows="5" placeholder="今日の様子、できるようになったこと、気になったことなど">${esc(diary(S.selected)?.text || '')}</textarea>
      <div class="row"><button class="btn" data-act="save-diary">保存</button></div>`, 'lavender');
  } else if (S.calMode === 'SCHEDULE') {
    quick = card(`🗓 ${label} に予定を追加`, `<div class="row wrap">${Object.entries(HEALTH).map(([k, h]) =>
      `<button class="chip" data-act="add-health" data-kind="${k}">${h.emoji} ${h.label}</button>`).join('')}</div>
      <p class="hint">通知の時刻はアプリで変えられます（Web 版から入れた予定は前日20時に通知）。</p>`, 'peach');
  } else {
    quick = card(`💴 ${label} の支出を記録`, `<div class="row wrap">${Object.entries(EXPENSE_CATS).map(([k, c]) =>
      `<button class="chip" data-act="add-expense" data-cat="${k}">${c.emoji} ${c.label}</button>`).join('')}</div>`, 'lemon');
  }

  const dayFeeds = feedsByDay[S.selected] || [];
  const dayToilets = toiletsByDay[S.selected] || [];
  const dayRecords = recordsByDay[S.selected] || [];
  const dayWeights = weightsByDay[S.selected] || [];
  const events = (healthByDay[S.selected] || []).sort((a, b) => (a.time || '09:00').localeCompare(b.time || '09:00'));
  const spent = expensesByDay[S.selected] || [];
  const d = diary(S.selected);
  const dayBody = (dayFeeds.length || isOn('FOOD') ? `<b class="accent">🍚 合計 ${formatKcal(dayFeeds.reduce((s, l) => s + l.kcal, 0))} ／ 目安 ${formatKcal(target)}</b>` : '')
    + dayWeights.map((w) => `<div class="row"><span class="grow">⚖ 体重${w.time ? ` ${w.time.slice(0, 5)}` : ''}</span><b>${formatAmount(w.kg)} kg</b></div>`).join('')
    + (recordList(dayFeeds, dayToilets, dayRecords) || (dayWeights.length ? '' : '<p class="hint">この日の記録はありません。</p>'))
    + (events.length ? `<div class="label">🗓 予定・通院</div>${events.map((r) =>
      `<button class="plain row" data-act="edit-health" data-id="${r.id}"><span class="dot" style="background:${HEALTH[r.kind]?.color}"></span>${HEALTH[r.kind]?.emoji || ''} ${esc(r.title)}${r.time ? ` ${r.time.slice(0, 5)}` : ''}${r.note ? `<span class="small muted">　${esc(r.note)}</span>` : ''}</button>`).join('')}` : '')
    + (d ? `<div class="label">📓 日記</div><p class="pre">${esc(d.text)}</p>` : '')
    + (spent.length ? `<div class="label">💴 支出　この子 ${yen(spent.filter((e) => e.petId === pet.id).reduce((s, e) => s + e.amount, 0))}${spent.some((e) => e.petId == null) ? `・みんなの分 ${yen(spent.filter((e) => e.petId == null).reduce((s, e) => s + e.amount, 0))}` : ''}　／　全体 ${yen(list('expenses').filter((e) => e.date === S.selected).reduce((s, e) => s + e.amount, 0))}</div>${spent.map((e) =>
      `<button class="plain row" data-act="edit-expense" data-id="${e.id}"><span class="grow">${EXPENSE_CATS[e.category]?.emoji || ''} ${EXPENSE_CATS[e.category]?.label || ''}${e.memo ? `・${esc(e.memo)}` : ''}
        <span class="small muted">　${e.petId == null ? 'みんな' : esc(S.data.pets.get(String(e.petId))?.name || '')}</span></span><b>${yen(e.amount)}</b></button>`).join('')}` : '');

  const base = lineCount >= 4 ? 0.55 : lineCount === 3 ? 0.62 : 0.72;
  const section = {
    CAL_GRID: () => `<div class="chips">${CAL_MODES.map(([k, l]) => `<button class="chip ${S.calMode === k ? 'on' : ''}" data-act="calmode" data-mode="${k}">${l}</button>`).join('')}</div>` +
      card('', `
      <div class="row month"><button class="link" data-act="month" data-d="-1">◀</button>
        <b>${month.getFullYear()}年${month.getMonth() + 1}月</b><button class="link" data-act="month" data-d="1">▶</button></div>
      <div class="grid week">${[...'日月火水木金土'].map((w) => `<span>${w}</span>`).join('')}</div>
      <div class="grid days" style="--cell:${(base / prefs.calendarSize()).toFixed(3)}">${cells}</div>
      ${summary}
      <div class="row">${shows('KCAL') ? '<div class="row wrap grow"><span class="kc k-lemon">少なめ</span><span class="kc k-mint">ちょうど</span><span class="kc k-peach">多め</span></div>' : '<span class="grow"></span>'}
        <button class="link" data-act="show-options">${S.showOptions ? '閉じる' : '👁 表示'}</button></div>
      ${S.showOptions ? `<div class="label">カレンダーに出すもの</div><div class="col">${CAL_SHOW.map(([k, l]) =>
        `<label class="check"><input type="checkbox" data-act="cal-show" data-key="${k}" ${shows(k) ? 'checked' : ''}>${l}</label>`).join('')}</div>
        ${shows('SCHEDULE') ? `<div class="row wrap legend">${Object.values(HEALTH).map((h) => `<span><i style="background:${h.color}"></i>${h.label}</span>`).join('')}</div>` : ''}
        <p class="hint small">マスの大きさや、この画面の並び順は「設定」→「画面の表示」で変えられます。</p>` : ''}`),
    CAL_QUICK: () => quick,
    CAL_DAY: () => card(`📝 ${label} の記録`, dayBody),
  };
  return prefs.visible('CALENDAR').map((k) => section[k]?.() || '').join('');
}

// ---------------- ごはん ----------------

function foodView(pet) {
  const target = targetKcal(pet);
  const lines = planLines(pet);
  const total = lines.reduce((s, ln) => s + ln.dailyKcal, 0);
  const fixed = lines.filter((ln) => !(ln.item.auto && ln.food.divisible)).reduce((s, ln) => s + ln.dailyKcal, 0);
  const treats = lines.filter((ln) => ln.food.kind === 'TREAT').reduce((s, ln) => s + ln.dailyKcal, 0);
  const warns = [];
  if (fixed > target * 1.05) warns.push(`量を決めたごはん・おやつだけで、1日の目安（${formatKcal(target)}）を超えています。`);
  if (treats > target * TREAT_LIMIT_RATIO) warns.push(`おやつが1日 ${formatKcal(treats)} で、目安の10%（${formatKcal(target * TREAT_LIMIT_RATIO)}）を超えています。`);
  const foods = list('foods');
  return card(`🍚 ${esc(pet.name)} のごはんプラン`, `
      <div class="row"><span class="grow">1日の目安</span><b>${formatKcal(target)}</b></div>
      ${lines.length ? lines.map((ln) => `
        <button class="plain row rec" data-act="edit-plan" data-id="${ln.item.id}">
          <span class="grow"><b>${ln.food.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(ln.food.name)}</b>
            <span class="small muted" style="display:block">${cycleText(ln.item.timesPerCycle, ln.item.cycleDays)}・1回 ${formatAmount(ln.amount)} ${esc(ln.food.unitLabel)}${ln.item.auto && ln.food.divisible ? '（自動）' : ''}</span></span>
          <span class="small">1日 ${formatKcal(ln.dailyKcal)}</span></button>`).join('')
        : '<p class="hint">まだプランがありません。フードを登録して、あげる回数と量を決めましょう。</p>'}
      ${lines.length ? `<div class="row rec"><span class="grow label">合計</span><b class="${total > target * 1.1 ? 'error' : 'accent'}">${formatKcal(total)} ／ ${formatKcal(target)}</b></div>` : ''}
      ${warns.map((w) => `<p class="warn small">⚠ ${esc(w)}</p>`).join('')}
      <div class="row wrap">${foods.length ? '<button class="btn" data-act="add-plan">＋ プランに追加</button>' : ''}
        <button class="btn ghost" data-act="add-food">＋ フードを登録</button></div>
      <p class="hint small">「自動」にしたフードは、目安のカロリーから決まった量を引いた残りで1回量を計算します（g で量れるフードのみ）。登録したフードの一覧は「設定」にあります。</p>`, 'pink');
}

function planDialog(pet, editing) {
  const foods = list('foods').sort((a, b) => (a.kind === b.kind ? a.id - b.id : a.kind === 'MEAL' ? -1 : 1));
  const it = editing || { id: newId(), petId: pet.id, foodId: foods[0]?.id, auto: !!foods[0]?.divisible, amount: 0, timesPerCycle: 2, cycleDays: 1 };
  openDialog(`
    <h2>${editing ? 'プランを直す' : 'ごはんプランに追加'}</h2>
    <label class="field">フード<select name="food">${foods.map((f) =>
      `<option value="${f.id}" ${f.id === it.foodId ? 'selected' : ''}>${f.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(f.name)}</option>`).join('')}</select></label>
    <div class="label">あげる回数</div>
    <div class="row wrap">${CYCLE_PRESETS.map(([l, t, d]) =>
      `<button type="button" class="chip ${it.timesPerCycle === t && it.cycleDays === d ? 'on' : ''}" data-preset="${t},${d}">${l}</button>`).join('')}</div>
    <div class="row gap"><label class="field">何日に<input name="days" type="number" min="1" step="1" required value="${it.cycleDays}"></label>
      <label class="field">何回<input name="times" type="number" min="1" step="1" required value="${it.timesPerCycle}"></label></div>
    <label class="check" id="auto-row"><input type="checkbox" name="auto" ${it.auto ? 'checked' : ''}>1回の量を目安のカロリーから自動で決める</label>
    <label class="field" id="amount-row">1回の量（<span id="plan-unit"></span>）<input name="amount" type="number" inputmode="decimal" step="any" min="0" value="${it.amount || ''}"></label>
    ${editing ? '<button type="button" class="link muted" data-act="del-plan">このプランを外す</button>' : ''}`,
  (fd) => {
    const food = foodById(fd.get('food'));
    if (!food) { toast('フードを選んでください'); return false; }
    const auto = food.divisible && fd.get('auto') === 'on';
    const amount = Number(fd.get('amount'));
    if (!auto && !(amount > 0)) { toast('1回の量を入れてください'); return false; }
    const days = Math.max(1, Math.round(Number(fd.get('days')) || 1));
    const times = Math.max(1, Math.round(Number(fd.get('times')) || 1));
    put('plans', { ...it, foodId: food.id, auto, amount: auto ? 0 : amount, timesPerCycle: times, cycleDays: days });
    return true;
  });
  const box = $('#dialog');
  const sync = () => {
    const f = foodById(box.querySelector('select[name=food]').value);
    box.querySelector('#plan-unit').textContent = f?.unitLabel || '';
    box.querySelector('#auto-row').hidden = !f?.divisible;
    box.querySelector('#amount-row').hidden = !!f?.divisible && box.querySelector('input[name=auto]').checked;
  };
  box.querySelector('select[name=food]').addEventListener('change', sync);
  box.querySelector('input[name=auto]').addEventListener('change', sync);
  box.querySelectorAll('[data-preset]').forEach((b) => b.addEventListener('click', () => {
    const [t, d] = b.dataset.preset.split(',');
    box.querySelector('input[name=times]').value = t;
    box.querySelector('input[name=days]').value = d;
    box.querySelectorAll('[data-preset]').forEach((o) => o.classList.toggle('on', o === b));
  }));
  box.querySelector('[data-act=del-plan]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    closeDialog();
    remove('plans', it);
    toast('外しました', '元に戻す', () => put('plans', it));
  });
  sync();
}

function foodDialog(editing) {
  const f = editing || { id: newId(), name: '', kind: 'MEAL', divisible: true, unitLabel: 'g', baseAmount: 100, kcalPerBase: 0 };
  openDialog(`
    <h2>${editing ? 'フードを直す' : 'フードを登録'}</h2>
    <label class="field">名前<input name="name" required value="${esc(f.name)}" placeholder="例：〇〇 室内猫用"></label>
    <div class="row wrap">
      <label class="chip"><input type="radio" name="kind" value="MEAL" ${f.kind !== 'TREAT' ? 'checked' : ''}>🍚 ごはん</label>
      <label class="chip"><input type="radio" name="kind" value="TREAT" ${f.kind === 'TREAT' ? 'checked' : ''}>🍪 おやつ</label></div>
    <div class="row wrap">
      <label class="chip"><input type="radio" name="div" value="1" ${f.divisible ? 'checked' : ''}>g で量る（カリカリなど）</label>
      <label class="chip"><input type="radio" name="div" value="0" ${f.divisible ? '' : 'checked'}>個数で数える（パウチ・缶など）</label></div>
    <label class="field" id="pack-row">数え方<select name="unit">${PACK_UNITS.map((u) => `<option ${f.unitLabel === u ? 'selected' : ''}>${u}</option>`).join('')}</select></label>
    <div class="row gap"><label class="field"><span id="base-label"></span><input name="base" type="number" inputmode="decimal" step="any" min="0" required value="${f.baseAmount}"></label>
      <label class="field">あたりのカロリー（kcal）<input name="kcal" type="number" inputmode="decimal" step="any" min="0" required value="${f.kcalPerBase || ''}"></label></div>
    <p class="hint small">パッケージの「100g あたり ○kcal」「1袋あたり ○kcal」をそのまま入れてください。</p>
    ${editing ? '<button type="button" class="link muted" data-act="del-food">このフードを削除</button>' : ''}`,
  (fd) => {
    const name = String(fd.get('name') || '').trim();
    const divisible = fd.get('div') === '1';
    const baseAmount = Number(fd.get('base'));
    const kcalPerBase = Number(fd.get('kcal'));
    if (!name) { toast('名前を入れてください'); return false; }
    if (!(baseAmount > 0) || !(kcalPerBase >= 0)) { toast('カロリーを入れてください'); return false; }
    put('foods', { ...f, name, kind: fd.get('kind') || 'MEAL', divisible, unitLabel: divisible ? 'g' : fd.get('unit') || '袋', baseAmount, kcalPerBase });
    return true;
  });
  const box = $('#dialog');
  const sync = () => {
    const div = box.querySelector('input[name=div]:checked')?.value === '1';
    box.querySelector('#pack-row').hidden = div;
    box.querySelector('#base-label').textContent = div ? '量（g）' : `数（${box.querySelector('select[name=unit]').value}）`;
  };
  box.querySelectorAll('input[name=div]').forEach((r) => r.addEventListener('change', () => {
    box.querySelector('input[name=base]').value = r.value === '1' ? 100 : 1;
    sync();
  }));
  box.querySelector('select[name=unit]').addEventListener('change', sync);
  box.querySelector('[data-act=del-food]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    const used = list('plans').filter((p) => p.foodId === f.id);
    if (!confirm(`「${f.name}」を削除しますか？${used.length ? `\nこのフードを使っているごはんプラン（${used.length}件）も外れます。` : ''}\n（あげた記録は残ります）`)) return;
    closeDialog();
    used.forEach((p) => remove('plans', p));
    remove('foods', f);
  });
  sync();
}

// ---------------- うちの子 ----------------

const TREND_RANGES = { MONTH: ['1か月', 31], QUARTER: ['3か月', 92], YEAR: ['1年', 366], ALL: ['すべて', null] };
const rangeFrom = (r) => (TREND_RANGES[r][1] ? dateKey(addDays(new Date(), -(TREND_RANGES[r][1] - 1))) : null);
const inRangeKey = (key, from) => (!from || key >= from) && key <= dateKey(new Date());
const rangeChips = (act, cur) => `<div class="row wrap">${Object.entries(TREND_RANGES).map(([k, [l]]) =>
  `<button class="chip ${cur === k ? 'on' : ''}" data-act="${act}" data-range="${k}">${l}</button>`).join('')}</div>`;
const avg = (a) => (a.length ? a.reduce((s, v) => s + v, 0) / a.length : 0);
const MOOD_SCORE = { GOOD: 3, SO_SO: 2, BAD: 1 };

/** 1日ごとの値（記録のない日は 0）。記録を始める前の日は含めない（アプリと同じ） */
function dailySeries(byDay, from) {
  const today = dateKey(new Date());
  const keys = Object.keys(byDay).filter((k) => inRangeKey(k, from));
  if (!keys.length) return null;
  const min = Object.keys(byDay).sort()[0];
  let d = parseDate(from && from > min ? from : min);
  const out = [];
  while (dateKey(d) <= today) { out.push([dateKey(d), byDay[dateKey(d)] || 0]); d = addDays(d, 1); }
  return out;
}
const countBy = (arr, f, v = () => 1) => arr.reduce((m, x) => { const k = f(x); m[k] = (m[k] || 0) + v(x); return m; }, {});
const dayOf = (ms) => dateKey(new Date(ms));

function topPhoto(p, size, cls = 'pet-photo') {
  const url = iconIsPhoto(p) && p.photoId ? photoUrl(p.photoId) : null;
  return url ? `<img class="${cls}" style="width:${size}px;height:${size}px" src="${url}" alt="">`
    : `<span class="${cls}" style="width:${size}px;height:${size}px;font-size:${Math.round(size * 0.55)}px;background:${petColor(p)}">${petIcon(p)}</span>`;
}

function petTabView() {
  const all = pets();
  const pet = all.find((p) => p.id === S.petView);
  if (!pet) {
    const today = dateKey(new Date());
    const size = prefs.petPhotoSize();
    return card(`うちの子たち（${all.length}）`, all.map((p) => {
      const feeds = list('feedLogs').filter((l) => l.petId === p.id && dayOf(l.time) === today);
      const toilets = list('excretionLogs').filter((l) => l.petId === p.id && dayOf(l.time) === today);
      const mood = S.data.moods.get(`${p.id}_${today}`);
      return `<button class="plain row rec pet-row" data-act="pet-open" data-id="${p.id}">${topPhoto(p, size)}
        <span class="grow"><b class="name">${esc(p.name)}</b>
          <span class="small muted" style="display:block">${SPECIES[p.species]?.label || ''}${p.breed ? `・${esc(p.breed)}` : ''}・${ageText(p)}・${formatAmount(p.weightKg)} kg</span>
          ${p.adoptionDate ? `<span class="small muted" style="display:block">お迎えから ${durationText(p.adoptionDate)}</span>` : ''}
          <span class="small" style="display:block">今日 🍚${Math.round(feeds.reduce((s, l) => s + l.kcal, 0))}/${Math.round(targetKcal(p))}kcal
            💩${toilets.filter((t) => t.type === 'POOP').length} 💧${toilets.filter((t) => t.type === 'PEE').length}${mood ? ` ${MOODS[mood.mood]?.emoji || ''}` : ''}</span></span>
        <span class="chev">›</span></button>`;
    }).join('') + '<p class="hint small">タップすると、その子のプロフィールと記録の推移が見られます。新しい子の登録や並べ替えは「設定」からできます。</p>'
      + '<button class="btn ghost" data-act="add-pet">＋ 新しい子を登録</button>');
  }
  const head = `<div class="row pet-head"><button class="link" data-act="pet-back">‹ 一覧へ</button>
    <div class="chips grow">${all.map((p) => `<button class="avatar-btn ${p.id === pet.id ? 'on' : ''}" data-act="pet-open" data-id="${p.id}" title="${esc(p.name)}">${avatarTag(p)}</button>`).join('')}</div></div>`;
  const section = {
    PET_PROFILE: () => {
      const top = pet.photoId ? photoUrl(pet.photoId) : null;
      return card('', `${top ? `<img class="top-photo" src="${top}" alt="" data-act="photo" data-id="${esc(pet.photoId)}">` : ''}
        <div class="row">${top ? '' : topPhoto(pet, 64)}<div class="grow">
          <div class="pet-name">${esc(pet.name)}</div>
          <div>${SPECIES[pet.species]?.emoji || ''} ${SPECIES[pet.species]?.label || ''}${pet.breed ? `・${esc(pet.breed)}` : ''}</div>
          <div>${SEX[pet.sex] || ''}・${pet.neutered ? '避妊・去勢済み' : '未去勢'}</div>
          <div>${ageText(pet)}・${formatAmount(pet.weightKg)} kg・BCS ${pet.bcs}（${BCS_TITLES[pet.bcs - 1] || ''}）</div>
          <div class="small muted">誕生日 ${slash(pet.birthDate)}${pet.birthEstimated ? '（推定）' : ''}${pet.adoptionDate ? `　お迎え ${slash(pet.adoptionDate)}（${durationText(pet.adoptionDate)}）` : ''}</div>
        </div></div>
        <button class="btn" data-act="edit-pet" data-id="${pet.id}">プロフィールを編集</button>`, 'pink');
    },
    PET_ABOUT: () => {
      const rows = [['性格', pet.personality], ['体の特徴', pet.physical], ['そのほか', pet.notes]].filter(([, v]) => v);
      return card(`💗 ${esc(pet.name)}のこと`, rows.length ? rows.map(([l, v]) => `<div><div class="label">${l}</div><p class="pre">${esc(v)}</p></div>`).join('')
        : '<p class="hint">性格や体の特徴は「プロフィールを編集」で書けます。</p>');
    },
    PET_TRENDS: () => trendsCard(pet),
    PET_DOCS: () => card('📎 書類', (pet.documents || []).length
      ? `<div class="row wrap">${pet.documents.map((d) => `<div class="doc">${photoTag(d.photoId)}<div class="small">${esc(d.title)}</div></div>`).join('')}</div>`
      : '<p class="hint">診断書・血統書などの画像は「プロフィールを編集」で追加できます。</p>'),
    PET_KCAL: () => kcalGuideCard(pet),
  };
  return head + prefs.visible('PET').map((k) => section[k]?.() || '').join('');
}

function kcalGuideCard(pet) {
  const e = energy(pet);
  const custom = pet.customKcal != null && pet.customKcal > 0;
  const basisNote = e.growing ? '成長期なので、いまの体重で計算しています。'
    : pet.targetWeightKg ? `目標体重 ${formatAmount(pet.targetWeightKg)} kg で計算しています。`
      : `BCS ${pet.bcs} から推定した理想体重 ${formatAmount(e.basis)} kg で計算しています。`;
  return card('🔥 1日のカロリーの目安', `
    <div class="kcal"><b>${Math.round(e.target)}</b> kcal</div>
    ${custom ? `<p class="small">手動で設定した値です（自動計算では ${formatKcal(e.calculated)}）</p>` : ''}
    <div class="label">計算の内訳</div>
    <p class="small">安静時に必要なカロリー（RER）= 70 × ${formatAmount(e.basis)}kg^0.75 = ${formatKcal(e.rer)}</p>
    <p class="small">係数 ${e.factor}（${esc(e.factorLabel)}）</p>
    <p class="small">1日の目安（MER）= ${formatKcal(e.rer)} × ${e.factor} = ${formatKcal(e.calculated)}</p>
    <p class="hint small">ライフステージ：${e.stageLabel}。${basisNote} 一般的な目安なので、体重の変化や獣医さんの指示に合わせて調整してください。</p>`, 'lemon');
}

function trendsCard(pet) {
  const metrics = [['WEIGHT', '⚖ 体重']];
  if (isOn('FOOD')) metrics.push(['KCAL', '🍚 カロリー']);
  if (isOn('POOP')) metrics.push(['POOP', '💩 うんちの回数']);
  if (isOn('PEE')) metrics.push(['PEE', '💧 おしっこの回数']);
  if (isOn('MOOD')) metrics.push(['MOOD', '☀️ 機嫌']);
  items().filter((k) => RECORD_ITEMS[k] && !RECORD_ITEMS[k].special).forEach((k) => metrics.push([`R_${k}`, `${RECORD_ITEMS[k].emoji} ${RECORD_ITEMS[k].label}`]));
  const key = metrics.some(([k]) => k === S.trendKey) ? S.trendKey : 'WEIGHT';
  const chips = `<div class="row wrap">${metrics.map(([k, l]) => `<button class="chip ${k === key ? 'on' : ''}" data-act="trend" data-key="${k}">${l}</button>`).join('')}</div>`;
  if (key === 'WEIGHT') return card('📈 記録の推移', chips + weightContent(pet));
  const from = rangeFrom(S.trendRange);
  let body = '';
  if (key === 'KCAL' || key === 'POOP' || key === 'PEE') {
    const byDay = key === 'KCAL'
      ? countBy(list('feedLogs').filter((l) => l.petId === pet.id), (l) => dayOf(l.time), (l) => l.kcal)
      : countBy(list('excretionLogs').filter((l) => l.petId === pet.id && l.type === key), (l) => dayOf(l.time));
    const pts = dailySeries(byDay, from);
    if (!pts) body = '<p class="hint">この期間の記録はありません。</p>';
    else {
      const target = targetKcal(pet);
      const a = avg(pts.map((p) => p[1]));
      body = lineChart([{ name: pet.name, color: lineColor(pet), points: pts }], { id: `trend-${key}`, unit: key === 'KCAL' ? 'kcal' : '回', goal: key === 'KCAL' ? target : null })
        + (key === 'KCAL' ? `<p>平均 ${formatAmount(a)} kcal（目安の${target > 0 ? Math.round(a / target * 100) : 0}%）</p><p class="hint small">点線は1日の目安です。</p>`
          : `<p>平均 ${formatAmount(a)} 回／日${(() => {
            const bad = list('excretionLogs').filter((l) => l.petId === pet.id && l.type === key && isAbnormal(l) && inRangeKey(dayOf(l.time), from)).length;
            return bad ? `・気になる記録 ${bad}件` : '';
          })()}</p>`);
    }
  } else if (key === 'MOOD') {
    const moods = list('moods').filter((m) => m.petId === pet.id && inRangeKey(m.date, from)).sort((a, b) => a.date.localeCompare(b.date));
    body = moods.length
      ? lineChart([{ name: pet.name, color: lineColor(pet), points: moods.map((m) => [m.date, MOOD_SCORE[m.mood] || 2]) }], { id: 'trend-MOOD' })
        + `<p class="hint small">${Object.values(MOODS).map((m, i) => `${m.emoji}${m.label} ${3 - i}`).join('・')} として表示しています。</p>
        <p>${Object.entries(MOODS).map(([k, m]) => `${m.emoji}${m.label} ${moods.filter((x) => x.mood === k).length}日`).join('　')}</p>`
      : '<p class="hint">この期間の機嫌の記録はありません。</p>';
  } else {
    const item = key.slice(2);
    const it = RECORD_ITEMS[item];
    const all = list('records').filter((r) => r.petId === pet.id && r.item === item).sort((a, b) => b.time - a.time);
    const logs = all.filter((r) => inRangeKey(dayOf(r.time), from));
    if (!logs.length) body = `<p class="hint">この期間の「${it.label}」の記録はありません。</p>`;
    else {
      if (it.kind === 'NUMBER') {
        const pts = logs.filter((r) => r.value != null).map((r) => [dayOf(r.time), r.value]).reverse();
        if (pts.length) body += lineChart([{ name: pet.name, color: lineColor(pet), points: pts }], { id: `trend-${key}`, unit: it.unit || '' })
          + `<p>平均 ${formatAmount(avg(pts.map((p) => p[1])))}${it.unit || ''}（${pts.length}回）</p>`;
      }
      const last = dayOf(all[0].time);
      const today = dateKey(new Date());
      body += `<p>${logs.length}回　最後は ${slash(last)}（${last === today ? '今日' : `${durationText(last)}前`}）</p>`
        + (S.showAllRecords ? logs : logs.slice(0, 8)).map((r) => `<button class="plain row rec" data-act="edit-record" data-id="${r.id}">
          <span class="grow">${slash(dayOf(r.time))} ${timeText(r.time)}</span><span class="grow">${esc(recordValueText(r)) || '✓'}</span></button>`).join('')
        + (logs.length > 8 && !S.showAllRecords ? `<button class="link" data-act="show-all-records">すべて表示（${logs.length}件）</button>` : '');
    }
  }
  return card('📈 記録の推移', chips + rangeChips('trend-range', S.trendRange) + body);
}

function weightContent(pet) {
  const all = list('weights').filter((w) => w.petId === pet.id).sort((a, b) => a.date.localeCompare(b.date));
  const from = rangeFrom(S.weightRange);
  const pts = all.filter((w) => inRangeKey(w.date, from));
  const goal = pet.targetWeightKg || null;
  let body = `<div class="row"><div class="grow"><span class="kcal"><b>${formatAmount(pet.weightKg)}</b> kg</span>
    ${goal ? `<span class="small muted">　目標 ${formatAmount(goal)} kg</span>` : ''}</div>
    <button class="btn" data-act="add-weight" data-id="${pet.id}">＋ 体重を記録</button></div>`;
  body += rangeChips('weight-range', S.weightRange);
  if (!pts.length) return `${body}<p class="hint">この期間の体重の記録はありません。</p>`;
  if (pts.length >= 2) {
    const d = pts[pts.length - 1].kg - pts[0].kg;
    body += `<p class="small">期間中 ${d > 0 ? '+' : d < 0 ? '−' : '±'}${formatAmount(Math.abs(d))} kg</p>`;
  }
  body += lineChart([{ name: pet.name, color: lineColor(pet), points: pts.map((w) => [w.date, w.kg]) }], { id: 'trend-WEIGHT', unit: 'kg', goal });
  if (goal) body += '<p class="hint small">点線は目標体重です。</p>';
  const rows = [...all].reverse();
  body += (S.showAllWeights ? rows : rows.slice(0, 8)).map((w, i) => {
    const prev = rows[i + 1];
    const diff = prev ? w.kg - prev.kg : null;
    return `<button class="plain row rec" data-act="edit-weight" data-id="${w.id}"><span class="grow">${slash(w.date)}${w.time ? ` ${w.time.slice(0, 5)}` : ''}</span>
      <b>${formatAmount(w.kg)} kg</b><span class="small muted diff">${diff == null ? '' : `${diff > 0 ? '+' : diff < 0 ? '−' : '±'}${formatAmount(Math.abs(diff))}`}</span></button>`;
  }).join('');
  if (rows.length > 8 && !S.showAllWeights) body += `<button class="link" data-act="show-all-weights">すべて表示（${rows.length}件）</button>`;
  return body;
}

/** 体重（1日1つ。いちばん新しい記録はプロフィールの体重にもなる） */
function syncLatestWeight(pet, changed, removed) {
  const ws = list('weights').filter((x) => x.petId === pet.id && x.id !== removed?.id && x.id !== changed?.id);
  if (changed) ws.push(changed);
  const latest = ws.sort((a, b) => a.date.localeCompare(b.date) || (a.time || '').localeCompare(b.time || '')).pop();
  const cur = S.data.pets.get(String(pet.id)) || pet;
  if (latest && cur.weightKg !== latest.kg) put('pets', { ...cur, weightKg: latest.kg });
}

// ---------------- うちの子の登録・編集 ----------------

function petEditor(editing) {
  const all = pets();
  const p = editing || {
    id: newId(), name: '', species: 'CAT', breed: '', sex: 'FEMALE', neutered: false, weightKg: 4,
    birthDate: dateKey(addDays(new Date(), -365)), birthEstimated: false, bcs: 3, targetWeightKg: null, customKcal: null,
    colorIndex: all.length % PET_COLORS.length, iconIndex: 0, adoptionDate: null, personality: '', physical: '', notes: '',
    order: all.reduce((m, x) => Math.max(m, x.order || 0), -1) + 1, photoId: null, photoAsIcon: true, iconMode: 'photo', documents: [],
  };
  let photoId = p.photoId;
  let documents = [...(p.documents || [])];
  const iconChips = (species, cur) => (SPECIES[species] || SPECIES.CAT).icons.map((ic, i) =>
    `<label class="chip"><input type="radio" name="icon" value="${i}" ${i === cur ? 'checked' : ''}>${ic}</label>`).join('');
  openDialog(`
    <h2>${editing ? `${esc(p.name)}のプロフィール` : '新しい子を登録'}</h2>
    <label class="field">名前<input name="name" required value="${esc(p.name)}"></label>
    <div class="row wrap">${Object.entries(SPECIES).map(([k, s]) =>
      `<label class="chip"><input type="radio" name="species" value="${k}" ${p.species === k ? 'checked' : ''}>${s.emoji} ${s.label}</label>`).join('')}</div>
    <label class="field">品種<input name="breed" value="${esc(p.breed)}" placeholder="例：ミックス、柴犬"></label>
    <div class="row wrap">${Object.entries(SEX).map(([k, l]) =>
      `<label class="chip"><input type="radio" name="sex" value="${k}" ${p.sex === k ? 'checked' : ''}>${l}</label>`).join('')}
      <label class="check"><input type="checkbox" name="neutered" ${p.neutered ? 'checked' : ''}>避妊・去勢済み</label></div>
    <div class="label">トップ画像</div>
    <div class="row" id="pe-photo">${photoId ? photoTag(photoId, 'thumb', false) : '<span class="thumb ph">🖼</span>'}
      <label class="btn ghost">📷 写真を選ぶ<input type="file" accept="image/*" id="pe-photo-input" hidden></label>
      <button type="button" class="link muted" id="pe-photo-del" ${photoId ? '' : 'hidden'}>写真を外す</button></div>
    <p class="hint small">正方形に切り抜いて保存します。家族共有でも送れるよう、小さめのサイズにします。</p>
    <div class="row wrap"><span class="label">アイコン</span>
      <label class="chip"><input type="radio" name="iconMode" value="photo" ${iconIsPhoto(p) ? 'checked' : ''}>写真（縮小版）</label>
      <label class="chip"><input type="radio" name="iconMode" value="emoji" ${iconIsPhoto(p) ? '' : 'checked'}>絵文字</label></div>
    <div class="row wrap" id="pe-icons">${iconChips(p.species, p.iconIndex || 0)}</div>
    <div class="row wrap">${PET_COLORS.map((c, i) =>
      `<label class="chip color"><input type="radio" name="color" value="${i}" ${i === (p.colorIndex || 0) ? 'checked' : ''}><i style="background:${c}"></i></label>`).join('')}</div>
    <p class="hint small">色は、写真が無いときのアイコンの背景とグラフの線に使います。</p>
    <div class="row gap"><label class="field">誕生日<input name="birth" type="date" required value="${p.birthDate}"></label>
      <label class="field">体重（kg）<input name="weight" type="number" inputmode="decimal" step="any" min="0" required value="${p.weightKg || ''}"></label></div>
    <label class="check"><input type="checkbox" name="estimated" ${p.birthEstimated ? 'checked' : ''}>誕生日はだいたい（年齢から逆算）</label>
    <label class="field">お迎えした日<input name="adoption" type="date" value="${p.adoptionDate || ''}"></label>
    <div class="label">体形（BCS）</div>
    <div class="row wrap">${BCS_TITLES.map((t, i) =>
      `<label class="chip"><input type="radio" name="bcs" value="${i + 1}" ${p.bcs === i + 1 ? 'checked' : ''}>${i + 1} ${t}</label>`).join('')}</div>
    <div class="row gap"><label class="field">目標体重（kg・任意）<input name="target" type="number" inputmode="decimal" step="any" min="0" value="${p.targetWeightKg ?? ''}"></label>
      <label class="field">1日のカロリー（任意）<input name="kcal" type="number" inputmode="numeric" step="1" min="0" value="${p.customKcal ?? ''}"></label></div>
    <p class="hint small">空欄なら、体重・年齢・去勢の有無・体形から自動で計算します。</p>
    <label class="field">性格（人なつこい・怖がり など）<textarea class="area" name="personality" rows="2">${esc(p.personality)}</textarea></label>
    <label class="field">体の特徴（模様・持病・アレルギー など）<textarea class="area" name="physical" rows="2">${esc(p.physical)}</textarea></label>
    <label class="field">そのほか（かかりつけ病院・苦手なこと など）<textarea class="area" name="notes" rows="2">${esc(p.notes)}</textarea></label>
    <div class="label">書類の画像（診断書・血統書など）</div>
    <div class="col" id="pe-docs"></div>
    <div class="row"><input class="copy grow" id="pe-doc-title" placeholder="書類の名前（例：血統書、ワクチン証明書）">
      <label class="btn ghost">📎 追加<input type="file" accept="image/*" id="pe-doc-input" hidden></label></div>
    <p class="hint small">文字がなんとか読めるくらいに軽くして保存します。</p>
    ${editing ? '<button type="button" class="link error" data-act="del-pet">この子の登録を削除</button>' : ''}`,
  (fd) => {
    const name = String(fd.get('name') || '').trim();
    const weightKg = Number(fd.get('weight'));
    if (!name) { toast('名前を入れてください'); return false; }
    if (!(weightKg > 0)) { toast('体重を入れてください'); return false; }
    const num = (k) => { const v = String(fd.get(k) || '').trim(); return v && Number(v) > 0 ? Number(v) : null; };
    const mode = fd.get('iconMode') || 'photo';
    const next = {
      ...p, name, species: fd.get('species') || 'CAT', breed: String(fd.get('breed') || '').trim(), sex: fd.get('sex') || 'FEMALE',
      neutered: fd.get('neutered') === 'on', weightKg, birthDate: fd.get('birth'), birthEstimated: fd.get('estimated') === 'on',
      adoptionDate: fd.get('adoption') || null, bcs: Number(fd.get('bcs')) || 3, targetWeightKg: num('target'),
      customKcal: num('kcal') ? Math.round(num('kcal')) : null, iconIndex: Number(fd.get('icon')) || 0, colorIndex: Number(fd.get('color')) || 0,
      personality: String(fd.get('personality') || '').trim(), physical: String(fd.get('physical') || '').trim(), notes: String(fd.get('notes') || '').trim(),
      photoId, photoAsIcon: mode === 'photo', iconMode: mode, documents,
    };
    put('pets', next);
    if (!editing) {
      put('weights', { id: newId(), petId: next.id, date: dateKey(new Date()), kg: weightKg, time: null });
      S.petId = next.id; store.set('petId', String(next.id));
      toast(`${name}を登録しました`);
    } else if (editing.weightKg !== weightKg) {
      // プロフィールで体重を直したときは、今日の体重としても残す（アプリと同じ）
      const today = dateKey(new Date());
      const same = list('weights').find((w) => w.petId === p.id && w.date === today);
      put('weights', { id: same?.id || newId(), petId: p.id, date: today, kg: weightKg, time: same?.time || null });
    }
    return true;
  });
  const box = $('#dialog');
  const drawDocs = () => {
    box.querySelector('#pe-docs').innerHTML = documents.map((d, i) => `<div class="row">${photoTag(d.photoId, 'thumb', false)}
      <span class="grow">${esc(d.title)}</span><button type="button" class="link muted" data-doc="${i}">外す</button></div>`).join('');
    box.querySelectorAll('[data-doc]').forEach((b) => b.addEventListener('click', () => { documents = documents.filter((_, i) => i !== Number(b.dataset.doc)); drawDocs(); }));
  };
  drawDocs();
  box.querySelectorAll('input[name=species]').forEach((r) => r.addEventListener('change', () => {
    box.querySelector('#pe-icons').innerHTML = iconChips(r.value, 0);
  }));
  const upload = async (file, maxSide, quality, square) => {
    const b64 = await compressPhoto(file, maxSide, quality, square);
    const id = String(newId());
    await backend.putPhoto(S.familyId, id, b64);
    photoCache.set(id, `data:image/jpeg;base64,${b64}`);
    return id;
  };
  box.querySelector('#pe-photo-input').addEventListener('change', async (ev) => {
    const file = ev.target.files?.[0];
    if (!file) return;
    try {
      photoId = await upload(file, 512, 0.7, true);
      box.querySelector('#pe-photo').firstElementChild.outerHTML = photoTag(photoId, 'thumb', false);
      box.querySelector('#pe-photo-del').hidden = false;
    } catch { toast('写真を送れませんでした'); }
  });
  box.querySelector('#pe-photo-del').addEventListener('click', (ev) => {
    photoId = null;
    box.querySelector('#pe-photo').firstElementChild.outerHTML = '<span class="thumb ph">🖼</span>';
    ev.target.hidden = true;
  });
  box.querySelector('#pe-doc-input').addEventListener('change', async (ev) => {
    const file = ev.target.files?.[0];
    if (!file) return;
    try {
      const id = await upload(file, 1400, 0.6, false);
      const title = box.querySelector('#pe-doc-title').value.trim() || '書類';
      documents = [...documents, { photoId: id, title }];
      box.querySelector('#pe-doc-title').value = '';
      drawDocs();
    } catch { toast('画像を送れませんでした'); }
  });
  box.querySelector('[data-act=del-pet]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    if (!confirm(`${p.name}の登録を削除しますか？\nごはん・トイレ・体重などの記録もすべて消え、家族の端末からも消えます。元に戻せません。`)) return;
    closeDialog();
    for (const c of COLLECTIONS) {
      if (c === 'pets' || c === 'settings' || c === 'foods') continue;
      list(c).filter((x) => x.petId === p.id).forEach((x) => remove(c, x));
    }
    remove('pets', p);
    if (S.petView === p.id) S.petView = null;
    if (S.petId === p.id) S.petId = null;
    render();
    toast(`${p.name}の登録を削除しました`);
  });
}

// ---------------- まとめ（ダッシュボード） ----------------

function dashboardView() {
  const all = pets();
  const scoped = all.filter((p) => !S.dashScope || p.id === S.dashScope);
  const ps = scoped.length ? scoped : all;
  const from = rangeFrom(S.dashRange);
  const head = card('📊 まとめ', `
    <div class="row wrap"><button class="chip ${!S.dashScope ? 'on' : ''}" data-act="dash-scope" data-id="0">全員</button>
      ${all.map((p) => `<button class="chip ${S.dashScope === p.id ? 'on' : ''}" data-act="dash-scope" data-id="${p.id}">${esc(p.name)}</button>`).join('')}</div>
    ${rangeChips('dash-range', S.dashRange)}
    <p class="hint small">グラフをタップすると、その日の値が出ます。出す項目と順番、下のタブに出すかどうかは「設定」の「画面の表示」で変えられます。</p>`);
  const metric = (id, title, series, unit, summary) => card(title, series.length
    ? lineChart(series, { id, unit }) + series.map((s) => `<div class="row small"><i class="sw" style="background:${s.color}"></i>${esc(summary(s))}</div>`).join('')
    : '<p class="hint">この期間の記録はありません。</p>');
  const perPetRows = (f) => ps.map((p) => `<div class="row">${avatarTag(p)}<b class="grow">${esc(p.name)}</b><span>${f(p)}</span></div>`).join('');

  const section = {
    DASH_EXPENSE: () => {
      const l = list('expenses').filter((e) => inRangeKey(e.date, from));
      const total = l.reduce((s, e) => s + e.amount, 0);
      const common = l.filter((e) => e.petId == null).reduce((s, e) => s + e.amount, 0);
      let body = `<div class="row wrap">${ps.map((p) => `<span class="pill k-pink">${esc(p.name)} ${yen(l.filter((e) => e.petId === p.id).reduce((s, e) => s + e.amount, 0))}</span>`).join('')}
        ${common ? `<span class="pill k-mint">みんなの分 ${yen(common)}</span>` : ''}<span class="pill k-lemon">全体 ${yen(total)}</span></div>`;
      if (!l.length) return card('💴 支出', `${body}<p class="hint">この期間の支出はありません。カレンダーの「💴 支出」から記録できます。</p>`);
      const monthly = S.dashRange !== 'MONTH';
      const keyOf = (d) => (monthly ? `${d.slice(0, 8)}01` : d);
      const fill = (byKey) => {
        const keys = Object.keys(byKey).sort();
        const out = [];
        let d = parseDate(keys[0]);
        while (dateKey(d) <= keys[keys.length - 1]) {
          out.push([dateKey(d), byKey[dateKey(d)] || 0]);
          d = monthly ? new Date(d.getFullYear(), d.getMonth() + 1, 1) : addDays(d, 1);
        }
        return out;
      };
      const series = [];
      for (const p of ps) {
        const byKey = countBy(l.filter((e) => e.petId === p.id), (e) => keyOf(e.date), (e) => e.amount);
        if (Object.keys(byKey).length) series.push({ name: p.name, color: lineColor(p), points: fill(byKey) });
      }
      if (!S.dashScope) series.push({ name: '全体', color: TOTAL_COLOR, points: fill(countBy(l, (e) => keyOf(e.date), (e) => e.amount)) });
      if (series.length) body += lineChart(series, { id: 'dash-expense', unit: '円', height: 200 });
      body += `<p class="hint small">${monthly ? '月ごとの合計です。' : '日ごとの合計です（記録のない日は 0 円）。'}</p>`;
      const byCat = Object.entries(countBy(l.filter((e) => !S.dashScope || e.petId === S.dashScope), (e) => e.category, (e) => e.amount)).sort((a, b) => b[1] - a[1]);
      if (byCat.length) body += `<p class="small muted">${byCat.map(([c, v]) => `${EXPENSE_CATS[c]?.emoji || ''}${EXPENSE_CATS[c]?.label || ''} ${yen(v)}`).join('　')}</p>`;
      return card('💴 支出', body);
    },
    DASH_WEIGHT: () => {
      const series = ps.map((p) => ({ name: p.name, color: lineColor(p), points: list('weights').filter((w) => w.petId === p.id && inRangeKey(w.date, from)).sort((a, b) => a.date.localeCompare(b.date)).map((w) => [w.date, w.kg]) }))
        .filter((s) => s.points.length);
      return metric('dash-weight', '⚖ 体重', series, 'kg', (s) => {
        const d = s.points[s.points.length - 1][1] - s.points[0][1];
        return `${s.name} ${formatAmount(s.points[s.points.length - 1][1])} kg（${s.points.length < 2 ? '記録1回' : `期間中 ${d > 0 ? '+' : d < 0 ? '−' : '±'}${formatAmount(Math.abs(d))}`}）`;
      });
    },
    DASH_KCAL: () => {
      if (!isOn('FOOD')) return '';
      const series = ps.map((p) => ({ p, name: p.name, color: lineColor(p), points: dailySeries(countBy(list('feedLogs').filter((l) => l.petId === p.id), (l) => dayOf(l.time), (l) => l.kcal), from) }))
        .filter((s) => s.points);
      return metric('dash-kcal', '🍚 1日のカロリー', series, 'kcal', (s) => {
        const a = avg(s.points.map((x) => x[1]));
        const t = targetKcal(s.p);
        return `${s.name} 平均 ${formatAmount(a)} kcal（目安の${t > 0 ? Math.floor(a / t * 100) : 0}%）`;
      });
    },
    DASH_POOP: () => toiletMetric('POOP'),
    DASH_PEE: () => toiletMetric('PEE'),
    DASH_MOOD: () => (isOn('MOOD') ? card('☀️ 機嫌', perPetRows((p) => {
      const moods = list('moods').filter((m) => m.petId === p.id && inRangeKey(m.date, from));
      return moods.length ? Object.entries(MOODS).map(([k, m]) => `${m.emoji}${moods.filter((x) => x.mood === k).length}`).join('　') : '記録なし';
    })) : ''),
    DASH_RECORDS: () => items().filter((k) => RECORD_ITEMS[k] && !RECORD_ITEMS[k].special).map((k) => {
      const it = RECORD_ITEMS[k];
      if (it.kind === 'NUMBER') {
        const series = ps.map((p) => ({ name: p.name, color: lineColor(p), points: list('records').filter((r) => r.petId === p.id && r.item === k && r.value != null && inRangeKey(dayOf(r.time), from)).sort((a, b) => a.time - b.time).map((r) => [dayOf(r.time), r.value]) }))
          .filter((s) => s.points.length);
        return metric(`dash-${k}`, `${it.emoji} ${it.label}`, series, it.unit || '', (s) => `${s.name} 平均 ${formatAmount(avg(s.points.map((x) => x[1])))}${it.unit || ''}（${s.points.length}回）`);
      }
      return card(`${it.emoji} ${it.label}`, perPetRows((p) => {
        const all = list('records').filter((r) => r.petId === p.id && r.item === k);
        const n = all.filter((r) => inRangeKey(dayOf(r.time), from)).length;
        const last = all.length ? dayOf(Math.max(...all.map((r) => r.time))) : null;
        return `${n}回${last ? `・最後 ${slash(last)}` : ''}`;
      }));
    }).join(''),
  };
  function toiletMetric(type) {
    if (!isOn(type)) return '';
    const e = EXCRETION[type];
    const series = ps.map((p) => ({ p, name: p.name, color: lineColor(p), points: dailySeries(countBy(list('excretionLogs').filter((l) => l.petId === p.id && l.type === type), (l) => dayOf(l.time)), from) }))
      .filter((s) => s.points);
    return metric(`dash-${type}`, `${e.emoji} ${e.label}の回数（1日）`, series, '回', (s) => {
      const bad = list('excretionLogs').filter((l) => l.petId === s.p.id && l.type === type && isAbnormal(l) && inRangeKey(dayOf(l.time), from)).length;
      return `${s.name} 平均 ${formatAmount(avg(s.points.map((x) => x[1])))} 回${bad ? `・気になる記録 ${bad}件` : ''}`;
    });
  }
  return head + prefs.visible('DASHBOARD').map((k) => section[k]?.() || '').join('');
}

// ---------------- 設定 ----------------

const AUTO_SORTS = [
  ['犬が先', (a, b) => (a.species === 'DOG' ? 0 : 1) - (b.species === 'DOG' ? 0 : 1) || a.birthDate.localeCompare(b.birthDate)],
  ['猫が先', (a, b) => (a.species === 'CAT' ? 0 : 1) - (b.species === 'CAT' ? 0 : 1) || a.birthDate.localeCompare(b.birthDate)],
  ['年上から', (a, b) => a.birthDate.localeCompare(b.birthDate)],
  ['年下から', (a, b) => b.birthDate.localeCompare(a.birthDate)],
  ['お迎え順', (a, b) => (a.adoptionDate || '9999').localeCompare(b.adoptionDate || '9999') || a.id - b.id],
  ['名前順', (a, b) => a.name.localeCompare(b.name, 'ja')],
];
const FONT_SCALES = [['小さめ', 0.9], ['ふつう', 1], ['大きめ', 1.15], ['とても大きく', 1.3]];
const CAL_SIZES = [['低め', 0.85], ['ふつう', 1], ['高め', 1.2], ['とても高く', 1.4]];
const PHOTO_SIZES = [['小', 56], ['中', 72], ['大', 96], ['特大', 128]];

const sharedSettings = () => S.data.settings.get('app') || { items: DEFAULT_ITEMS };
const putSettings = (patch) => put('settings', { ...sharedSettings(), ...patch });

function reorderPets(ordered) {
  ordered.forEach((p, i) => { if ((p.order || 0) !== i) put('pets', { ...p, order: i }); });
}

function settingsView() {
  const all = pets();
  const st = sharedSettings();
  const times = st.mealTimes || ['07:00', '12:00', '18:00', '22:00'];
  const scr = S.layoutScreen;
  const lay = prefs.layout(scr);
  const hid = prefs.hidden();
  let html = '';

  html += card('🐾 うちの子の管理', all.map((p, i) => `
      <div class="rec col"><div class="row">${avatarTag(p)}<div class="grow"><b>${esc(p.name)}</b>
        <div class="small muted">${SPECIES[p.species]?.label || ''}・${ageText(p)}</div></div>
        <button class="link" data-act="pet-move" data-id="${p.id}" data-d="-1" ${i ? '' : 'disabled'}>▲</button>
        <button class="link" data-act="pet-move" data-id="${p.id}" data-d="1" ${i < all.length - 1 ? '' : 'disabled'}>▼</button>
        <button class="btn ghost" data-act="edit-pet" data-id="${p.id}">編集</button></div>
        <div class="row wrap small"><span class="muted">アイコン</span>
          <button class="chip ${iconIsPhoto(p) ? '' : 'on'}" data-act="icon-mode" data-id="${p.id}" data-mode="emoji">${petIcon(p)} 絵文字</button>
          ${p.photoId ? `<button class="chip ${iconIsPhoto(p) ? 'on' : ''}" data-act="icon-mode" data-id="${p.id}" data-mode="photo">🖼 写真（縮小版）</button>` : '<span class="muted">写真は「編集」で登録できます</span>'}</div></div>`).join('') + `
      <button class="btn" data-act="add-pet">＋ 新しい子を登録</button>
      ${all.length > 1 ? `<div class="label">自動で並べる</div><div class="row wrap">${AUTO_SORTS.map(([l], i) => `<button class="chip" data-act="pet-sort" data-i="${i}">${l}</button>`).join('')}</div>` : ''}`);

  const foods = list('foods').sort((a, b) => (a.kind === b.kind ? a.id - b.id : a.kind === 'MEAL' ? -1 : 1));
  html += card('🍚 フードの登録', (foods.length ? foods.map((f) => `<button class="plain row rec" data-act="edit-food" data-id="${f.id}">
      <span class="grow">${f.kind === 'TREAT' ? '🍪' : '🍚'} ${esc(f.name)}</span>
      <span class="small muted">${formatAmount(f.baseAmount)}${esc(f.unitLabel)}あたり ${formatKcal(f.kcalPerBase)}</span></button>`).join('')
    : '<p class="hint">まだフードがありません。</p>') + '<button class="btn ghost" data-act="add-food">＋ フードを登録</button>');

  html += card('⏰ ごはんの時刻', `<p class="hint small">カレンダーのワンタップ記録で選べる時刻です。家族みんなで共通です。</p>
      ${times.map((t, i) => `<div class="row"><b class="meal">${mealLabel(Number(t.split(':')[0]))}</b>
        <input type="time" class="time-input grow" value="${esc(t.slice(0, 5))}" data-change="meal-time" data-i="${i}">
        <button class="link muted" data-act="meal-del" data-i="${i}">外す</button></div>`).join('')}
      ${times.length < 8 ? '<button class="btn ghost" data-act="meal-add">＋ 時刻を追加</button>' : ''}
      <div class="label">ワンタップ記録で最初に選ばれている時刻</div>
      <div class="row wrap"><button class="chip ${st.quickAtMealTime ? '' : 'on'}" data-act="meal-quick" data-v="0">いまの時刻</button>
        <button class="chip ${st.quickAtMealTime ? 'on' : ''}" data-act="meal-quick" data-v="1">いちばん近いごはんの時刻</button></div>`);

  html += card('📝 記録する項目', `<p class="hint small">「きょう」「カレンダー」に出す記録です。家族みんなで共通です。</p>
      <div class="col">${Object.entries(RECORD_ITEMS).map(([k, it]) =>
        `<label class="check"><input type="checkbox" data-act="item-toggle" data-key="${k}" ${isOn(k) ? 'checked' : ''}>${it.emoji} ${it.label}</label>`).join('')}</div>`);

  const pdf = S.pdf || (S.pdf = { petId: all[0]?.id, from: dateKey(addDays(new Date(), -30)), to: dateKey(new Date()), sections: PDF_SECTIONS.filter((s) => s[2]).map((s) => s[0]) });
  if (all.length) {
    html += card('📄 PDF形式で出力（提出などに）', `
      <div class="row wrap">${all.map((p) => `<button class="chip ${pdf.petId === p.id ? 'on' : ''}" data-act="pdf-pet" data-id="${p.id}">${esc(p.name)}</button>`).join('')}</div>
      <div class="label">期間</div>
      <div class="row gap"><input type="date" class="time-input" value="${pdf.from}" data-change="pdf-from"><span style="flex:none">〜</span>
        <input type="date" class="time-input" value="${pdf.to}" data-change="pdf-to"></div>
      <div class="label">入れる内容</div>
      <div class="col">${PDF_SECTIONS.map(([k, l]) => `<label class="check"><input type="checkbox" data-act="pdf-sec" data-key="${k}" ${pdf.sections.includes(k) ? 'checked' : ''}>${l}</label>`).join('')}</div>
      <button class="btn" data-act="pdf-print">印刷画面を開く</button>
      <p class="hint small">印刷画面の「送信先」で「PDF に保存」を選ぶと PDF になります（iPhone は共有ボタン →「プリント」から）。</p>`);
  }

  html += card('🔤 文字の大きさ', `<div class="row wrap">${FONT_SCALES.map(([l, v]) =>
    `<button class="chip ${prefs.fontScale() === v ? 'on' : ''}" data-act="font" data-v="${v}">${l}</button>`).join('')}</div>
    <p class="hint small">この端末（ブラウザ）だけの設定です。</p>`);

  html += card('🧩 画面の表示', `<p class="hint small">画面ごとに、出す項目と順番を変えられます。この端末（ブラウザ）だけの設定です。</p>
    <div class="row wrap">${prefs.SCREENS.map(([k, l]) => `<button class="chip ${scr === k ? 'on' : ''}" data-act="layout-screen" data-s="${k}">${l}</button>`).join('')}</div>
    ${lay.map((key, i) => {
      const [, , label, canHide] = prefs.sectionInfo(key);
      return `<div class="row rec">${canHide ? `<label class="check grow"><input type="checkbox" data-act="layout-show" data-key="${key}" ${hid.has(key) ? '' : 'checked'}>${label}</label>`
        : `<span class="grow">　 ${label}（いつも表示）</span>`}
        <button class="link" data-act="layout-move" data-key="${key}" data-d="-1" ${i ? '' : 'disabled'}>▲</button>
        <button class="link" data-act="layout-move" data-key="${key}" data-d="1" ${i < lay.length - 1 ? '' : 'disabled'}>▼</button></div>`;
    }).join('')}
    ${scr === 'CALENDAR' ? `<div class="label">カレンダーのマスの高さ</div><div class="row wrap">${CAL_SIZES.map(([l, v]) =>
      `<button class="chip ${prefs.calendarSize() === v ? 'on' : ''}" data-act="cal-size" data-v="${v}">${l}</button>`).join('')}</div>` : ''}
    ${scr === 'PET' ? `<div class="label">「うちの子」一覧の写真の大きさ</div><div class="row wrap">${PHOTO_SIZES.map(([l, v]) =>
      `<button class="chip ${prefs.petPhotoSize() === v ? 'on' : ''}" data-act="photo-size" data-v="${v}">${l}</button>`).join('')}</div>` : ''}
    <button class="link muted" data-act="layout-reset">並び・表示を最初の状態に戻す</button>`);

  html += familyView();
  html += '<p class="hint small center">通知（リマインダー）とバックアップは Android アプリの機能です。</p>';
  return html;
}

// ---------------- PDF（印刷） ----------------

async function printReport() {
  const o = S.pdf;
  const pet = S.data.pets.get(String(o.petId));
  if (!pet) return;
  const has = (k) => o.sections.includes(k);
  const inP = (key) => key >= o.from && key <= o.to;
  const toilets = list('excretionLogs').filter((l) => l.petId === pet.id && inP(dayOf(l.time))).sort((a, b) => a.time - b.time);
  // 写真は先に読み込んでおく
  const ids = [];
  if (has('PHOTO') && pet.photoId) ids.push(pet.photoId);
  if (has('DOCUMENTS')) (pet.documents || []).forEach((d) => ids.push(d.photoId));
  if (has('TOILET_PHOTOS')) toilets.forEach((l) => ids.push(...(l.photoIds || [])));
  toast('印刷の準備をしています…');
  await Promise.all(ids.filter((id) => !photoUrl(id)).map((id) => backend.getPhoto(S.familyId, id).then((b) => { if (b) photoCache.set(id, `data:image/jpeg;base64,${b}`); }, () => {})));
  const img = (id, cls) => (photoCache.get(id)?.startsWith?.('data:') ? `<img class="${cls}" src="${photoCache.get(id)}" alt="">` : '');
  const table = (head, rows) => (rows.length ? `<table><tr>${head.map((h) => `<th>${h}</th>`).join('')}</tr>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</table>` : '<p>記録はありません。</p>');
  const sec = (title, body) => `<section><h2>${title}</h2>${body}</section>`;
  let h = `<h1>${esc(pet.name)} の記録</h1><p class="sub">${slash(o.from)} 〜 ${slash(o.to)}　（${slash(dateKey(new Date()))} 作成）</p>`;
  if (has('PROFILE') || has('PHOTO')) {
    h += sec('プロフィール', `<div class="prof">${has('PHOTO') && pet.photoId ? img(pet.photoId, 'p-photo') : ''}${has('PROFILE') ? table(['項目', '内容'], [
      ['種類', `${SPECIES[pet.species]?.label || ''}${pet.breed ? `（${esc(pet.breed)}）` : ''}`],
      ['性別', `${SEX[pet.sex] || ''}・${pet.neutered ? '避妊・去勢済み' : '未去勢'}`],
      ['誕生日', `${slash(pet.birthDate)}${pet.birthEstimated ? '（推定）' : ''}　${ageText(pet)}`],
      ...(pet.adoptionDate ? [['お迎えした日', slash(pet.adoptionDate)]] : []),
      ['体重', `${formatAmount(pet.weightKg)} kg${pet.targetWeightKg ? `（目標 ${formatAmount(pet.targetWeightKg)} kg）` : ''}`],
      ['体形', `BCS ${pet.bcs}（${BCS_TITLES[pet.bcs - 1] || ''}）`],
      ['1日のカロリーの目安', formatKcal(targetKcal(pet))],
      ...[['性格', pet.personality], ['体の特徴', pet.physical], ['そのほか', pet.notes]].filter(([, v]) => v).map(([k, v]) => [k, esc(v)]),
    ]) : ''}</div>`);
  }
  if (has('DOCUMENTS') && (pet.documents || []).length) h += sec('書類', pet.documents.map((d) => `<figure>${img(d.photoId, 'p-doc')}<figcaption>${esc(d.title)}</figcaption></figure>`).join(''));
  if (has('WEIGHT')) {
    const ws = list('weights').filter((w) => w.petId === pet.id && inP(w.date)).sort((a, b) => a.date.localeCompare(b.date));
    h += sec('体重', (ws.length > 1 ? lineChart([{ name: pet.name, color: lineColor(pet), points: ws.map((w) => [w.date, w.kg]) }], { id: 'pdf-weight', unit: 'kg', goal: pet.targetWeightKg || null }) : '')
      + table(['日付', '体重'], ws.map((w) => [slash(w.date), `${formatAmount(w.kg)} kg`])));
  }
  if (has('FOOD')) {
    const feeds = list('feedLogs').filter((l) => l.petId === pet.id && inP(dayOf(l.time))).sort((a, b) => a.time - b.time);
    const byDay = Object.entries(feeds.reduce((m, l) => { (m[dayOf(l.time)] ||= []).push(l); return m; }, {}));
    h += sec('ごはん', table(['日付', '合計', '内容'], byDay.map(([d, l]) => [slash(d), formatKcal(l.reduce((s, x) => s + x.kcal, 0)),
      l.map((x) => `${timeText(x.time)} ${esc(x.foodName)} ${formatAmount(x.amount)}${esc(x.unitLabel)}`).join('<br>')])));
  }
  if (has('TOILET')) h += sec('うんち・おしっこ', table(['日時', '種類', '状態'], toilets.map((l) => [`${slash(dayOf(l.time))} ${timeText(l.time)}`, EXCRETION[l.type]?.label || '', esc(excretionDetail(l)) || '－'])));
  if (has('TOILET_PHOTOS')) {
    const ph = toilets.filter((l) => (l.photoIds || []).length);
    if (ph.length) h += sec('トイレの写真', ph.map((l) => l.photoIds.map((id) => `<figure>${img(id, 'p-small')}<figcaption>${slash(dayOf(l.time))} ${timeText(l.time)} ${EXCRETION[l.type]?.label || ''}</figcaption></figure>`).join('')).join(''));
  }
  if (has('MOOD')) {
    const ms = list('moods').filter((m) => m.petId === pet.id && inP(m.date)).sort((a, b) => a.date.localeCompare(b.date));
    h += sec('機嫌', ms.length ? `<p>${ms.map((m) => `${slash(m.date)} ${MOODS[m.mood]?.emoji || ''}${MOODS[m.mood]?.label || ''}`).join('　')}</p>` : '<p>記録はありません。</p>');
  }
  if (has('RECORDS')) {
    const rs = list('records').filter((r) => r.petId === pet.id && inP(dayOf(r.time))).sort((a, b) => a.time - b.time);
    h += sec('そのほかの記録', table(['日時', '項目', '内容'], rs.map((r) => [`${slash(dayOf(r.time))} ${timeText(r.time)}`, RECORD_ITEMS[r.item]?.label || r.item, esc(recordValueText(r)) || '✓'])));
  }
  if (has('SCHEDULE')) {
    const hs = list('health').filter((r) => r.petId === pet.id && inP(r.date)).sort((a, b) => a.date.localeCompare(b.date));
    h += sec('予定・通院', table(['日付', '種類', '内容'], hs.map((r) => [`${slash(r.date)}${r.time ? ` ${r.time.slice(0, 5)}` : ''}`, HEALTH[r.kind]?.label || '', `${esc(r.title)}${r.note ? `（${esc(r.note)}）` : ''}`])));
  }
  if (has('DIARY')) {
    const ds = list('diaries').filter((d) => d.petId === pet.id && inP(d.date)).sort((a, b) => a.date.localeCompare(b.date));
    h += sec('日記', ds.length ? ds.map((d) => `<p><b>${slash(d.date)}</b><br><span class="pre">${esc(d.text)}</span></p>`).join('') : '<p>記録はありません。</p>');
  }
  if (has('EXPENSE')) {
    const es = list('expenses').filter((e) => (e.petId === pet.id || e.petId == null) && inP(e.date)).sort((a, b) => a.date.localeCompare(b.date));
    h += sec('支出', table(['日付', '分類', '金額', 'メモ'], es.map((e) => [slash(e.date), `${EXPENSE_CATS[e.category]?.label || ''}${e.petId == null ? '（みんなの分）' : ''}`, yen(e.amount), esc(e.memo || '')]))
      + `<p>この子の分 ${yen(es.filter((e) => e.petId === pet.id).reduce((s, e) => s + e.amount, 0))}${es.some((e) => e.petId == null) ? `・みんなの分 ${yen(es.filter((e) => e.petId == null).reduce((s, e) => s + e.amount, 0))}` : ''}</p>`);
  }
  let box = $('#print');
  if (!box) { box = document.createElement('div'); box.id = 'print'; document.body.append(box); }
  box.innerHTML = h;
  document.title = `${pet.name}の記録_${o.from}_${o.to}`;
  setTimeout(() => { window.print(); document.title = 'うちの子ごはん帳'; }, 300);
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
    <div class="row gap"><label class="field">日付<input name="date" type="date" required value="${dateKey(new Date(log.time))}"></label>
      <label class="field">時刻<input name="time" type="time" required value="${hm(log.time)}"></label></div>
    <div class="label">写真（獣医さんに見せる用）</div>
    <div class="row wrap" id="toilet-photos">${(log.photoIds || []).map((id) => photoTag(id, 'thumb', false)).join('')}</div>
    <label class="btn ghost">📷 写真を追加<input type="file" accept="image/*" id="toilet-photo-input" hidden></label>`,
  (fd) => {
    put('excretionLogs', {
      ...log,
      condition: fd.get('condition') || null,
      flags: fd.getAll('flags'),
      note: String(fd.get('note') || ''),
      time: withTime(fd.get('date'), fd.get('time')),
      photoIds: added,
    });
    return true;
  });
  let added = [...(log.photoIds || [])];
  $('#toilet-photo-input').addEventListener('change', async (ev) => {
    const file = ev.target.files?.[0];
    if (!file) return;
    try {
      const b64 = await compressPhoto(file);
      const id = String(newId());
      await backend.putPhoto(S.familyId, id, b64);
      photoCache.set(id, `data:image/jpeg;base64,${b64}`);
      added = [...added, id];
      $('#toilet-photos').innerHTML = added.map((x) => photoTag(x, 'thumb', false)).join('');
    } catch {
      toast('写真を送れませんでした');
    }
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

/** 体温・散歩・ワクチンなどの記録（editing があれば修正） */
function recordDialog(pet, itemKey, time, editing) {
  const it = RECORD_ITEMS[itemKey];
  const r = editing || { id: newId(), petId: pet.id, item: itemKey, time, value: null, text: '', done: true };
  openDialog(`
    <h2>${it.emoji} ${it.label}${editing ? '' : 'を記録'}</h2>
    <div class="row gap"><label class="field">日付<input name="date" type="date" required value="${dateKey(new Date(r.time))}"></label>
      <label class="field">時刻<input name="time" type="time" required value="${hm(r.time)}"></label></div>
    ${it.kind === 'NUMBER' ? `<label class="field">${it.label}${it.unit ? `（${it.unit}）` : ''}<input name="value" type="number" inputmode="decimal" step="any" required value="${r.value ?? ''}"></label>` : ''}
    ${it.kind === 'CHECK' ? `<label class="check"><input type="checkbox" name="done" ${r.done !== false ? 'checked' : ''}>${itemKey === 'MEDICINE_DONE' ? '飲ませた' : '済み'}</label>` : ''}
    <label class="field">${it.kind === 'TEXT' ? '内容' : 'メモ（なくてもOK）'}<textarea class="area" name="text" rows="${it.kind === 'TEXT' ? 3 : 1}">${esc(r.text)}</textarea></label>`,
  (fd) => {
    const value = it.kind === 'NUMBER' ? Number(fd.get('value')) : null;
    if (it.kind === 'NUMBER' && !Number.isFinite(value)) { toast('数値を入れてください'); return false; }
    const next = { ...r, time: withTime(fd.get('date'), fd.get('time')), value, text: String(fd.get('text') || '').trim(), done: it.kind === 'CHECK' ? fd.get('done') === 'on' : true };
    put('records', next);
    if (!editing) recorded(`${it.label}を記録しました`, 'records', next);
    return true;
  });
}

/** 体重を記録・修正（1日1つ。いちばん新しい記録はプロフィールの体重にもなる） */
function weightDialog(pet, dateStr, editing) {
  const w0 = editing || { date: dateStr, kg: pet.weightKg, time: hm(Date.now()) };
  openDialog(`
    <h2>⚖ 体重${editing ? 'を直す' : 'を記録'}</h2>
    <div class="row gap"><label class="field">日付<input name="date" type="date" required value="${w0.date}"></label>
      <label class="field">時刻<input name="time" type="time" value="${w0.time ? w0.time.slice(0, 5) : ''}"></label></div>
    <label class="field">体重（kg）<input name="kg" type="number" inputmode="decimal" step="any" min="0" required value="${w0.kg || ''}"></label>
    ${editing ? '<button type="button" class="link muted" data-act="del-weight">この記録を削除</button>' : ''}`,
  (fd) => {
    const kg = Number(fd.get('kg'));
    if (!(kg > 0)) { toast('体重を入れてください'); return false; }
    const date = fd.get('date');
    const same = list('weights').find((w) => w.petId === pet.id && w.date === date && w.id !== editing?.id);
    if (same && editing) remove('weights', same);
    const w = { id: editing?.id || same?.id || newId(), petId: pet.id, date, kg, time: fd.get('time') ? `${fd.get('time')}:00`.slice(0, 8) : null };
    put('weights', w);
    syncLatestWeight(pet, w);
    return true;
  });
  $('#dialog [data-act=del-weight]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    closeDialog();
    remove('weights', editing);
    syncLatestWeight(pet, null, editing);
    toast('削除しました', '元に戻す', () => { put('weights', editing); syncLatestWeight(pet, editing); });
  });
}

function healthDialog(pet, kind, dateStr, editing) {
  const r = editing || { id: newId(), petId: pet.id, kind, date: dateStr, title: '', note: '', time: null,
    notifyDayBeforeHour: daysBetween(new Date(), parseDate(dateStr)) >= 0 ? 20 : null, notifyHoursBefore: null };
  openDialog(`
    <h2>${HEALTH[r.kind]?.emoji || ''} ${HEALTH[r.kind]?.label || ''}${editing ? 'を編集' : ''}</h2>
    <label class="field">内容<input name="title" value="${esc(r.title)}" placeholder="例：健康診断、3種混合ワクチン"></label>
    <div class="row gap"><label class="field">日付<input name="date" type="date" required value="${r.date}"></label>
      <label class="field">時刻（任意）<input name="time" type="time" value="${r.time ? r.time.slice(0, 5) : ''}"></label></div>
    <label class="field">メモ<input name="note" value="${esc(r.note)}"></label>
    ${editing ? '<button type="button" class="link muted" data-act="del-health">この予定を削除</button>' : ''}`,
  (fd) => {
    const t = fd.get('time');
    put('health', { ...r, title: String(fd.get('title') || '').trim() || HEALTH[r.kind]?.label || '', date: fd.get('date'), time: t ? `${t}:00` : null, note: String(fd.get('note') || '').trim() });
    return true;
  });
  $('#dialog [data-act=del-health]')?.addEventListener('click', (e) => {
    e.stopPropagation();
    closeDialog();
    remove('health', r);
    toast('削除しました', '元に戻す', () => put('health', r));
  });
}

function expenseDialog(pet, cat, dateStr, editing) {
  const e = editing || { id: newId(), petId: pet.id, date: dateStr, category: cat, amount: 0, memo: '' };
  openDialog(`
    <h2>💴 支出${editing ? 'を直す' : 'を記録'}</h2>
    <div class="row wrap">${Object.entries(EXPENSE_CATS).map(([k, c]) =>
      `<label class="chip"><input type="radio" name="category" value="${k}" ${e.category === k ? 'checked' : ''}>${c.emoji} ${c.label}</label>`).join('')}</div>
    <label class="field">金額（円）<input name="amount" type="number" inputmode="numeric" min="1" step="1" required value="${e.amount || ''}"></label>
    <label class="field">日付<input name="date" type="date" required value="${e.date}"></label>
    <div class="row wrap"><span class="label">だれの分</span>
      <label class="chip"><input type="radio" name="pet" value="" ${e.petId == null ? 'checked' : ''}>みんな</label>
      ${pets().map((p) => `<label class="chip"><input type="radio" name="pet" value="${p.id}" ${e.petId === p.id ? 'checked' : ''}>${esc(p.name)}</label>`).join('')}</div>
    <label class="field">メモ<input name="memo" value="${esc(e.memo)}" placeholder="お店・品名など"></label>
    ${editing ? '<button type="button" class="link muted" data-act="del-expense">この支出を削除</button>' : ''}`,
  (fd) => {
    const amount = Math.round(Number(fd.get('amount')));
    if (!(amount > 0)) { toast('金額を入れてください'); return false; }
    put('expenses', { ...e, category: fd.get('category') || 'OTHER', amount, date: fd.get('date'), petId: fd.get('pet') ? Number(fd.get('pet')) : null, memo: String(fd.get('memo') || '').trim() });
    return true;
  });
  $('#dialog [data-act=del-expense]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    closeDialog();
    remove('expenses', e);
    toast('削除しました', '元に戻す', () => put('expenses', e));
  });
}

function photoViewer(id) {
  const url = photoUrl(id);
  if (!url) return;
  const box = $('#dialog');
  box.innerHTML = `<div class="sheet"><img class="full" src="${url}" alt=""><div class="row end"><button type="button" class="btn" data-close>閉じる</button></div></div>`;
  box.hidden = false;
  box.querySelector('[data-close]').addEventListener('click', closeDialog);
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
    case 'join': joinFamily(S.invite.fid, S.invite.key); break;
    case 'paste-join': {
      const m = ($('#invite-input')?.value || '').match(INVITE_RE);
      if (m) joinFamily(m[1], m[2]); else toast('招待リンクの形ではないようです');
      break;
    }
    case 'google': googleLogin(); break;
    case 'new-invite': backend.createInvite(S.familyId).catch(() => toast('招待を作れませんでした')); break;
    case 'revoke-invite':
      if (confirm('今の招待を取り消しますか？（参加済みのメンバーはそのままです）')) {
        backend.revokeInvites(S.familyId).catch(() => toast('取り消せませんでした'));
      }
      break;
    case 'copy-invite': {
      const url = inviteUrl(S.currentInvite.key);
      (navigator.clipboard?.writeText(url) ?? Promise.reject()).then(() => toast('コピーしました'), () => {
        const input = $('#main input.copy'); input.select(); toast('長押しでコピーしてください');
      });
      break;
    }
    case 'share-invite':
      navigator.share({ text: 'うちの子ごはん帳の記録を一緒につけよう 🐾', url: inviteUrl(S.currentInvite.key) }).catch(() => {});
      break;
    case 'remove-member':
      if (confirm(`「${a.name}」を家族から外しますか？外された端末は、家族の記録を見られなくなります。`)) {
        backend.removeMember(S.familyId, a.uid).catch(() => toast('外せませんでした'));
      }
      break;
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
    case 'calmode': S.calMode = a.mode; store.set('calMode', a.mode); render(); break;
    case 'show-options': S.showOptions = !S.showOptions; render(); break;
    case 'cal-show':
      S.calShow = el.checked ? [...new Set([...S.calShow, a.key])] : S.calShow.filter((k) => k !== a.key);
      store.set('calShow', JSON.stringify(S.calShow));
      render();
      break;
    case 'quick-item': {
      const it = RECORD_ITEMS[a.item];
      const time = a.tm === 'slot' ? slotTime() : Date.now();
      if (a.item === 'WEIGHT') weightDialog(pet, a.tm === 'slot' ? S.selected : dateKey(new Date()));
      else if (it.kind === 'CHECK') {
        const r = { id: newId(), petId: pet.id, item: a.item, time, value: null, text: '', done: true };
        put('records', r);
        recorded(`${it.label}を記録しました`, 'records', r);
      } else recordDialog(pet, a.item, time);
      break;
    }
    case 'edit-record': { const r = S.data.records.get(a.id); if (r && RECORD_ITEMS[r.item]) recordDialog(pet, r.item, r.time, r); break; }
    case 'save-diary': {
      const text = ($('#diary-text')?.value || '').trim();
      const prev = S.data.diaries.get(`${pet.id}_${S.selected}`);
      if (text) put('diaries', { petId: pet.id, date: S.selected, text });
      else if (prev) remove('diaries', prev);
      toast('日記を保存しました');
      break;
    }
    case 'add-health': healthDialog(pet, a.kind, S.selected); break;
    case 'edit-health': { const r = S.data.health.get(a.id); if (r) healthDialog(pet, r.kind, r.date, r); break; }
    case 'add-expense': expenseDialog(pet, a.cat, S.selected); break;
    case 'edit-expense': { const e = S.data.expenses.get(a.id); if (e) expenseDialog(pet, e.category, e.date, e); break; }
    case 'photo': photoViewer(a.id); break;
    case 'undo-last': { const e = S.undo[S.undo.length - 1]; if (e) { undoEntry(e); toast(`取り消しました：${e.label}`); } break; }
    case 'undo-clear': S.undo = []; render(); break;
    case 'chart': if (tapChart(el, ev)) render(); break;
    case 'add-pet': petEditor(); break;
    case 'edit-pet': { const p = S.data.pets.get(a.id); if (p) petEditor(p); break; }
    case 'pet-open': S.petView = Number(a.id); S.showAllWeights = false; S.showAllRecords = false; render(); window.scrollTo(0, 0); break;
    case 'pet-back': S.petView = null; render(); break;
    case 'trend': S.trendKey = a.key; S.showAllRecords = false; render(); break;
    case 'trend-range': S.trendRange = a.range; render(); break;
    case 'weight-range': S.weightRange = a.range; render(); break;
    case 'show-all-weights': S.showAllWeights = true; render(); break;
    case 'show-all-records': S.showAllRecords = true; render(); break;
    case 'add-weight': { const p = S.data.pets.get(a.id); if (p) weightDialog(p, dateKey(new Date())); break; }
    case 'edit-weight': {
      const w = S.data.weights.get(a.id);
      const p = w && S.data.pets.get(String(w.petId));
      if (p) weightDialog(p, w.date, w);
      break;
    }
    case 'dash-scope': S.dashScope = Number(a.id); render(); break;
    case 'dash-range': S.dashRange = a.range; render(); break;
    case 'add-food': foodDialog(); break;
    case 'edit-food': { const f = foodById(a.id); if (f) foodDialog(f); break; }
    case 'add-plan': planDialog(pet); break;
    case 'edit-plan': { const p = S.data.plans.get(a.id); if (p) planDialog(pet, p); break; }
    case 'pet-move': {
      const all = pets();
      const i = all.findIndex((p) => String(p.id) === a.id);
      const j = i + Number(a.d);
      if (i < 0 || j < 0 || j >= all.length) break;
      all.splice(j, 0, all.splice(i, 1)[0]);
      reorderPets(all);
      break;
    }
    case 'pet-sort': reorderPets([...pets()].sort(AUTO_SORTS[Number(a.i)][1])); toast(`${AUTO_SORTS[Number(a.i)][0]}に並べました`); break;
    case 'icon-mode': { const p = S.data.pets.get(a.id); if (p) put('pets', { ...p, iconMode: a.mode, photoAsIcon: a.mode === 'photo' }); break; }
    case 'meal-add': {
      const times = sharedSettings().mealTimes || ['07:00', '12:00', '18:00', '22:00'];
      const last = times.length ? Number(times[times.length - 1].split(':')[0]) : 7;
      putSettings({ mealTimes: [...new Set([...times, `${String(Math.min(23, last + 1)).padStart(2, '0')}:00`])].sort() });
      break;
    }
    case 'meal-del': {
      const times = sharedSettings().mealTimes || ['07:00', '12:00', '18:00', '22:00'];
      putSettings({ mealTimes: times.filter((_, i) => i !== Number(a.i)) });
      S.slot = null;
      break;
    }
    case 'meal-quick': putSettings({ quickAtMealTime: a.v === '1' }); S.slot = null; break;
    case 'item-toggle': {
      const on = new Set(items());
      if (el.checked) on.add(a.key); else on.delete(a.key);
      putSettings({ items: Object.keys(RECORD_ITEMS).filter((k) => on.has(k)) });
      break;
    }
    case 'pdf-pet': S.pdf.petId = Number(a.id); render(); break;
    case 'pdf-sec': S.pdf.sections = el.checked ? [...new Set([...S.pdf.sections, a.key])] : S.pdf.sections.filter((k) => k !== a.key); break;
    case 'pdf-print': printReport(); break;
    case 'font': prefs.setFontScale(Number(a.v)); render(); break;
    case 'layout-screen': S.layoutScreen = a.s; render(); break;
    case 'layout-move': prefs.move(a.key, Number(a.d)); render(); break;
    case 'layout-show': prefs.setShown(a.key, el.checked); render(); break;
    case 'cal-size': prefs.setCalendarSize(Number(a.v)); render(); break;
    case 'photo-size': prefs.setPetPhotoSize(Number(a.v)); render(); break;
    case 'layout-reset': if (confirm('画面の並びと表示を最初の状態に戻しますか？')) { prefs.resetLayout(); render(); } break;
    case 'month': S.month = new Date(S.month.getFullYear(), S.month.getMonth() + Number(a.d), 1); render(); break;
    default: break;
  }
});

// 入力欄の変更（ごはんの時刻・PDF の期間）
document.addEventListener('change', (ev) => {
  const el = ev.target.closest('[data-change]');
  if (!el) return;
  const a = el.dataset;
  switch (a.change) {
    case 'meal-time': {
      if (!el.value) break;
      const times = [...(sharedSettings().mealTimes || ['07:00', '12:00', '18:00', '22:00'])];
      times[Number(a.i)] = el.value;
      putSettings({ mealTimes: [...new Set(times)].sort() });
      S.slot = null;
      break;
    }
    case 'pdf-from': if (el.value) S.pdf.from = el.value; break;
    case 'pdf-to': if (el.value) S.pdf.to = el.value; break;
    default: break;
  }
});

// 日付が変わったときや、しばらく閉じていたあとに開いたときに表示を更新
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });
window.addEventListener('hashchange', () => { if (/join=/.test(location.hash)) start(); });

start();
