Servux (panyaaa256 fork)
========================

[sakura-ryoko/servux](https://github.com/sakura-ryoko/servux) のフォークです。
本家の機能はそのまま、Litematica 向けに **サーバーサイドの図面検証・領域分析・建材リスト** を追加しています。

Servux はサーバー専用の mod です。**クライアントやシングルプレイには不要** で、マルチプレイの専用サーバーに入れて使います。
追加機能をクライアントの GUI から使うには、対応するフォーク版 Litematica
([panyaaa256/litemafork](https://github.com/panyaaa256/litemafork)) が必要です。

- [対応バージョン](#対応バージョン)
- [追加機能の概要](#追加機能の概要)
- [導入](#導入)
- [コマンド](#コマンド)
- [設定](#設定)
- [権限](#権限)
- [制限事項](#制限事項)
- [ビルド](#ビルド)

対応バージョン
--------------

| ブランチ | Minecraft | Servux | 組み合わせる Litematica (litemafork) |
|---|---|---|---|
| `26.2` | 26.2 | 0.11.6-sakura.1 | `26.2` ブランチ (0.28.8, malilib 0.29.6) |
| `1.21.11` | 1.21.11 | 0.9.9 | `1.21.11` ブランチ (0.26.16, malilib 0.27.20) |

- 2 つのブランチは同じ機能・同じ設定・同じコマンドを持つように保っています。違いは Minecraft のバージョン差だけです。
- どちらも本家の `LTS/26.2` / `LTS/1.21.11` に追従しています。
- Litematica との通信はプロトコル v2 (本家と同じ) です。古いプロトコル v1 のクライアントとは通信できません。

追加機能の概要
--------------

どの機能も Litematica の「描画距離の内側しか見えない」という制約を、サーバー側で処理することで取り除くものです。

### サーバーサイド図面検証 (Verify)

配置した図面と実際のワールドをサーバー上で比較します。クライアントの描画距離に関係なく、建築全体を検証できます。

- 不一致は `Missing` (足りない)、`Extra` (余計)、`Wrong Block` (ブロック違い)、`Wrong State` (状態違い)、`Wrong Contents` (中身違い)、`Missing Entities` (足りないエンティティ) に分類されます。
- `Wrong Contents` はチェストなどコンテナの中身の比較です。Litematica 単体の検証はブロックの状態しか比べないため、空のチェストも「正しい」扱いになりますが、この検証では中身の違いも検出します。ブロックの状態が一致している位置だけを調べるので、この件数は「正しい状態」の件数と重なります。
  - 中身を比較するかどうかは、Litematica 側の設定 (`verifierCheckContents`、既定は無効) で検証ごとに指定します。サーバー側で `verify_nbt` が無効なら比較しません。
  - 先頭から `verify_nbt_detail_positions` 件は「図面側」と「実際」の中身も結果と一緒に送り、Litematica の検証 GUI で並べて表示できます。
- `Missing Entities` は、図面にあるのにワールドにないエンティティ (額縁・絵画・防具立て・トロッコなど) です。判定ルールは Litematica の検証と同じです。
  - 同じ種類で、位置のずれが許容誤差以内のエンティティがあれば「ある」とみなします。1 体のエンティティを図面の 2 体に対応させることはしません。
  - モブ・アイテム・経験値オーブ・飛び道具は、動いたり消えたりするので調べません (防具立ては調べます)。
  - クライアントから依頼するときは、Litematica 側の設定 (`verifierCheckEntities` と `verifierEntityPositionTolerance`) に従います。コマンドから始めたときは常に調べ、許容誤差は `0.1` ブロックです。
  - Litematica 単体の検証では、エンティティの追跡範囲 (額縁などは 10 チャンク) より遠いエンティティは調べられません。サーバー側ならどれだけ遠くても調べられます。
- 結果はバッチに分けて送られます (受信確認のたびに次を送信)。数百万ブロック規模でも巨大なパケットにはなりません。
- Syncmatica で共有済みの図面は、アップロードなしでコマンドから検証できます ([Syncmatica 連携](#syncmatica-連携))。
- 結果はチャットにも出るので、mod なしのクライアントからコマンドだけでも使えます。座標をクリックするとテレポートコマンドが入力欄に入ります。

### サーバーサイド領域分析 (Analyze)

選択範囲にあるブロック・エンティティ・コンテナの中身をサーバー上で数えます。

- クライアントには、開いていないコンテナの中身が送られてきません。そのため Litematica 単体の領域分析では、サーバー上のチェストがすべて空として数えられます。サーバー側で数えれば中身も正しく集計できます。
- 入れ子になったシュルカーボックスやバンドルの中身まで数えます。中身の入ったシュルカーボックス / バンドルは、入れ物ではなく中身として数えます。

### サーバーサイド建材リスト (Material List)

配置した図面に必要な資材を、実際のワールドと突き合わせて数えます。

- Litematica 単体の建材リストは、描画距離の外にあるブロックを「空気」とみなすため、その部分がすべて「不足」になります。描画距離より大きい建築ほど結果が不正確になる問題を、サーバー側の集計で解消します。
- ブロックごとの必要数・不足数・別ブロックがある数に加え、図面が配置するエンティティとコンテナの中身も数えます。

### 共通: チャンクの読み込み方針

検証・領域分析・建材リストはすべて **読み取り専用** で、共通の設定 (`chunk_walk_*`) に従ってチャンクを読みます。

- メモリにないチャンクは非同期で読み込みます。読み込んだチャンクは **tick されません** (モブのスポーン、レッドストーン、ブロックの tick は起きません)。
- **一度も生成されていないチャンクは生成しません。** 「未生成」として報告するだけなので、調べただけでワールドが広がることはありません。
- サーバーの tick 時間 (MSPT) が高いあいだは、新しいチャンクの読み込みを控えます。
- 読めなかったチャンクの数 (未読み込み / 未生成) は結果と一緒に報告されます。

導入
----

1. サーバーの `mods` フォルダに Servux の jar を入れます (Fabric API も必要です)。
2. 追加機能をクライアントから使う場合は、各プレイヤーがフォーク版 Litematica を入れます。
3. 設定はサーバー起動時に `config/servux.json` に書き出されます。ゲーム内では `/servux set` で変更できます。

現在、ビルド済みの配布ファイル (Releases) はありません。[ビルド](#ビルド) を参照してください。

コマンド
--------

本家のコマンド (`/servux reload|save|set|info|list|search`) に、以下が追加されています。
**プレイヤーとして実行してください** (コンソールからは開始できません)。
既定では OP レベル 4 が必要です ([権限](#権限))。

### `/servux verify`

| コマンド | 説明 |
|---|---|
| `verify list` | Syncmatica で共有されていて、検証できる配置の一覧を表示します |
| `verify start <配置>` | 共有された配置の検証を開始します。配置は表示名か UUID で指定します |
| `verify status` | 実行中の検証の進行状況を表示します |
| `verify cancel [セッション]` | 実行中の検証を中止します。省略すると自分の検証を中止します |
| `verify show <分類> [ページ]` | 最後の検証結果から、指定した分類の不一致を一覧します |

`<分類>` は `missing` / `extra` / `wrong_block` / `wrong_state` / `wrong_nbt` / `missing_entity` のいずれかです。

### `/servux analyze`

| コマンド | 説明 |
|---|---|
| `analyze start <from> <to> [entities] [containers]` | 2 つの角で囲んだ範囲を分析します。`entities` と `containers` は `true` / `false` で、省略するとどちらも数えます |
| `analyze status` | 実行中の分析の進行状況を表示します |
| `analyze cancel [セッション]` | 実行中の分析を中止します。省略すると自分の分析を中止します |
| `analyze show [ページ]` | 最後の分析結果を、数の多い順に表示します |

### `/servux materials`

| コマンド | 説明 |
|---|---|
| `materials start <配置> [ignore_state]` | Syncmatica で共有された配置の建材リストを作成します。名前に空白を含む場合は `"` で囲みます。`ignore_state` を `true` にすると、ブロックの種類が合っていれば状態の違いを不足として数えません (既定 `false`) |
| `materials status` | 実行中の建材リストの進行状況を表示します |
| `materials cancel [セッション]` | 実行中の建材リストを中止します。省略すると自分のものを中止します |
| `materials show [ページ]` | 最後の建材リストを、不足の多い順に表示します |

設定
----

`litematic_data` プロバイダーに、以下の設定が追加されています。
`/servux set <設定名> <値>` で変更、`/servux info <設定名>` で確認できます。

### 権限レベル

| 設定 | 既定 | 説明 |
|---|---|---|
| `permission_level_verify` | `0` | クライアントから検証を依頼するのに必要な権限レベル |
| `permission_level_analyze` | `0` | クライアントから領域分析を依頼するのに必要な権限レベル |
| `permission_level_materials` | `0` | クライアントから建材リストを依頼するのに必要な権限レベル |

### 検証

| 設定 | 既定 | 説明 |
|---|---|---|
| `verify_max_result_positions` | `200000` | 1 回の検証で保持する不一致の座標数の上限。超えても分類ごとの件数は正確なまま、座標の一覧だけが打ち切られます。`0` で件数のみ |
| `verify_nbt` | `true` | コンテナの中身も比較する (`Wrong Contents`) |
| `verify_nbt_slot_exact` | `false` | 中身をスロット単位で比較する。既定では順序を無視して「何が何個あるか」で比べます (反転した配置ではラージチェストの左右が入れ替わるため) |
| `verify_nbt_strict` | `false` | アイテムの ID と個数に加え、エンチャントや名前などのデータも比較する |
| `verify_nbt_detail_positions` | `1024` | 中身違いのうち、両方の中身を結果と一緒に送る件数の上限。中身のデータは大きくなりうるので別に上限を設けている。`0` で座標のみ |
| `verify_syncmatica_interop` | `true` | Syncmatica で共有された配置をコマンドから検証できるようにする |

### 領域分析

| 設定 | 既定 | 説明 |
|---|---|---|
| `analyze_max_volume` | `67108864` | 1 回の分析で調べられる最大ブロック数 (既定は 512×256×512)。`0` で無制限 |
| `analyze_containers` | `true` | コンテナの中身も数える。いちばん負荷の高い処理なので無効にもできます |

### 検証・分析・建材リスト共通

| 設定 | 既定 | 説明 |
|---|---|---|
| `chunk_walk_force_load_chunks` | `true` | メモリにないチャンクを読み込んで調べる。無効にすると、読み込み済みのチャンクだけを調べます |
| `chunk_walk_generate_missing_chunks` | `false` | 未生成のチャンクを生成して調べる。**ワールドが恒久的に広がる** ため既定は無効です |
| `chunk_walk_max_loads_per_tick` | `2` | 1 tick あたりに開始する新しいチャンク読み込みの数 (1〜16)。小さいほど遅くなる代わりにディスク負荷が下がります |
| `chunk_walk_pause_mspt_threshold` | `45` | 平均 tick 時間がこの値 (ミリ秒) を超えているあいだ、新しい読み込みを止めます。`0` で止めません |
| `task_batch_positions` | `16384` | 結果を送るときの 1 バッチあたりの件数 |
| `task_session_timeout` | `300` | 受信確認のないまま放置された結果を破棄するまでの秒数。`0` で無効 |

> [!NOTE]
> 1.21.11 ブランチでは 2026-09-20 に設定名を 26.2 と揃えました
> (`verify_force_load_chunks` → `chunk_walk_force_load_chunks`、`verify_batch_positions` → `task_batch_positions` など 6 項目)。
> 旧名で値を変えていた場合は既定値に戻っているので、設定し直してください。

設定ファイル全体の例は [FEATURES.md](FEATURES.md) にあります。

権限
----

LuckPerms など fabric-permissions-api 対応の権限 mod があれば、ノードで細かく制御できます。
権限 mod がない場合は、それぞれの既定の OP レベルで判定されます。

| 用途 | ノード | 権限 mod がない場合 |
|---|---|---|
| コマンド全体 | `servux.commands` | OP レベル 4 |
| `/servux verify` | `servux.commands.verify` | OP レベル 4 |
| `/servux analyze` | `servux.commands.analyze` | OP レベル 4 |
| `/servux materials` | `servux.commands.materials` | OP レベル 4 |
| Litematica 連携全体 | `servux.provider.litematic_data` | `permission_level` (既定 0) |
| クライアントからの検証 | `servux.provider.litematic_data.verify` | `permission_level_verify` (既定 0) |
| クライアントからの領域分析 | `servux.provider.litematic_data.analyze` | `permission_level_analyze` (既定 0) |
| クライアントからの建材リスト | `servux.provider.litematic_data.materials` | `permission_level_materials` (既定 0) |

既定では、Litematica の GUI からの依頼は全員が使え、コマンドは OP レベル 4 のみが使えます。

Syncmatica 連携
---------------

`verify` と `materials` のコマンドは、Syncmatica で共有された配置を対象にできます。
Syncmatica がディスクに残すファイル (`<ワールド>/syncmatica/placements.json` と `syncmatics/*.litematic`) を読み取るだけで、Syncmatica 本体には依存しません。

- 読み取り専用で、Syncmatica のデータは変更しません。
- `placements.json` は Syncmatica の非公式な形式なので、Syncmatica のバージョンによっては読めないことがあります。読めない配置は警告を出して飛ばします。
- `verify_syncmatica_interop` を `false` にすると無効になります。

制限事項
--------

- **図面のサイズ:** Litematica の GUI から検証・建材リストを依頼するときは、図面をサーバーへアップロードします。アップロードの上限を超える大きな図面は送信できません (クライアントにエラーが表示されます)。
- **エンティティ:** 検証で調べるのは「足りないエンティティ」だけです。ワールドにだけある余計なエンティティや、額縁の中身・防具立ての装備の違いは調べません。
  - チャンクの境界のすぐそばで許容誤差の分だけずれたエンティティは、隣のチャンクのエンティティがまだ読み込まれていないと「足りない」と判定されることがあります (既定の許容誤差 `0.1` ではまれです)。
- **同時実行:** 検証・分析・建材リストは、それぞれプレイヤー 1 人につき 1 つまでです。
- **中止:** 本家の Fill / Delete などのタスクは、クライアントから中止できません。

ビルド
------

JDK 25 が必要です。

```
git clone https://github.com/panyaaa256/servux.git
cd servux
git checkout 26.2   # 1.21.11 版は git checkout 1.21.11
./gradlew build
```

ビルドした jar は `build/libs/` にできます。

クレジット・ライセンス
----------------------

- 本家: [sakura-ryoko/servux](https://github.com/sakura-ryoko/servux) (元は maruohon 氏の Servux)
- ライセンス: LGPLv3 ([LICENSE.txt](LICENSE.txt))
