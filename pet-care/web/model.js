// うちの子ごはん帳 Web 版：データの形と計算（Android アプリの model/ と同じ内容）

export const COLLECTIONS = ['pets', 'foods', 'plans', 'feedLogs', 'excretionLogs', 'weights', 'reminders', 'health', 'moods',
  'records', 'diaries', 'expenses', 'settings'];

/** 記録できる項目（アプリの RecordItem と同じ。special はごはん・トイレなど専用の記録を使うもの） */
export const RECORD_ITEMS = {
  WEIGHT: { label: '体重', emoji: '⚖', kind: 'NUMBER', unit: 'kg', special: true },
  TEMPERATURE: { label: '体温', emoji: '🌡', kind: 'NUMBER', unit: '℃' },
  FOOD: { label: 'ごはん', emoji: '🍚', kind: 'NUMBER', special: true },
  WATER: { label: '水', emoji: '🚰', kind: 'NUMBER', unit: 'ml' },
  POOP: { label: 'うんち', emoji: '💩', kind: 'TEXT', special: true },
  PEE: { label: 'おしっこ', emoji: '💧', kind: 'TEXT', special: true },
  MOOD: { label: '機嫌', emoji: '☀️', kind: 'CHECK', special: true },
  WALK: { label: '散歩', emoji: '🦮', kind: 'NUMBER', unit: '分' },
  HEARTWORM: { label: 'フィラリア予防', emoji: '🦟', kind: 'CHECK' },
  COMBO_VACCINE: { label: '混合ワクチン', emoji: '💉', kind: 'TEXT' },
  RABIES: { label: '狂犬病ワクチン', emoji: '🩹', kind: 'CHECK' },
  VISIT: { label: '通院記録', emoji: '🏥', kind: 'TEXT' },
  MEDICINE_DONE: { label: '薬（飲ませた）', emoji: '💊', kind: 'CHECK' },
  MEDICINE_NOTE: { label: '薬（内容）', emoji: '📋', kind: 'TEXT' },
  SICK: { label: '体調不良', emoji: '🤒', kind: 'TEXT' },
  BRUSHING: { label: 'ブラッシング', emoji: '🪮', kind: 'TEXT' },
  TRIMMING: { label: 'トリミング', emoji: '✂', kind: 'TEXT' },
  SHAMPOO: { label: 'シャンプー', emoji: '🛁', kind: 'TEXT' },
  NAIL: { label: '爪切り', emoji: '💅', kind: 'TEXT' },
};
export const DEFAULT_ITEMS = ['WEIGHT', 'FOOD', 'POOP', 'PEE', 'MOOD', 'MEDICINE_DONE', 'VISIT'];

export const EXPENSE_CATS = {
  FOOD: { label: 'フード', emoji: '🍚' },
  TREAT: { label: 'おやつ', emoji: '🍪' },
  MEDICAL: { label: '医療', emoji: '🏥' },
  TRIMMING: { label: 'トリミング', emoji: '✂' },
  GOODS: { label: '用品', emoji: '🧸' },
  INSURANCE: { label: '保険', emoji: '🛡' },
  OTHER: { label: 'その他', emoji: '💴' },
};

export const yen = (v) => `¥${Number(v || 0).toLocaleString('ja-JP')}`;
export const shortYen = (v) => (v >= 100000 ? `${Math.floor(v / 10000)}万` : v >= 10000 ? `${(v / 10000).toFixed(1)}万` : `¥${v}`);

export function recordValueText(r) {
  const it = RECORD_ITEMS[r.item];
  if (!it) return r.text || '';
  const main = it.kind === 'NUMBER' ? (r.value != null ? `${formatAmount(r.value)}${it.unit ? ` ${it.unit}` : ''}` : null)
    : it.kind === 'CHECK' ? (r.done === false ? '未' : '✓ 済み') : null;
  return [main, r.text || null].filter(Boolean).join('　');
}

export const SPECIES = {
  CAT: { label: '猫', emoji: '🐱', icons: ['🐱', '🐈', '🐈‍⬛', '😺', '😸'] },
  DOG: { label: '犬', emoji: '🐶', icons: ['🐶', '🐕', '🐩', '🦮', '🐕‍🦺'] },
};

export const PET_COLORS = ['#FFC9D6', '#FFD8B5', '#FFF0A8', '#C6EBD9', '#C4E1F6', '#DCCFF5', '#E6D3C0', '#DCDCE3'];

export const EXCRETION = {
  POOP: {
    label: 'うんち', emoji: '💩',
    conditions: ['コロコロでかたい', 'ふつう', 'やわらかめ', '形がない（泥状）', '水っぽい（下痢）'],
    flags: ['赤い血が混じる', '黒っぽい（タールのよう）', '色がいつもと違う（白・灰・黄・緑など）', '粘液・ゼリー状のものがつく',
      '虫や米粒のようなものが混じる', '異物（毛・ひも・おもちゃなど）が混じる', 'いきむのに出ない・出にくい', 'においがいつもより強い', 'トイレ以外でした'],
  },
  PEE: {
    label: 'おしっこ', emoji: '💧',
    conditions: ['出ない', '少ない', 'ふつう', '多い'],
    flags: ['赤い・ピンク色（血が混じる）', '色がいつもと違う（濃い・茶色・オレンジなど）', 'にごっている・キラキラしたものがある',
      '何度も行くのに少ししか出ない', '出すときに痛がる・鳴く', '量がとても多い・水をよく飲む', 'においがいつもと違う（甘い・強い）', 'トイレ以外でした'],
  },
};
export const ABNORMAL_CONDITIONS = ['下痢', '形がない（泥状）', '水っぽい（下痢）', '出ない'];

export const MOODS = {
  GOOD: { label: 'ごきげん', emoji: '☀️' },
  SO_SO: { label: 'ふつう', emoji: '☁️' },
  BAD: { label: 'いまいち', emoji: '☔' },
};

export const HEALTH = {
  VISIT: { label: '通院', emoji: '🏥', color: '#E0708F' },
  MEDICINE: { label: 'お薬', emoji: '💊', color: '#8E7CCF' },
  VACCINE: { label: 'ワクチン', emoji: '💉', color: '#3FA796' },
  TRIMMING: { label: 'トリミング', emoji: '✂', color: '#E8963F' },
  OTHER: { label: 'その他', emoji: '📝', color: '#7D90AE' },
};

export const petIcon = (p) => (SPECIES[p.species] || SPECIES.CAT).icons[p.iconIndex || 0] || SPECIES[p.species]?.emoji || '🐾';
export const petColor = (p) => PET_COLORS[(((p.colorIndex || 0) % PET_COLORS.length) + PET_COLORS.length) % PET_COLORS.length];

/** ID は「ミリ秒 × 1000 + 乱数」（アプリと同じ。2^53 未満に収まる） */
let lastId = 0;
export function newId() {
  const id = Math.max(Date.now() * 1000 + Math.floor(Math.random() * 1000), lastId + 1);
  lastId = id;
  return id;
}

/** ドキュメント ID（アプリの SyncData と同じ規則） */
export const docId = (collection, item) => (collection === 'moods' || collection === 'diaries' ? `${item.petId}_${item.date}`
  : collection === 'settings' ? 'app' : String(item.id));

// ---- 日付 ----
const pad = (n) => String(n).padStart(2, '0');
export const dateKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const parseDate = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
export const timeText = (ms) => { const d = new Date(ms); return `${d.getHours()}:${pad(d.getMinutes())}`; };
export const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
export const daysBetween = (a, b) => Math.round((parseDate(dateKey(b)) - parseDate(dateKey(a))) / 86400000);

// ---- カロリー計算（Nutrition.kt と同じ） ----
export const TREAT_LIMIT_RATIO = 0.10;

export function ageInMonths(birth, today) {
  let m = (today.getFullYear() - birth.getFullYear()) * 12 + (today.getMonth() - birth.getMonth());
  if (today.getDate() < birth.getDate()) m -= 1;
  return Math.max(0, m);
}

function stage(species, months) {
  if (months < 4) return 'BABY_EARLY';
  if (months < 12) return 'BABY';
  if (species === 'DOG' && months >= 84) return 'SENIOR';
  if (species === 'CAT' && months >= 132) return 'SENIOR';
  return 'ADULT';
}

const rer = (kg) => (kg <= 0 ? 0 : 70 * Math.pow(kg, 0.75));

const STAGE_LABEL = { BABY_EARLY: '子ども（生後4か月未満）', BABY: '子ども（生後4〜12か月）', ADULT: '成年', SENIOR: 'シニア' };

export const estimatedIdealWeight = (kg, bcs) => kg / (1 + 0.15 * (Math.min(5, Math.max(1, bcs)) - 3));

/** 1日に必要なカロリーとその内訳（Nutrition.energy と同じ） */
export function energy(pet, today = new Date()) {
  const st = stage(pet.species, ageInMonths(parseDate(pet.birthDate), today));
  const growing = st === 'BABY_EARLY' || st === 'BABY';
  const dog = pet.species === 'DOG';
  const sp = SPECIES[pet.species]?.label || '';
  let basis;
  if (growing) basis = pet.weightKg;
  else if (pet.targetWeightKg != null && pet.targetWeightKg > 0) basis = pet.targetWeightKg;
  else basis = estimatedIdealWeight(pet.weightKg, pet.bcs);
  let factor; let label;
  if (st === 'BABY_EARLY') { factor = 3.0; label = '成長期（生後4か月未満）'; }
  else if (st === 'BABY') { factor = dog ? 2.0 : 2.5; label = '成長期（生後4〜12か月）'; }
  else if (pet.bcs >= 4) { factor = dog ? 1.0 : 0.8; label = `減量中（BCS ${pet.bcs}）`; }
  else if (dog && st === 'SENIOR') { factor = 1.4; label = 'シニア犬（7歳以上）'; }
  else if (pet.neutered) { factor = dog ? 1.6 : 1.2; label = `避妊・去勢済みの成${sp}`; }
  else { factor = dog ? 1.8 : 1.4; label = `未去勢の成${sp}`; }
  const calculated = rer(basis) * factor;
  const target = pet.customKcal != null && pet.customKcal > 0 ? pet.customKcal : calculated;
  return { stage: st, stageLabel: STAGE_LABEL[st], basis, rer: rer(basis), factor, factorLabel: label, calculated, target, growing };
}

export const targetKcal = (pet, today = new Date()) => energy(pet, today).target;

export const SEX = { MALE: 'オス', FEMALE: 'メス' };
export const BCS_TITLES = ['痩せ', 'やや痩せ', '理想的', 'やや肥満', '肥満'];
export const PACK_UNITS = ['袋', 'パウチ', '缶', '本', '個', '枚', 'カップ', 'トレイ'];
export const CYCLE_PRESETS = [['毎日1回', 1, 1], ['毎日2回', 2, 1], ['毎日3回', 3, 1], ['2日に1回', 1, 2], ['3日に1回', 1, 3], ['週1回', 1, 7]];

/** 「3歳2か月」のような年齢 */
export function ageText(pet, today = new Date()) {
  const m = ageInMonths(parseDate(pet.birthDate), today);
  const t = m < 12 ? `${m}か月` : m % 12 === 0 ? `${m / 12}歳` : `${Math.floor(m / 12)}歳${m % 12}か月`;
  return pet.birthEstimated ? `約${t}` : t;
}

/** 「2年3か月」のような期間 */
export function durationText(fromKey, today = new Date()) {
  const from = parseDate(fromKey);
  const m = ageInMonths(from, today);
  if (m < 1) return `${daysBetween(from, today)}日`;
  return m < 12 ? `${m}か月` : m % 12 === 0 ? `${m / 12}年` : `${Math.floor(m / 12)}年${m % 12}か月`;
}

export const slash = (key) => { const d = parseDate(key); return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`; };

/** 時刻から「朝・昼・夕・夜」 */
export const mealLabel = (h) => (h >= 4 && h <= 9 ? '朝' : h >= 10 && h <= 14 ? '昼' : h >= 15 && h <= 19 ? '夕' : '夜');

/** PDF（印刷）に入れる内容（アプリの PdfSection と同じ） */
export const PDF_SECTIONS = [
  ['PROFILE', 'プロフィール', true], ['PHOTO', 'トップ画像', true], ['DOCUMENTS', '書類の画像（診断書・血統書など）', false],
  ['WEIGHT', '体重', true], ['FOOD', 'ごはん', true], ['TOILET', 'うんち・おしっこ', true], ['TOILET_PHOTOS', 'トイレの写真', false],
  ['MOOD', '機嫌', true], ['RECORDS', 'そのほかの記録（体温・薬など）', true], ['SCHEDULE', '予定・通院', true],
  ['DIARY', '日記', false], ['EXPENSE', '支出', false],
];

export const kcalPerUnit = (f) => (f.baseAmount > 0 ? f.kcalPerBase / f.baseAmount : 0);
const servingsPerDay = (item) => (item.cycleDays <= 0 ? 0 : item.timesPerCycle / item.cycleDays);
export const roundAmount = (a) => (a < 10 ? Math.round(a * 2) / 2 : Math.round(a));

/** ごはんプラン：量が決まったものを先に足し、残りを「自動」のフードで分ける */
export function plan(target, items) {
  const isAuto = ({ item, food }) => item.auto && food.divisible;
  const lines = new Map();
  let fixedKcal = 0;
  for (const p of items.filter((x) => !isAuto(x))) {
    const daily = p.item.amount * kcalPerUnit(p.food) * servingsPerDay(p.item);
    fixedKcal += daily;
    lines.set(p.item.id, { ...p, amount: p.item.amount, dailyKcal: daily });
  }
  const autos = items.filter(isAuto);
  const share = autos.length ? Math.max(0, target - fixedKcal) / autos.length : 0;
  for (const p of autos) {
    const perDay = servingsPerDay(p.item);
    const k = kcalPerUnit(p.food);
    const amount = roundAmount(perDay > 0 && k > 0 ? share / perDay / k : 0);
    lines.set(p.item.id, { ...p, amount, dailyKcal: amount * k * perDay });
  }
  return items.map((p) => lines.get(p.item.id));
}

export function formatAmount(v) {
  const r = Math.round(v * 10) / 10;
  return Number.isInteger(r) ? String(r) : r.toFixed(1);
}
export const formatKcal = (v) => `${Math.round(v)} kcal`;
export const cycleText = (times, days) => (days === 1 ? `毎日${times}回` : days === 7 && times === 1 ? '週1回' : `${days}日に${times}回`);

export const isAbnormal = (log) => (log.flags || []).length > 0 || ABNORMAL_CONDITIONS.includes(log.condition);
export const excretionDetail = (log) =>
  [log.condition, (log.flags || []).length ? log.flags.map((f) => `⚠${f}`).join('・') : null, log.note || null].filter(Boolean).join('　');
