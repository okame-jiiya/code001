// この端末（ブラウザ）だけの表示の設定。アプリの UiPrefs と同じ考え方。

const get = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
const set = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch { /* 保存できなくても動く */ } };

export const SCREENS = [
  ['TABS', '下のタブ'], ['HOME', 'きょう'], ['CALENDAR', 'カレンダー'], ['PET', 'うちの子（1頭ずつの画面）'], ['DASHBOARD', 'まとめ'],
];

/** 画面の中の並べ替えられる部分（最初の順番がふつうの並び）。[key, 画面, 名前, 隠せるか] */
export const SECTIONS = [
  ['TAB_HOME', 'TABS', '🏠 きょう', true],
  ['TAB_CALENDAR', 'TABS', '📅 カレンダー', true],
  ['TAB_FOOD', 'TABS', '🍚 ごはん', true],
  ['TAB_PET', 'TABS', '🐾 うちの子', true],
  ['TAB_DASHBOARD', 'TABS', '📊 まとめ（推移のダッシュボード）', true],
  ['TAB_SETTINGS', 'TABS', '⚙ 設定', false],

  ['HOME_UNDO', 'HOME', '直前の記録の取り消し', true],
  ['HOME_ALERTS', 'HOME', '注意・近い予定', true],
  ['HOME_KCAL', 'HOME', '今日のカロリー', true],
  ['HOME_FOOD', 'HOME', 'ごはんをあげた', true],
  ['HOME_TOILET', 'HOME', 'トイレ・きげん', true],
  ['HOME_OTHER', 'HOME', 'ほかの記録', true],
  ['HOME_LOG', 'HOME', '今日の記録', true],

  ['CAL_GRID', 'CALENDAR', 'カレンダー', false],
  ['CAL_QUICK', 'CALENDAR', '選んだ日にすぐ登録', true],
  ['CAL_DAY', 'CALENDAR', '選んだ日の記録', true],

  ['PET_PROFILE', 'PET', 'プロフィール', false],
  ['PET_ABOUT', 'PET', 'この子のこと（性格・特徴）', true],
  ['PET_TRENDS', 'PET', '記録の推移', true],
  ['PET_DOCS', 'PET', '書類', true],
  ['PET_KCAL', 'PET', '1日のカロリーの目安', true],

  ['DASH_EXPENSE', 'DASHBOARD', '支出', true],
  ['DASH_WEIGHT', 'DASHBOARD', '体重', true],
  ['DASH_KCAL', 'DASHBOARD', '1日のカロリー', true],
  ['DASH_POOP', 'DASHBOARD', 'うんちの回数', true],
  ['DASH_PEE', 'DASHBOARD', 'おしっこの回数', true],
  ['DASH_MOOD', 'DASHBOARD', '機嫌', true],
  ['DASH_RECORDS', 'DASHBOARD', 'ほかの記録（設定で選んだ項目）', true],
];
export const sectionInfo = (key) => SECTIONS.find((s) => s[0] === key);

/** 画面ごとの並び（保存した順。あとから増えた部分は、ふつうの並びで前にあるものの後ろに入れる） */
export function layout(screen) {
  const all = SECTIONS.filter((s) => s[1] === screen).map((s) => s[0]);
  const saved = (get(`order_${screen}`) || '').split(',').filter((k) => all.includes(k));
  const list = [...new Set(saved)];
  all.forEach((k, i) => {
    if (list.includes(k)) return;
    const before = all.slice(0, i).reverse().find((x) => list.includes(x));
    list.splice(before ? list.indexOf(before) + 1 : 0, 0, k);
  });
  return list;
}

export function hidden() {
  try { return new Set(JSON.parse(get('hiddenSections')) || []); } catch { return new Set(); }
}

/** 画面に出す順（表示しないものを除く） */
export const visible = (screen) => { const h = hidden(); return layout(screen).filter((k) => !h.has(k)); };

export function move(key, by) {
  const screen = sectionInfo(key)[1];
  const list = layout(screen);
  const i = list.indexOf(key);
  const j = Math.min(list.length - 1, Math.max(0, i + by));
  if (i === j) return;
  list.splice(j, 0, list.splice(i, 1)[0]);
  set(`order_${screen}`, list.join(','));
}

export function setShown(key, shown) {
  const h = hidden();
  if (shown) h.delete(key); else h.add(key);
  set('hiddenSections', JSON.stringify([...h]));
}

export const calendarSize = () => Number(get('calendarSize')) || 1;
export const setCalendarSize = (v) => set('calendarSize', String(v));
export const petPhotoSize = () => Number(get('petPhotoSize')) || 72;
export const setPetPhotoSize = (v) => set('petPhotoSize', String(v));
export const fontScale = () => Number(get('fontScale')) || 1;
export const setFontScale = (v) => set('fontScale', String(v));

export function resetLayout() {
  SCREENS.forEach(([s]) => set(`order_${s}`, null));
  set('hiddenSections', null);
  set('calendarSize', null);
  set('petPhotoSize', null);
}
