// うちの子ごはん帳 Web 版（家族共有用）。「きょう」と「カレンダー」で記録・閲覧ができる。
import * as backend from './backend.js';
import {
  COLLECTIONS, EXCRETION, MOODS, HEALTH, petIcon, petColor, newId, docId, dateKey, parseDate, timeText, daysBetween, targetKcal, kcalPerUnit, plan, formatAmount, formatKcal, cycleText, isAbnormal, excretionDetail,
  TREAT_LIMIT_RATIO, RECORD_ITEMS, DEFAULT_ITEMS, EXPENSE_CATS, yen, shortYen, recordValueText, ABNORMAL_CONDITIONS,
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
  tab: store.get('tab') || 'today',
  month: (() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); })(),
  selected: dateKey(new Date()),
  slot: null,
  calMode: ['RECORD', 'DIARY', 'SCHEDULE', 'EXPENSE'].includes(store.get('calMode')) ? store.get('calMode') : 'RECORD',
  calShow: (() => { try { return JSON.parse(store.get('calShow')) || null; } catch { return null; } })()
    || ['KCAL', 'TOILET', 'MOOD', 'RECORDS', 'SCHEDULE', 'DIARY', 'EXPENSE'],
  showOptions: false,
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
async function compressPhoto(file, maxSide = 800, quality = 0.55) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const scale = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bmp.width * scale);
  canvas.height = Math.round(bmp.height * scale);
  canvas.getContext('2d').drawImage(bmp, 0, 0, canvas.width, canvas.height);
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
      members: (m) => { S.members = m; if (S.tab === 'family') render(); },
      invite: (i) => { S.currentInvite = i; if (S.tab === 'family') render(); },
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
      ${avatarTag(p)}${esc(p.name)}
    </button>`).join('')}</div>`;
  tabs.hidden = false;
  tabs.innerHTML = [['today', '🏠', 'きょう'], ['calendar', '📅', 'カレンダー'], ['family', '👨‍👩‍👧', '家族']].map(([k, e, l]) =>
    `<button class="${S.tab === k ? 'on' : ''}" data-act="tab" data-tab="${k}"><span>${e}</span>${l}</button>`).join('');
  main.innerHTML = S.tab === 'calendar' ? calendarView(pet) : S.tab === 'family' ? familyView() : todayView(pet);
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
  if (isOn('FOOD')) html += card('🍚 ごはんをあげた', (lines.length ? lines.map((ln) => {
    const given = feeds.filter((l) => l.foodId === ln.food.id).length;
    return `<div class="row">
      <div class="grow"><b>${esc(ln.food.name)}</b>
        <div class="small muted">1回 ${formatAmount(ln.amount)} ${esc(ln.food.unitLabel)}・${cycleText(ln.item.timesPerCycle, ln.item.cycleDays)}</div>
        <div class="small accent">今日 ${given}${ln.item.cycleDays === 1 ? `／${ln.item.timesPerCycle}` : ''} 回</div></div>
      <button class="btn" data-act="feed" data-plan="${ln.item.id}" ${ln.amount > 0 ? '' : 'disabled'}>あげた</button></div>`;
  }).join('') : '<p class="hint">ごはんプランはアプリの「ごはん」タブで作れます。</p>') +
    (list('foods').length ? `<button class="link" data-act="feed-custom" data-date="${key}">＋ 量を変えて・プラン外のごはんやおやつを記録</button>` : ''));

  const toiletTypes = Object.entries(EXCRETION).filter(([k]) => isOn(k));
  if (toiletTypes.length || isOn('MOOD')) html += card(toiletTypes.length ? '🚽 トイレ・きげん' : '☀️ きげん', (isOn('MOOD') ? moodPicker(pet, key) : '') + `
    <div class="row gap">${toiletTypes.map(([k, e]) =>
      `<button class="btn big ${k.toLowerCase()}" data-act="toilet" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
    ${toiletTypes.map(([k, e]) => {
      const l = toilets.filter((t) => t.type === k);
      const last = l.length ? Math.max(...l.map((t) => t.time)) : null;
      return `<div class="small muted">${e.emoji} 今日 ${l.length} 回${last ? `（最後 ${timeText(last)}）` : ''}</div>`;
    }).join('')}`);

  html += card('📝 ほかの記録', quickItems(key, 'now'));
  const records = list('records').filter((r) => r.petId === pet.id && sameDay(r.time, key));
  html += card('🗒 今日の記録', recordList(feeds, toilets, records) || '<p class="hint">まだ記録がありません。トイレの記録をタップすると状態（かたさ・血が混じる など）や写真を残せます。</p>');
  return html;
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
const mealLabel = (h) => (h >= 4 && h <= 9 ? '朝' : h >= 10 && h <= 14 ? '昼' : h >= 15 && h <= 19 ? '夕' : '夜');
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
    summary = `<b>今月の支出 ${yen(l.reduce((s, e) => s + e.amount, 0))}</b>
      <div class="small muted">${byCat.map(([c, v]) => `${EXPENSE_CATS[c]?.emoji || ''}${yen(v)}`).join('　')}</div>`;
  }

  let quick = '';
  if (S.calMode === 'RECORD') {
    const lines = planLines(pet);
    quick = card(`⚡ ${label} にワンタップで記録`, `
      <div class="row wrap">${slots().map(([k, l]) => `<button class="chip ${currentSlot() === k ? 'on' : ''}" data-act="slot" data-slot="${k}">${l}</button>`).join('')}</div>
      ${isOn('FOOD') ? `<div class="row wrap">${lines.filter((ln) => ln.amount > 0).map((ln) =>
        `<button class="btn tonal" data-act="feed-at" data-plan="${ln.item.id}">🍚 ${esc(ln.food.name)} ${formatAmount(ln.amount)}${esc(ln.food.unitLabel)}</button>`).join('')}
        ${list('foods').length ? `<button class="btn ghost" data-act="feed-custom" data-date="${S.selected}">＋ ほかのごはん</button>` : ''}</div>` : ''}
      <div class="row gap">${Object.entries(EXCRETION).filter(([k]) => isOn(k)).map(([k, e]) =>
        `<button class="btn ${k.toLowerCase()}" data-act="toilet-at" data-type="${k}">${e.emoji} ${e.label}</button>`).join('')}</div>
      ${quickItems(S.selected, 'slot')}
      ${isOn('MOOD') ? moodPicker(pet, S.selected) : ''}`, 'mint');
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
    + (spent.length ? `<div class="label">💴 支出 ${yen(spent.reduce((s, e) => s + e.amount, 0))}</div>${spent.map((e) =>
      `<button class="plain row" data-act="edit-expense" data-id="${e.id}"><span class="grow">${EXPENSE_CATS[e.category]?.emoji || ''} ${EXPENSE_CATS[e.category]?.label || ''}${e.memo ? `・${esc(e.memo)}` : ''}
        <span class="small muted">　${e.petId == null ? 'みんな' : esc(S.data.pets.get(String(e.petId))?.name || '')}</span></span><b>${yen(e.amount)}</b></button>`).join('')}` : '');

  return `<div class="chips">${CAL_MODES.map(([k, l]) => `<button class="chip ${S.calMode === k ? 'on' : ''}" data-act="calmode" data-mode="${k}">${l}</button>`).join('')}</div>` +
    card('', `
    <div class="row month"><button class="link" data-act="month" data-d="-1">◀</button>
      <b>${month.getFullYear()}年${month.getMonth() + 1}月</b><button class="link" data-act="month" data-d="1">▶</button></div>
    <div class="grid week">${[...'日月火水木金土'].map((w) => `<span>${w}</span>`).join('')}</div>
    <div class="grid lines-${Math.min(lineCount, 4)}">${cells}</div>
    ${summary}
    <div class="row">${shows('KCAL') ? '<div class="row wrap grow"><span class="kc k-lemon">少なめ</span><span class="kc k-mint">ちょうど</span><span class="kc k-peach">多め</span></div>' : '<span class="grow"></span>'}
      <button class="link" data-act="show-options">${S.showOptions ? '閉じる' : '👁 表示'}</button></div>
    ${S.showOptions ? `<div class="label">カレンダーに出すもの</div><div class="col">${CAL_SHOW.map(([k, l]) =>
      `<label class="check"><input type="checkbox" data-act="cal-show" data-key="${k}" ${shows(k) ? 'checked' : ''}>${l}</label>`).join('')}</div>
      ${shows('SCHEDULE') ? `<div class="row wrap legend">${Object.values(HEALTH).map((h) => `<span><i style="background:${h.color}"></i>${h.label}</span>`).join('')}</div>` : ''}` : ''}`) +
    quick +
    card(`📝 ${label} の記録`, dayBody);
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

/** 体重（1日1つ。アプリと同じく、いちばん新しい記録はプロフィールの体重にもなる） */
function weightDialog(pet, dateStr) {
  openDialog(`
    <h2>⚖ 体重を記録</h2>
    <div class="row gap"><label class="field">日付<input name="date" type="date" required value="${dateStr}"></label>
      <label class="field">時刻<input name="time" type="time" value="${hm(Date.now())}"></label></div>
    <label class="field">体重（kg）<input name="kg" type="number" inputmode="decimal" step="any" min="0" required value="${pet.weightKg || ''}"></label>`,
  (fd) => {
    const kg = Number(fd.get('kg'));
    if (!(kg > 0)) { toast('体重を入れてください'); return false; }
    const date = fd.get('date');
    const same = list('weights').find((w) => w.petId === pet.id && w.date === date);
    const w = { id: same?.id || newId(), petId: pet.id, date, kg, time: fd.get('time') ? `${fd.get('time')}:00`.slice(0, 8) : null };
    put('weights', w);
    const latest = [...list('weights').filter((x) => x.petId === pet.id), w].sort((a, b) => a.date.localeCompare(b.date)).pop();
    if (latest === w && pet.weightKg !== kg) put('pets', { ...pet, weightKg: kg });
    return true;
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
    case 'month': S.month = new Date(S.month.getFullYear(), S.month.getMonth() + Number(a.d), 1); render(); break;
    default: break;
  }
});

// 日付が変わったときや、しばらく閉じていたあとに開いたときに表示を更新
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });
window.addEventListener('hashchange', () => { if (/join=/.test(location.hash)) start(); });

start();
