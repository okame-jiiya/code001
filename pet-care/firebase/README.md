# Firebase の準備（家族共有・Web 版）

一度だけの作業です。Google アカウントがあれば無料でできます（クレジットカード不要）。

1. https://console.firebase.google.com/ を開き「プロジェクトを作成」。名前は `uchinoko-gohan` など自由。
   Google アナリティクスはオフで構いません。
2. プロジェクトのトップで「</>」（ウェブ）を押してアプリを登録（ニックネームは自由、Hosting のチェックは不要）。
   表示される `firebaseConfig = { apiKey: …, authDomain: …, projectId: …, appId: … }` を控えます。
3. 左のメニュー「構築 → Authentication」→「始める」→「Sign-in method」で **匿名** を有効にします。
4. 「構築 → Firestore Database」→「データベースを作成」。ロケーションは `asia-northeast1（東京）`、
   「本番環境モード」で作成します。
5. Firestore の「ルール」タブに `firestore.rules` の中身を貼り付けて「公開」。
6. GitHub のリポジトリの Settings → Pages で、Source を「Deploy from a branch」、Branch を `gh-pages` / `(root)` にして保存。

手順 2 の値を `pet-care/firebase-config.json` として置くと、アプリと Web 版の両方で共有機能が使えるようになります。

```json
{
  "apiKey": "…",
  "authDomain": "….firebaseapp.com",
  "projectId": "…",
  "storageBucket": "….firebasestorage.app",
  "messagingSenderId": "…",
  "appId": "1:…:web:…"
}
```

無料枠（Spark プラン）は 1 日あたり読み取り 5 万回・書き込み 2 万回までで、家族数人の記録なら十分に収まります。
