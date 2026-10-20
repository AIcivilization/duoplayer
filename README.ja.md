<p align="center">
  <img src="docs/icon.png" width="128" alt="DuoPlayer Hi-Fi">
</p>

<h1 align="center">DuoPlayer Hi-Fi</h1>

<p align="center">
  <b>2 つのロスレス音源を同時に再生 ― ソニー Android ウォークマンのために作りました。</b><br>
  音楽 + 雨音、曲 + メトロノーム、オーディオブック + ホワイトノイズ……A と B をワンタップで同時再生し、64bit エンジンでミックスします。
</p>

<p align="center">
  <a href="https://github.com/AIcivilization/duoplayer/releases/latest"><img src="https://img.shields.io/github/v/release/AIcivilization/duoplayer?label=download&color=E3B267" alt="ダウンロード"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Sony%20Walkman-A100%20%7C%20ZX500%20%7C%20WM1M2%20%7C%20A300%20%7C%20ZX700-111111" alt="Sony Walkman">
  <img src="https://img.shields.io/badge/Hi--Res-192%20kHz%20%2F%2032--bit-8A2BE2" alt="Hi-Res">
  <img src="https://img.shields.io/badge/languages-17-0A7EA4" alt="17 languages">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/AIcivilization/duoplayer?color=lightgrey" alt="MIT"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a> · <b>日本語</b>
</p>

## 主な機能

- **A / B の組み合わせカード**：A タブ・B タブをタップしてそれぞれを設定します。再生ボタンは 1 つだけで、押すと A と B を同時に再生します。
- **明るい = 再生、グレー = オフ**：再生中でも各トラックをタップしてオン・オフできます。
- **ブックマーク**：A + B の組み合わせを好きな名前で保存できます。保存されるのはファイル、歌詞、音量、定位、リピート、オン・オフの設定です。次回からワンタップで再生できます。
- **連続再生**：
  - ブックマークリスト：組み合わせを 1 つずつ順に再生します。
  - ファイルリスト：ファイルを順に A で再生し、B は雨音などを流し続けます。
- **対訳歌詞**：
  - `.lrc` ファイルと、FLAC / MP3 / DSF / WAV に埋め込まれた歌詞に対応します。
  - 文字コード（GBK / UTF-8 / UTF-16）は自動で判別します。
  - 原文と訳文を並べて表示し、行をタップするとその位置へ移動します。
- **ロスレスエンジン**：
  - FLAC / WAV / AIFF / DSD をアプリ内でデコードします。
  - A と B は 64bit 浮動小数点でミックスします。
  - サンプリングレートが同じときはリサンプルせず、**ビットパーフェクト**のまま出力します。
- **バックアップと復元**、再インストール後の**フォルダ再リンク**、GitHub からの**アプリ内アップデート**。
- **17 言語**に対応し、システムの言語に自動で切り替わります。

## 対応機種

| 機種 | Android | 状態 |
|---|---|---|
| **NW-ZX505 / ZX507** | 9 | メインターゲット |
| **NW-A105 / A106 / A107** | 9 | 動作するはず（ZX500 と同じプラットフォーム） |
| **NW-WM1AM2 / WM1ZM2** | 11 | 動作するはず |
| **NW-A306 / A307** | 12 | 動作するはず |
| **NW-ZX706 / ZX707** | 12 | 動作するはず |
| Android 9 以降のスマホ・DAP | 9+ | 動作します |

> 現時点では Android 9 エミュレーターでのみテストしています。実機での結果を [デバイスレポート](https://github.com/AIcivilization/duoplayer/issues/new?template=device-report.md) で教えてください。機種名、ファームウェア、アプリ上部に表示される出力サンプリングレートを書いていただけると助かります。

## インストール

1. **[Releases](https://github.com/AIcivilization/duoplayer/releases/latest)** から `duoplayer-x.y.apk` をダウンロードします。
2. ウォークマンにコピーし、ファイルマネージャーで開いてインストールします。
3. 以降のバージョンはアプリ内（リスト → 更新を確認）でアップデートできます。

## ウォークマンで最高の音質を得るには

- **設定 → 音 → 「High-Res streaming（ハイレゾストリーミング）」をオン**にしてください。A100 / ZX500 シリーズでは、オフのとき他社アプリの出力が 48kHz/16bit に制限され、オンにすると 192kHz/32bit になります。
- A / B の音量は 0 dB のままにして、本体の音量ボタンで調整してください（S-Master HX が担当します）。
- 3.5mm と 4.4mm バランス出力の両方で使えます。側面のボタンで再生・一時停止・前へ・次へを操作できます。

## ライセンス

[MIT](LICENSE)。Sony、Walkman、S-Master はソニーグループ株式会社の商標です。本プロジェクトはソニーとは関係のない個人開発です。
