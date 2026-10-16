// 検索結果ページに注入され、商品一覧（と追加ページ分）を集めてアプリ側に渡す。
// ページ構造が変わった場合は、まずこのCONFIGを調整する
(function () {
  if (window.Yano) return;

  const CONFIG = {
    itemLinkSelector: 'a[href*="/jp/auction/"]',
    cardSelectorCandidates: ['li.Product', 'li[class*="Product"]', 'div[class*="Product"]', 'article'],
    nextPageLinkSelectorCandidates: ['a[rel="next"]', 'a.Pager__link--next', 'a[class*="next"]'],
    maxItemsPerRun: 300,
  };

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
    const m = (card.textContent || '').match(/([\d,]{2,})\s*円/);
    return m ? m[1] + '円' : '';
  }

  function extractTimeLeft(card) {
    const m = (card.textContent || '').match(/残り[^\s　]{1,20}/);
    return m ? m[0] : '';
  }

  function extractImage(card) {
    const img = card.querySelector('img');
    if (!img) return '';
    return img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-original') || '';
  }

  function extractItemsFromDocument(doc) {
    const byId = new Map();
    for (const a of Array.from(doc.querySelectorAll(CONFIG.itemLinkSelector))) {
      const id = getAuctionId(a.href);
      if (!id || byId.has(id)) continue;
      const card = findCard(a);
      const title = (a.textContent || '').trim() || (card.querySelector('img') || {}).alt || '(タイトル不明)';
      byId.set(id, {
        auctionId: id,
        url: a.href.split('?')[0],
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

  function isSearchPage() {
    return /^\/(search\/search|opensearch|category\/list\/)/.test(location.pathname);
  }

  async function collectItems(extraPages) {
    let items = extractItemsFromDocument(document);
    let nextUrl = findNextPageUrl(document);
    let fetched = 0;
    while (nextUrl && fetched < extraPages && items.length < CONFIG.maxItemsPerRun) {
      AndroidBridge.onProgress('商品一覧を取得中… (' + (fetched + 1) + '/' + extraPages + 'ページ追加)');
      try {
        const res = await fetch(nextUrl, { credentials: 'include' });
        if (!res.ok) break;
        const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
        items = items.concat(extractItemsFromDocument(doc));
        nextUrl = findNextPageUrl(doc);
      } catch (e) {
        break;
      }
      fetched++;
    }
    const seen = new Set();
    return items
      .filter((it) => (seen.has(it.auctionId) ? false : (seen.add(it.auctionId), true)))
      .slice(0, CONFIG.maxItemsPerRun);
  }

  window.Yano = {
    async collect(extraPages) {
      try {
        if (!isSearchPage()) {
          AndroidBridge.onError('検索結果のページを開いてから実行してください');
          return;
        }
        AndroidBridge.onProgress('商品一覧を取得中…');
        const items = await collectItems(extraPages);
        if (items.length === 0) {
          AndroidBridge.onError('商品が見つかりませんでした（ページ構造が変わった可能性があります）');
          return;
        }
        AndroidBridge.onItems(JSON.stringify(items));
      } catch (e) {
        AndroidBridge.onError('一覧の取得に失敗しました: ' + e);
      }
    },
  };
})();
