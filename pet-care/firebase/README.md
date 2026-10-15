# Firebase の準備（家族共有・Web 版）

一度だけの作業です。Google アカウントがあれば無料でできます（クレジットカード不要）。

## 1. プロジェクトとデータベース

1. https://console.firebase.google.com/ を開き「プロジェクトを作成」。名前は `uchinoko-gohan` など自由。
   Google アナリティクスはオフで構いません。
2. 「構築 → Firestore Database」→「データベースを作成」。ロケーションは `asia-northeast1（東京）`、
   「本番環境モード」で作成します。
3. Firestore の「ルール」タブに `firestore.rules` の中身を貼り付けて「公開」。
   （アプリの更新で記録の種類が増えたときは、ここをもう一度貼り直してください）

## 2. アプリの登録

1. プロジェクトのトップで「</>」（ウェブ）を押してアプリを登録（ニックネームは自由、Hosting のチェックは不要）。
   表示される `firebaseConfig = { apiKey: …, authDomain: …, projectId: …, appId: … }` を控えます。
2. 同じ画面で Android のアイコンを押してアプリを登録します。
   - パッケージ名：`com.okamejiiya.petcare`
   - デバッグ用の署名証明書 SHA-1：`E0:BC:64:BA:3C:3C:3A:88:F5:3F:1C:CF:F2:8D:74:23:BD:C4:67:EA`
   - 表示される「アプリ ID」（`1:…:android:…`）を控えます（google-services.json のダウンロードは不要）

## 3. ログイン（匿名・Google）

1. 「構築 → Authentication」→「始める」→「Sign-in method」で **匿名** を有効にします。
2. 同じ画面で **Google** を有効にします（サポートメールは自分のアドレス）。
   有効にしたあと Google の行を開き、「ウェブ SDK 構成」の **ウェブ クライアント ID** を控えます。
3. 「Authentication → 設定 → 承認済みドメイン」に `okame-jiiya.github.io` を追加します（Web 版の Google ログイン用）。

## 4. 不正利用対策（App Check）

アプリ以外から Firebase を使われないようにする仕組みです。いまは「計測だけ」にしておき、
Google Play で公開するときに「適用」をオンにします。

1. 「構築 → App Check」を開き、Android アプリに **Play Integrity** を登録します。
2. Web アプリに **reCAPTCHA Enterprise** を登録します。表示されるサイトキーを控えます
   （reCAPTCHA Enterprise の画面でキーを作るとき、ドメインに `okame-jiiya.github.io` を入れます）。
3. 「適用」はまだオンにしないでください。いまの APK は Google Play を通していないので、
   オンにするとアプリから使えなくなります。Play で公開したあとにオンにします。

## 5. Web 版の公開

GitHub のリポジトリの Settings → Pages で、Source を「Deploy from a branch」、Branch を `gh-pages` / `(root)` にして保存。

## 6. 設定ファイル

控えた値を `pet-care/firebase-config.json` として置くと、アプリと Web 版の両方で共有機能が使えるようになります。
どれも公開されて問題ない値です（データはルールで守られます）。

```json
{
  "apiKey": "…",
  "authDomain": "….firebaseapp.com",
  "projectId": "…",
  "storageBucket": "….firebasestorage.app",
  "messagingSenderId": "…",
  "appId": "1:…:web:…",
  "androidAppId": "1:…:android:…",
  "googleWebClientId": "….apps.googleusercontent.com",
  "recaptchaSiteKey": "…"
}
```

`googleWebClientId` が無いと Google ログインのボタンは出ません。`recaptchaSiteKey` が無いと Web 版の App Check は使いません。

## Google Play で公開するとき

- 公開用の署名鍵（Play アプリ署名）の SHA-1 を、Firebase の Android アプリに追加する（Google ログイン用）
- App Check の「適用」をオンにする（Firestore と Authentication）
- プライバシーポリシーを用意し、Play Console の「データ セーフティ」に「家族共有をオンにしたとき記録をクラウドに保存する」ことを記入する
- 利用者が増えたら、Firebase の料金プランを従量課金（Blaze）に切り替える
