// Yahoo!オークションのページ構造が変わった場合は、まずこのCONFIGを調整する
const CONFIG = {
  itemLinkSelector: 'a[href*="/jp/auction/"]',
  cardSelectorCandidates: ['li.Product', 'li[class*="Product"]', 'div[class*="Product"]', 'article'],
  nextPageLinkSelectorCandidates: [
    'a[rel="next"]',
    'a.Pager__link--next',
    'a[class*="next"]',
  ],
  cacheKey: 'yano_autoextend_cache_v1',
  cacheTtlMs: 12 * 60 * 60 * 1000,
  concurrency: 3,
  requestStaggerMs: 350,
  iframeLoadTimeoutMs: 8000,
  maxItemsPerRun: 300,
};

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function getAuctionId(url) {
  const m = url.match(/\/jp\/auction\/([a-zA-Z0-9]+)/);
  return m ? m[1] : null;
}

function findCard(anchor) {
  for (const sel of CONFIG.cardSelectorCandidates) {
    const card = anchor.closest(sel);
    if (card) return card;
  }
  return anchor.parentElement ? anchor.parentElement.parentElement || anchor.parentElement : anchor;
}

function extractPrice(card) {
  const text = card.textContent || '';
  const m = text.match(/([\d,]{2,})\s*円/);
  return m ? m[1] + '円' : '';
}

function extractTimeLeft(card) {
  const text = card.textContent || '';
  const m = text.match(/残り[^\s　]{1,20}/);
  return m ? m[0] : '';
}

function extractImage(card) {
  const img = card.querySelector('img');
  if (!img) return '';
  return img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-original') || '';
}

function extractItemsFromDocument(doc) {
  const anchors = Array.from(doc.querySelectorAll(CONFIG.itemLinkSelector));
  const byId = new Map();
  for (const a of anchors) {
    const url = a.href;
    const id = getAuctionId(url);
    if (!id || byId.has(id)) continue;
    const card = findCard(a);
    const title = (a.textContent || '').trim() || (card.querySelector('img') || {}).alt || '(タイトル不明)';
    byId.set(id, {
      auctionId: id,
      url: url.split('?')[0],
      title,
      image: extractImage(card),
      price: extractPrice(card),
      timeLeft: extractTimeLeft(card),
    });
  }
  return Array.from(byId.values());
}

function findNextPageUrl(doc) {
  for (const sel of CONFIG.nextPageLinkSelectorCandidates) {
    const a = doc.querySelector(sel);
    if (a && a.href) return a.href;
  }
  return null;
}

async function fetchHtml(url) {
  const res = await fetch(url, { credentials: 'include' });
  if (!res.ok) throw new Error('request failed: ' + url);
  return await res.text();
}

function storageGet(key) {
  return new Promise((resolve) => {
    chrome.storage.local.get([key], (result) => resolve(result[key]));
  });
}

function storageSet(key, value) {
  return new Promise((resolve) => {
    chrome.storage.local.set({ [key]: value }, () => resolve());
  });
}

async function loadCache() {
  const raw = await storageGet(CONFIG.cacheKey);
  return raw || {};
}

async function saveCache(cache) {
  await storageSet(CONFIG.cacheKey, cache);
}

function parseAutoExtend(html) {
  const text = html.replace(/<[^>]+>/g, ' ');
  const m = text.match(/自動延長[\s\S]{0,30}?(あり|なし)/);
  return m ? m[1] : null;
}

async function getAutoExtendStatus(item, cache) {
  const cached = cache[item.auctionId];
  if (cached && Date.now() - cached.ts < CONFIG.cacheTtlMs) {
    return cached.status;
  }
  try {
    const html = await fetchHtml(item.url);
    const status = parseAutoExtend(html) || '不明';
    cache[item.auctionId] = { status, ts: Date.now() };
    return status;
  } catch (e) {
    return '不明';
  }
}

async function mapWithConcurrency(items, limit, fn, onProgress) {
  let idx = 0;
  let done = 0;
  const results = new Array(items.length);
  async function worker() {
    while (idx < items.length) {
      const cur = idx++;
      results[cur] = await fn(items[cur]);
      done++;
      if (onProgress) onProgress(done, items.length);
      await sleep(CONFIG.requestStaggerMs);
    }
  }
  const workers = Array.from({ length: Math.min(limit, items.length) }, worker);
  await Promise.all(workers);
  return results;
}

function findWatchButton(doc) {
  const candidates = doc.querySelectorAll('button, a');
  for (const el of candidates) {
    const label = (el.getAttribute('aria-label') || '') + (el.textContent || '');
    if (label.includes('ウォッチリスト') && !label.includes('削除') && !label.includes('解除')) {
      return el;
    }
  }
  return null;
}

function looksWatched(btn) {
  const label =
    (btn.getAttribute('aria-label') || '') + (btn.textContent || '') + (btn.className || '');
  return /解除|削除|watched|active/i.test(label) || btn.getAttribute('aria-pressed') === 'true';
}

function showManualFallback(statusEl, url) {
  statusEl.textContent = '';
  const btn = document.createElement('button');
  btn.className = 'yano-manual-btn';
  btn.textContent = '手動で開く';
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    window.open(url, '_blank', 'noopener');
  });
  statusEl.appendChild(btn);
}

function addToWatchlist(item, statusEl) {
  statusEl.textContent = '追加中…';
  const iframe = document.createElement('iframe');
  iframe.style.display = 'none';
  let settled = false;

  const timeoutId = setTimeout(() => {
    if (settled) return;
    settled = true;
    cleanup();
    statusEl.textContent = '⚠ 自動追加できませんでした';
    showManualFallback(statusEl, item.url);
  }, CONFIG.iframeLoadTimeoutMs);

  function cleanup() {
    clearTimeout(timeoutId);
    setTimeout(() => iframe.remove(), 500);
  }

  iframe.addEventListener('load', () => {
    if (settled) return;
    let doc = null;
    try {
      doc = iframe.contentDocument;
    } catch (e) {
      doc = null;
    }
    if (!doc) {
      settled = true;
      cleanup();
      statusEl.textContent = '⚠ 自動追加できませんでした';
      showManualFallback(statusEl, item.url);
      return;
    }
    const btn = findWatchButton(doc);
    if (!btn) {
      settled = true;
      cleanup();
      statusEl.textContent = '⚠ ボタンが見つかりません';
      showManualFallback(statusEl, item.url);
      return;
    }
    btn.click();
    setTimeout(() => {
      settled = true;
      const ok = looksWatched(btn);
      cleanup();
      if (ok) {
        statusEl.textContent = '✓ 追加済み';
      } else {
        statusEl.textContent = '⚠ 未確認';
        showManualFallback(statusEl, item.url);
      }
    }, 1200);
  });

  iframe.src = item.url;
  document.body.appendChild(iframe);
}

function renderResults(items) {
  const overlay = document.createElement('div');
  overlay.id = 'yano-overlay';
  const modal = document.createElement('div');
  modal.id = 'yano-modal';

  const header = document.createElement('h2');
  header.textContent = `自動延長なしの商品 (${items.length}件)`;
  const closeBtn = document.createElement('button');
  closeBtn.id = 'yano-close';
  closeBtn.textContent = '×';
  closeBtn.addEventListener('click', () => overlay.remove());
  header.appendChild(closeBtn);
  modal.appendChild(header);

  if (items.length === 0) {
    const empty = document.createElement('p');
    empty.textContent = '自動延長なしの商品は見つかりませんでした。';
    modal.appendChild(empty);
  }

  for (const item of items) {
    const row = document.createElement('div');
    row.className = 'yano-item';
    row.addEventListener('click', () => window.open(item.url, '_blank', 'noopener'));

    if (item.image) {
      const img = document.createElement('img');
      img.src = item.image;
      img.loading = 'lazy';
      row.appendChild(img);
    }

    const info = document.createElement('div');
    info.className = 'yano-item-info';
    const titleEl = document.createElement('span');
    titleEl.className = 'yano-item-title';
    titleEl.textContent = item.title;
    const meta = document.createElement('div');
    meta.className = 'yano-item-meta';
    meta.textContent = [item.price, item.timeLeft].filter(Boolean).join(' / ');
    info.appendChild(titleEl);
    info.appendChild(meta);
    row.appendChild(info);

    const watchBtn = document.createElement('button');
    watchBtn.className = 'yano-watch-btn';
    watchBtn.textContent = '☆ ウォッチ追加';
    const statusEl = document.createElement('span');
    statusEl.className = 'yano-watch-status';
    watchBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      addToWatchlist(item, statusEl);
    });
    row.appendChild(watchBtn);
    row.appendChild(statusEl);

    modal.appendChild(row);
  }

  overlay.appendChild(modal);
  document.body.appendChild(overlay);
}

async function collectItemsAcrossPages(extraPages) {
  let items = extractItemsFromDocument(document);
  let nextUrl = findNextPageUrl(document);
  let pagesFetched = 0;
  while (nextUrl && pagesFetched < extraPages && items.length < CONFIG.maxItemsPerRun) {
    try {
      const html = await fetchHtml(nextUrl);
      const doc = new DOMParser().parseFromString(html, 'text/html');
      items = items.concat(extractItemsFromDocument(doc));
      nextUrl = findNextPageUrl(doc);
    } catch (e) {
      break;
    }
    pagesFetched++;
  }
  const seen = new Set();
  return items.filter((it) => {
    if (seen.has(it.auctionId)) return false;
    seen.add(it.auctionId);
    return true;
  }).slice(0, CONFIG.maxItemsPerRun);
}

async function run(extraPages, progressEl) {
  progressEl.textContent = '商品一覧を取得中…';
  const items = await collectItemsAcrossPages(extraPages);
  if (items.length === 0) {
    progressEl.textContent = '商品が見つかりませんでした。';
    return;
  }

  const cache = await loadCache();
  progressEl.textContent = `自動延長の状態を確認中… 0/${items.length}`;
  const statuses = await mapWithConcurrency(
    items,
    CONFIG.concurrency,
    (item) => getAutoExtendStatus(item, cache),
    (done, total) => {
      progressEl.textContent = `自動延長の状態を確認中… ${done}/${total}`;
    }
  );
  await saveCache(cache);

  const filtered = items.filter((_, i) => statuses[i] === 'なし');
  progressEl.textContent = `完了: ${items.length}件中 ${filtered.length}件が自動延長なし`;
  renderResults(filtered);
}

function injectPanel() {
  const panel = document.createElement('div');
  panel.id = 'yano-panel';

  const label = document.createElement('label');
  label.textContent = '追加取得ページ数:';
  label.style.fontSize = '12px';

  const pagesInput = document.createElement('input');
  pagesInput.id = 'yano-pages';
  pagesInput.type = 'number';
  pagesInput.min = '0';
  pagesInput.max = '9';
  pagesInput.value = '0';

  const runBtn = document.createElement('button');
  runBtn.id = 'yano-run';
  runBtn.textContent = '自動延長なしを抽出';

  const progress = document.createElement('div');
  progress.id = 'yano-progress';

  runBtn.addEventListener('click', () => {
    const extraPages = Math.max(0, Math.min(9, parseInt(pagesInput.value, 10) || 0));
    runBtn.disabled = true;
    run(extraPages, progress).finally(() => {
      runBtn.disabled = false;
    });
  });

  panel.appendChild(label);
  panel.appendChild(pagesInput);
  panel.appendChild(document.createElement('br'));
  panel.appendChild(runBtn);
  panel.appendChild(progress);
  document.body.appendChild(panel);
}

injectPanel();
