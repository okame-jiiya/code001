// 検索結果ページに注入され、商品一覧（と追加ページ分）を集めてアプリ側に渡す。
// ページ構造が変わった場合は、まずこのCONFIGを調整する
(function () {
  if (window.Yano) return;

  const CONFIG = {
    itemLinkSelector: 'a[href*="/jp/auction/"]',
    cardSelectorCandidates: ['li.Product', 'li[class*="Product"]', 'div[class*="Product"]', 'article'],
    pageSize: 100,
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

  // 「1〜50件/534件」の表示から総件数を読む（読めなければnull）
  function parseTotalCount(doc) {
    const m = (doc.body ? doc.body.textContent : '').match(/\d+\s*〜\s*\d+\s*件\s*\/\s*([\d,]+)\s*件/);
    return m ? parseInt(m[1].replace(/,/g, ''), 10) : null;
  }

  function isSearchPage() {
    return /^\/(search\/search|opensearch|category\/list\/)/.test(location.pathname);
  }

  // 「次へ」リンクは探さず、URLの開始位置(b)と表示件数(n)を書き換えて次のページを取りに行く
  async function collectItems(maxItems) {
    const items = extractItemsFromDocument(document);
    const seen = new Set(items.map((it) => it.auctionId));
    const total = parseTotalCount(document);
    const here = new URL(location.href);
    const firstB = parseInt(here.searchParams.get('b') || '1', 10) || 1;
    const firstN = parseInt(here.searchParams.get('n') || '50', 10) || 50;
    let nextB = firstB + firstN;

    while (items.length < maxItems && (total === null || nextB <= total)) {
      AndroidBridge.onProgress('商品一覧を取得中… ' + items.length + (total ? '/' + total : '') + '件');
      const url = new URL(here.href);
      url.searchParams.set('b', String(nextB));
      url.searchParams.set('n', String(CONFIG.pageSize));
      let added = 0;
      try {
        const res = await fetch(url.href, { credentials: 'include' });
        if (!res.ok) break;
        const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
        for (const it of extractItemsFromDocument(doc)) {
          if (seen.has(it.auctionId)) continue;
          seen.add(it.auctionId);
          items.push(it);
          added++;
        }
      } catch (e) {
        break;
      }
      if (added === 0) break;
      nextB += CONFIG.pageSize;
    }
    return { items: items.slice(0, maxItems), total };
  }

  window.Yano = {
    // maxItems: 0ならこのページだけ、それ以外は最大件数
    async collect(maxItems) {
      try {
        if (!isSearchPage()) {
          AndroidBridge.onError('検索結果のページを開いてから実行してください');
          return;
        }
        AndroidBridge.onProgress('商品一覧を取得中…');
        const { items, total } = maxItems > 0
          ? await collectItems(maxItems)
          : { items: extractItemsFromDocument(document), total: parseTotalCount(document) };
        if (items.length === 0) {
          AndroidBridge.onError('商品が見つかりませんでした（ページ構造が変わった可能性があります）');
          return;
        }
        AndroidBridge.onItems(JSON.stringify(items), total === null ? -1 : total);
      } catch (e) {
        AndroidBridge.onError('一覧の取得に失敗しました: ' + e);
      }
    },
  };
})();
