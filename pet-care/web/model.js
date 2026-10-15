// うちの子ごはん帳 Web 版：データの形と計算（Android アプリの model/ と同じ内容）

export const COLLECTIONS = ['pets', 'foods', 'plans', 'feedLogs', 'excretionLogs', 'weights', 'reminders', 'health', 'moods'];

export const SPECIES = {
  CAT: { label: '猫', emoji: '🐱', icons: ['🐱', '🐈', '🐈‍⬛', '😺', '😸'] },
  DOG: { label: '犬', emoji: '🐶', icons: ['🐶', '🐕', '🐩', '🦮', '🐕‍🦺'] },
};

export const PET_COLORS = ['#FFC9D6', '#FFD8B5', '#FFF0A8', '#C6EBD9', '#C4E1F6', '#DCCFF5', '#E6D3C0', '#DCDCE3'];

export const EXCRETION = {
  POOP: { label: 'うんち', emoji: '💩', conditions: ['かたい', 'ふつう', 'やわらかい', '下痢'], flags: ['血が混じる', '粘液がつく', '異物が混じる', 'トイレ以外でした'] },
  PEE: { label: 'おしっこ', emoji: '💧', conditions: ['少ない', 'ふつう', '多い'], flags: ['血が混じる', '何度も行くのに少ししか出ない', 'トイレ以外でした'] },
};
export const ABNORMAL_CONDITIONS = ['下痢'];

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
export const docId = (collection, item) => (collection === 'moods' ? `${item.petId}_${item.date}` : String(item.id));

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

export function targetKcal(pet, today = new Date()) {
  const st = stage(pet.species, ageInMonths(parseDate(pet.birthDate), today));
  const growing = st === 'BABY_EARLY' || st === 'BABY';
  const dog = pet.species === 'DOG';
  let basis;
  if (growing) basis = pet.weightKg;
  else if (pet.targetWeightKg != null && pet.targetWeightKg > 0) basis = pet.targetWeightKg;
  else basis = pet.weightKg / (1 + 0.15 * (Math.min(5, Math.max(1, pet.bcs)) - 3));
  let factor;
  if (st === 'BABY_EARLY') factor = 3.0;
  else if (st === 'BABY') factor = dog ? 2.0 : 2.5;
  else if (pet.bcs >= 4) factor = dog ? 1.0 : 0.8;
  else if (dog && st === 'SENIOR') factor = 1.4;
  else if (pet.neutered) factor = dog ? 1.6 : 1.2;
  else factor = dog ? 1.8 : 1.4;
  if (pet.customKcal != null && pet.customKcal > 0) return pet.customKcal;
  return rer(basis) * factor;
}

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
