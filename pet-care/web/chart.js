// 推移のグラフ（SVG）。何本かの線を重ね、タップした日の値を上に出す。アプリの LineChart / MultiLineChart と同じ見た目。

const W = 340;
const LEFT = 40;
const RIGHT = W - 10;
const pad2 = (n) => String(n).padStart(2, '0');
const dayNum = (key) => { const [y, m, d] = key.split('-').map(Number); return Date.UTC(y, m - 1, d) / 86400000; };
const md = (key) => { const [, m, d] = key.split('-').map(Number); return `${m}/${d}`; };
const fmt = (v) => { const r = Math.round(v * 10) / 10; return Math.abs(r) >= 10000 ? `${Math.round(r / 1000) / 10}万` : Number.isInteger(r) ? String(r) : r.toFixed(1); };
const escText = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/** グラフごとの日付の並び（タップした位置から日付を探すため） */
const charts = new Map();
/** グラフごとに選んでいる日 */
const selected = new Map();

/**
 * series: [{ name, color, points: [[dateKey, value], ...] }]
 * opts: { id, unit, goal, height }
 */
export function lineChart(series, { id, unit = '', goal = null, height = 190 } = {}) {
  const all = series.flatMap((s) => s.points);
  if (!all.length) return '';
  const dates = [...new Set(all.map((p) => p[0]))].sort();
  charts.set(id, dates);
  let sel = selected.get(id);
  if (!dates.includes(sel)) sel = dates[dates.length - 1];
  const values = all.map((p) => p[1]).concat(goal != null ? [goal] : []);
  let lo = Math.min(...values);
  let hi = Math.max(...values);
  if (hi - lo < 0.4) { const mid = (hi + lo) / 2; lo = mid - 0.2; hi = mid + 0.2; }
  const dataLo = lo; const dataHi = hi;
  const padV = (hi - lo) * 0.12; lo -= padV; hi += padV;
  const top = 10; const bottom = height - 20;
  const d0 = dayNum(dates[0]); const d1 = dayNum(dates[dates.length - 1]);
  const x = (k) => (d1 === d0 ? (LEFT + RIGHT) / 2 : LEFT + (RIGHT - LEFT) * (dayNum(k) - d0) / (d1 - d0));
  const y = (v) => bottom - (bottom - top) * ((v - lo) / (hi - lo));
  const dots = Math.max(...series.map((s) => s.points.length)) <= 40;

  let svg = '';
  for (let k = 0; k <= 2; k++) {
    const v = dataLo + (dataHi - dataLo) * k / 2;
    svg += `<line x1="${LEFT}" x2="${RIGHT}" y1="${y(v).toFixed(1)}" y2="${y(v).toFixed(1)}" class="gl"/>`
      + `<text x="${LEFT - 5}" y="${(y(v) + 3).toFixed(1)}" text-anchor="end">${fmt(v)}</text>`;
  }
  svg += `<text x="${LEFT}" y="${height - 5}">${md(dates[0])}</text>`;
  if (d1 !== d0) svg += `<text x="${RIGHT}" y="${height - 5}" text-anchor="end">${md(dates[dates.length - 1])}</text>`;
  if (goal != null) svg += `<line x1="${LEFT}" x2="${RIGHT}" y1="${y(goal).toFixed(1)}" y2="${y(goal).toFixed(1)}" class="goal"/>`;
  svg += `<line x1="${x(sel).toFixed(1)}" x2="${x(sel).toFixed(1)}" y1="${top}" y2="${bottom}" class="guide"/>`;
  for (const s of series) {
    if (s.points.length > 1) {
      svg += `<polyline fill="none" stroke="${s.color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" points="${
        s.points.map((p) => `${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join(' ')}"/>`;
    }
    for (const p of s.points) {
      const on = p[0] === sel;
      if (!dots && !on && s.points.length > 1) continue;
      svg += `<circle cx="${x(p[0]).toFixed(1)}" cy="${y(p[1]).toFixed(1)}" r="${on ? 4.5 : 3}" fill="${s.color}" stroke="#fff" stroke-width="1.5"/>`;
    }
  }
  const head = series.map((s) => {
    const v = s.points.filter((p) => p[0] === sel).pop();
    return v ? `<span style="color:${s.color}">${series.length > 1 ? `${escText(s.name)} ` : ''}${fmt(v[1])}${escText(unit)}</span>` : '';
  }).filter(Boolean).join('　');
  return `<div class="chart"><div class="chart-sel"><b>${md(sel)}</b>　${head}</div>
    <svg viewBox="0 0 ${W} ${height}" data-act="chart" data-chart="${escText(id)}">${svg}</svg>
    ${series.length > 1 ? `<div class="row wrap legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${escText(s.name)}</span>`).join('')}</div>` : ''}</div>`;
}

/** グラフをタップしたとき：いちばん近い日を選ぶ */
export function tapChart(svg, ev) {
  const id = svg.dataset.chart;
  const dates = charts.get(id);
  if (!dates?.length) return false;
  const rect = svg.getBoundingClientRect();
  const vx = (ev.clientX - rect.left) * (W / rect.width);
  const d0 = dayNum(dates[0]); const d1 = dayNum(dates[dates.length - 1]);
  const day = d1 === d0 ? d0 : d0 + ((vx - LEFT) / (RIGHT - LEFT)) * (d1 - d0);
  let best = dates[0];
  for (const k of dates) if (Math.abs(dayNum(k) - day) < Math.abs(dayNum(best) - day)) best = k;
  selected.set(id, best);
  return true;
}

/** 線の色（アイコンの背景色を濃くしたもの。同じ子は同じ色） */
export const LINE_COLORS = ['#E8738F', '#E89B5C', '#C9A800', '#4FAF86', '#4F95C9', '#8C6FD1', '#A9805A', '#7D7D8C'];
export const TOTAL_COLOR = '#5A4A55';
export const lineColor = (pet) => LINE_COLORS[(((pet.colorIndex || 0) % LINE_COLORS.length) + LINE_COLORS.length) % LINE_COLORS.length];
export { pad2 };
