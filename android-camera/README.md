# BlackCam（黒画面ビデオカメラ）

Pixel 10 Pro 向けの録画専用カメラアプリです。

- 起動するとすぐに録画が始まり、画面は真っ黒のまま（明るさも最低）になります。
- ホームに戻る・戻るボタン・画面オフで録画が止まり、動画が保存されます。保存先は設定で選べます（既定はアルバムの `DCIM/BlackCam`。ムービー内の `Movies/BlackCam`、または好きなフォルダも選べます）。
- 黒い画面をタップすると設定が開きます。録画はそのまま続き、変更は次回起動時から反映されます。
  - カメラ：背面の超広角 / メイン / 2x / 望遠 5x、前面
  - 解像度：4K / フルHD / HD、フレームレート：30 / 60 fps（端末が対応するものだけ表示）
  - コーデック：H.264 / H.265 (HEVC)
  - ビットレート：自動 または 4〜100 Mbps
  - 音声録音のオン・オフ、手ぶれ補正のオン・オフ
- 約 3.9GB ごとに自動でファイルを分けて保存します（長時間録画でもファイルが壊れないように）。

## インストール

1. [Releases の「BlackCam (latest debug build)」](https://github.com/okame-jiiya/code001/releases/tag/android-camera-latest)から `BlackCam-debug.apk` をダウンロードします（ログイン不要。Pixel のブラウザから直接ダウンロードできます）。
2. ダウンロードした apk を Pixel にコピーし、ファイルアプリから開いてインストールします（「提供元不明のアプリ」の許可が必要です）。
3. 初回起動時にカメラとマイクの権限を許可してください。

## ビルド

```sh
cd android-camera
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Android SDK（compileSdk 36）と JDK 17 が必要です。
