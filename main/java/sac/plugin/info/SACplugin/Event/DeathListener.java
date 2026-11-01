package sac.plugin.info.SACplugin.Event;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DeathListener implements Listener {
    private final SACplugin plugin;
    // ★ プレイヤーの死亡タイマータスクを管理するマップ
    private final Map<UUID, BukkitTask> deathTasks = new HashMap<>();

    // ==========================================
    // ★追加: death_settings.json で管理する「死亡時に全部消すか」の設定
    // true: 今まで通りインベントリ・レベル・データを完全リセットする
    // false: インベントリ・データは残し、Col2割減少＋指定座標へのリスポーンにする
    // ==========================================
    private boolean fullWipeOnDeath = true;

    public DeathListener(SACplugin plugin) {
        this.plugin = plugin;
        loadDeathSettings();

        // ==========================================
        // ★修正: リロード・再起動対策
        // プラグインが起動した時に、オンラインのプレイヤーが死亡中なら3分からタイマーを再開する
        // ==========================================
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats != null && stats.isDead) {
                    stats.deathTimer = 60; // ★強制的に3分(180秒)にリセット
                    plugin.getPlayerManager().savePlayerData(player, stats);

                    player.setGameMode(GameMode.SPECTATOR);
                    player.setFlySpeed(0.0f);
                    startDeathTimer(player, stats);
                }
            }
        }, 30L); // 安全にデータが読み込まれるまで少し長めに待つ(1.5秒)
    }

    // ==========================================
    // ★追加: death_settings.json を読み込む
    // ==========================================
    private void loadDeathSettings() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "death_settings.json");
        if (!file.exists()) {
            plugin.saveResource("death_settings.json", false);
        }

        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("fullWipeOnDeath")) {
                fullWipeOnDeath = json.get("fullWipeOnDeath").getAsBoolean();
            }
            plugin.getLogger().info("[SAO] death_settings.json を読み込みました。fullWipeOnDeath=" + fullWipeOnDeath);
        } catch (Exception e) {
            plugin.getLogger().warning("[SAO] death_settings.json の読み込みに失敗しました。デフォルト(true)を使用します: " + e.getMessage());
        }
    }

    /** /sacreload などから手動で呼び出したい場合用。現状はreloadablesには未登録。 */
    public void reload() {
        loadDeathSettings();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // 1. バニラのアイテムドロップや経験値ロストをキャンセルし、一時的にそのまま保持する
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats != null) {
            stats.isDead = true;
            stats.deathTimer = 60; // ★ 3分(180秒)の猶予を設定
            plugin.getPlayerManager().savePlayerData(player, stats);
        }

        // 死んだ場所の座標を記録
        Location deathLoc = player.getLocation().clone();

        // 2. 即座にリスポーンさせ、スペクテイターで死体の後ろに固定する
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // バニラの死亡画面をスキップして強制リスポーン
            player.spigot().respawn();

            player.setGameMode(GameMode.SPECTATOR);
            // スペクテイターでの移動速度を0にして物理的にも動けなくする
            player.setFlySpeed(0.0f);

            // プレイヤーの向いていた方向の「後ろ2マス」を計算
            Vector dir = deathLoc.getDirection().setY(0).normalize();
            if (dir.lengthSquared() == 0) {
                dir = new Vector(0, 0, 1);
            }
            Location specLoc = deathLoc.clone().subtract(dir.multiply(2));
            specLoc.setY(deathLoc.getY() + 1.2); // 自分の死体を見下ろせるように少し高くする
            specLoc.setDirection(dir); // 前(自分の死体の方)を向かせる

            player.teleport(specLoc);

            // タイマーを開始
            if (stats != null) {
                startDeathTimer(player, stats);
            }
        }, 1L);
    }

    // ==========================================
    // ★ ログイン時、まだ死亡猶予中ならタイマーを「3分から」再開する
    // ==========================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // ★修正: JoinListener(初期設定)の処理が完全に終わるのを待つため、1.5秒(30Tick)遅らせる！
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerData stats = plugin.getPlayerManager().getStats(player);

            if (stats != null) {
                // ★追加: 黒鉄宮の死亡回数ランキング(直近1週間ログイン判定)用に、最終ログイン時刻を更新する
                stats.lastLoginTime = System.currentTimeMillis();
                plugin.getPlayerManager().savePlayerData(player, stats);

                // 基本はデータ上の生死判定に従う
                boolean isReallyDead = stats.isDead;

                // ==========================================
                // ★追加: 旧バージョンで死亡したままのプレイヤーへの救済措置
                // データがなくても、非OPでスペクテイターなら「死亡している」とみなす！
                // ==========================================
                if (!stats.isDead && !player.isOp() && player.getGameMode() == GameMode.SPECTATOR) {
                    isReallyDead = true;
                    stats.isDead = true; // データ上も死亡状態に上書き修正してあげる
                    player.sendMessage("§e[System] §c旧バージョンの死亡データを検知したため、復活タイマーを再起動します。");
                }

                if (isReallyDead) {
                    stats.deathTimer = 60; // ★3分(180秒)から数え直し！
                    plugin.getPlayerManager().savePlayerData(player, stats); // 念のためセーブ

                    player.setGameMode(GameMode.SPECTATOR);
                    player.setFlySpeed(0.0f); // ログイン時も飛行速度を0にする
                    startDeathTimer(player, stats);
                }
            }
        }, 30L);
    }

    // ==========================================
    // ★ 死亡猶予中のプレイヤーが動けないように座標を固定する
    // ==========================================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        if (stats != null && stats.isDead) {
            // X, Y, Z の座標が変わった場合のみキャンセル（首を振る視点移動は許可）
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {

                Location newLoc = event.getFrom();
                newLoc.setPitch(event.getTo().getPitch());
                newLoc.setYaw(event.getTo().getYaw());
                event.setTo(newLoc);
            }
        }
    }

    // ==========================================
    // ★追加: スペクテイターのテレポート機能(数字キーやクリック)を無効化する
    // ==========================================
    @EventHandler
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        if (stats != null && stats.isDead) {
            // テレポートの原因が「SPECTATE (スペクテイターメニューや他プレイヤーへのワープ)」ならキャンセル
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
                event.setCancelled(true);
                player.sendMessage("§c[System] 復活待ち中はテレポートできません。");
            }
        }
    }

    // ==========================================
    // ★ 死亡タイマーの進行処理
    // ==========================================
    private void startDeathTimer(Player player, PlayerData stats) {
        UUID uuid = player.getUniqueId();

        // 既に動いているタイマーがあればキャンセル
        if (deathTasks.containsKey(uuid)) {
            deathTasks.get(uuid).cancel();
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                // オフラインになったらタイマーを止める（次ログインした時に再開する）
                if (!player.isOnline()) {
                    this.cancel();
                    deathTasks.remove(uuid);
                    return;
                }

                if (stats.deathTimer > 0) {
                    // アクションバーに残り時間を表示
                    int min = stats.deathTimer / 60;
                    int sec = stats.deathTimer % 60;
                    String timeStr = String.format("%02d:%02d", min, sec);

                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            new net.md_5.bungee.api.chat.TextComponent("§c[System] DEATH TIMER " + timeStr + " (復活待ち)"));

                    stats.deathTimer--;

                    // ★追加: 10秒に1回、進捗をファイルにセーブしておく (突然のサーバーダウン対策)
                    if (stats.deathTimer % 10 == 0) {
                        plugin.getPlayerManager().savePlayerData(player, stats);
                    }

                } else {
                    // 3分経過！完全なデス処理へ移行
                    this.cancel();
                    deathTasks.remove(uuid);
                    executeFinalDeath(player);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // 20Tick(1秒)ごとに実行

        deathTasks.put(uuid, task);
    }

    // ==========================================
    // ★ 猶予時間が終了したときの処理。設定に応じて分岐する。
    // ==========================================
    public void executeFinalDeath(Player player) {
        if (fullWipeOnDeath) {
            executeFullWipeDeath(player);
        } else {
            executeSoftDeath(player);
        }
    }

    /**
     * ★追加: レベル1のプレイヤーが死亡した時の「完全リセット」処理。
     * データを丸ごと削除して最初から作り直す(以前の仕様と同じ)。
     * ただし死亡回数(deathCount)と最終ログイン時刻(lastLoginTime)だけは
     * ランキング表示のために新しいデータへ引き継ぐ。
     */
    private void executeCompleteWipe(Player player, PlayerData oldStats) {
        int carriedDeathCount = oldStats.deathCount + 1;
        long carriedLastLoginTime = oldStats.lastLoginTime;

        // 1. インベントリを空にする
        player.getInventory().clear();
        player.setExp(0);
        player.setLevel(0);

        // 2. サバイバルに戻し、キリトの家（worldのスポーン地点）へ飛ばす
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlySpeed(0.1f);
        World mainWorld = Bukkit.getWorld("world");
        World respawnWorld = mainWorld != null ? mainWorld : player.getWorld();
        player.teleport(respawnWorld.getSpawnLocation());

        // 3. データを完全に削除(ファイルも消える)
        plugin.getPlayerManager().deletePlayerData(player);

        // 宝箱の開封履歴もリセット(再挑戦できるようにする)
        if (plugin.getTreasureManager() != null) {
            plugin.getTreasureManager().resetOpenedByPlayer(player.getUniqueId());
        }

        // 4. 新しい初期データを作り直す
        try {
            plugin.getPlayerManager().setupPlayer(player);
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のexecuteCompleteWipe内setupPlayerで例外！");
            e.printStackTrace();
        }
        PlayerData newData = plugin.getPlayerManager().getStats(player);

        // ★死亡回数・最終ログイン時刻だけは引き継ぐ
        if (newData != null) {
            newData.deathCount = carriedDeathCount;
            newData.lastLoginTime = carriedLastLoginTime;
            plugin.getPlayerManager().savePlayerData(player, newData);
        }

        // 最大HPを初期値(20.0)にリセット
        player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        player.setHealth(20.0);
        player.setFoodLevel(20);

        // 5. 絶望の演出
        player.sendTitle("§cYou are Dead.", "§fデータが完全にリセットされました", 10, 70, 20);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);

        // 6. スコアボードの更新
        try {
            if (plugin.getSaoScoreboard() != null && newData != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, newData);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のスコアボード更新で例外！");
            e.printStackTrace();
        }

        // ★ナーヴギア付与
        try {
            player.getInventory().addItem(sac.plugin.info.SACplugin.Tutorial.TutorialManager.getNerveGear());
            player.sendMessage("§b[System] §r§bナーヴギアを手に入れた！　使用方法はインベントリから！");
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のナーヴギア付与で例外！");
            e.printStackTrace();
        }

        // ★黒鉄宮の死亡回数ランキング表示を更新する
        if (plugin.getDeathBoardManager() != null) {
            plugin.getDeathBoardManager().updateBoard();
        }
    }

    // Colの死亡時ペナルティで、これ以下には減らさない下限額(新規プレイヤーの初期所持金と同じ)
    private static final long DEATH_COL_FLOOR = 1200L;
    // スキル熟練度の死亡時の減少量
    private static final int DEATH_PROFICIENCY_LOSS = 3;

    /**
     * 死亡時のレベル減少量 = 現在のレベル÷10(小数切り捨て)+1。
     * 例: Lv1〜9=1減 / Lv10〜19=2減 / Lv20〜29=3減 / Lv30〜39=4減 ...
     * (小数を切り上げる方式にしたい場合は (level + 9) / 10 + 1 に変える)
     */
    private int calcLevelLoss(int level) {
        return level / 10 + 1;
    }

    // ==========================================
    // ★変更: 猶予時間が終了したときの「デスペナルティ処理」(fullWipeOnDeath = true)。
    // 以前は「プレイヤーデータを完全削除して最初から」という仕様だったが、
    // 以下の内容に変更した:
    //   ・レベルを「現在のレベル÷10(切り捨て)+1」だけ減少(最低1)
    //   ・exp = 0
    //   ・Colを5分の1減少(1200以下なら減らさず、1200を下回らない)
    //   ・STR/AGIを全リセットし、代わりにペナルティ後のレベルに応じたスキルポイントを付与
    //     (=スキルポイントは新しいレベルに連動して減る)
    //   ・装備中の防具・アクセサリーを削除(data.equipmentを空にする)
    //   ・インベントリの中身を全て削除(《Menu》は残す)
    //   ・スキル熟練度を全て3減少(最低1)
    //   ・死亡回数をカウントし、黒鉄宮のランキング表示を更新する
    // ==========================================
    public void executeFullWipeDeath(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        // ==========================================
        // ★ ショップの強制閉店(既存仕様を維持)
        // 死亡リセット時に出店中のショップを撤去し、商品・売上は消滅させる
        // ==========================================
        sac.plugin.info.SACplugin.shop.PlayerShopManager shopManager = plugin.getPlayerShopManager();
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (!(e instanceof org.bukkit.entity.Interaction)) continue;
                if (!e.getScoreboardTags().contains("sao_player_shop")) continue;

                sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop shop =
                        shopManager.getShop(e.getUniqueId());
                if (shop == null || !shop.ownerUuid.equals(player.getUniqueId())) continue;

                for (org.bukkit.entity.Entity nearby : e.getNearbyEntities(2.0, 3.0, 2.0)) {
                    if (nearby.getScoreboardTags().contains("sao_shop_furniture")) {
                        nearby.remove();
                    }
                }

                org.bukkit.entity.ArmorStand newSpot = e.getWorld().spawn(
                        e.getLocation(), org.bukkit.entity.ArmorStand.class);
                newSpot.setVisible(false);
                newSpot.setGravity(false);
                newSpot.setInvulnerable(true);
                newSpot.setCustomName("§bShop Spot");
                newSpot.setCustomNameVisible(true);
                newSpot.addScoreboardTag("sao_shop_spot");

                shopManager.removeShop(e.getUniqueId());
                e.remove();
            }
        }
        shopManager.pendingSales.remove(player.getUniqueId());
        shopManager.pendingExp.remove(player.getUniqueId());
        shopManager.saveShops();

        // ==========================================
        // ★追加: レベル1のプレイヤーはこれ以上レベルを下げられず実質ペナルティにならないため、
        // 従来通りの完全リセット(データ削除+最初からやり直し)を適用する。
        // ==========================================
        if (stats != null && stats.level <= 1) {
            executeCompleteWipe(player, stats);
            return;
        }

        // ==========================================
        // ★新デスペナルティ本体(レベル2以上)
        // ==========================================
        if (stats != null) {
            // 1. レベルを「現在のレベル÷10(切り捨て)+1」だけ減少(最低1)
            int newLevel = Math.max(1, stats.level - calcLevelLoss(stats.level));
            stats.level = newLevel;

            // 2. expを0に
            stats.exp = 0;

            // Colを5分の1減少。ただし1200以下の場合は減らさず、減少後も1200を下回らない
            if (stats.col > DEATH_COL_FLOOR) {
                stats.col = Math.max(DEATH_COL_FLOOR, stats.col - stats.col / 5);
            }

            // 3. STR/AGIを全リセットし、ペナルティ後のレベルに応じたスキルポイントを付与
            stats.str = 0;
            stats.agi = 0;
            stats.skillPoint = Math.max(0, newLevel - 1);

            // 4. 装備中の防具・アクセサリーを削除
            stats.equipment.clear();

            // 5. インベントリの中身を全て削除する(《Menu》は残す)
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType() == Material.AIR) continue;
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        && "§b《Menu》".equals(item.getItemMeta().getDisplayName())) {
                    continue; // 《Menu》はそのまま残す
                }
                player.getInventory().setItem(i, null);
            }

            // 6. スキル熟練度を全て3減少(最低1)。関連経験値はリセットする
            for (String key : new java.util.ArrayList<>(stats.proficiency.keySet())) {
                int current = stats.proficiency.getOrDefault(key, 1);
                stats.proficiency.put(key, Math.max(1, current - DEATH_PROFICIENCY_LOSS));
            }
            stats.proficiencyExp.clear();

            // 7. 死亡回数をカウント
            stats.deathCount++;

            // ==========================================
            // ★ レベル低下・STR/AGIリセットに伴い、HP等のステータス表示を再計算する
            // ==========================================
            stats.isDead = false;
            stats.deathTimer = 0;
            plugin.getPlayerManager().syncHealth(player, stats);
            stats.currentHP = stats.maxHP;

            plugin.getPlayerManager().savePlayerData(player, stats);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            // ★追加: 黒鉄宮の死亡回数ランキング表示を更新する
            if (plugin.getDeathBoardManager() != null) {
                plugin.getDeathBoardManager().updateBoard();
            }
        }

        // サバイバルに戻し、第一層の初期スポーン地点へ飛ばす
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlySpeed(0.1f);
        // ★変更: 復活地点を「始まりの街」(TutorialManager.TOWN_SPAWNと同じ座標)に変更
        Location townSpawn = sac.plugin.info.SACplugin.Tutorial.TutorialManager.TOWN_SPAWN;
        if (townSpawn.getWorld() != null) {
            player.teleport(townSpawn);
        } else {
            player.teleport(player.getWorld().getSpawnLocation());
        }

        // ★追加: 宝箱の開封履歴をリセット(再挑戦できるようにする、既存仕様を維持)
        if (plugin.getTreasureManager() != null) {
            plugin.getTreasureManager().resetOpenedByPlayer(player.getUniqueId());
        }

        player.setFoodLevel(20);

        // 演出
        player.sendTitle("§cYou are Dead.", "§fレベルが下がり、装備・所持品を失いました", 10, 70, 20);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
    }

    // ==========================================
    // ★追加: 猶予時間が終了したときの「マイルドな復活処理」(fullWipeOnDeath = false)
    // インベントリ・レベル・プレイヤーデータは一切消さず、Colだけ2割減らして
    // 指定座標(x8.5, y87, z252.5, yaw180, pitch0)へリスポーンさせる。
    // ==========================================
    public void executeSoftDeath(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        if (stats != null) {
            // Colを2割減少(端数切り捨て)
            long lostCol = Math.round(stats.col * 0.2);
            stats.col -= lostCol;
            if (stats.col < 0) stats.col = 0;

            // 死亡状態を解除
            stats.isDead = false;
            stats.deathTimer = 0;
            stats.currentHP = stats.maxHP;

            plugin.getPlayerManager().savePlayerData(player, stats);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            player.sendMessage("§e[System] §f所持Colの20%(§c" + lostCol + " Col§f)を失いました。");
        }

        // サバイバルに戻し、指定座標へリスポーン
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlySpeed(0.1f);

        org.bukkit.World floor1 = Bukkit.getWorld("world");
        Location respawnLoc;
        if (floor1 != null) {
            respawnLoc = new Location(floor1, 8.5, 87, 252.5, 180f, 0f);
        } else {
            // worldが取得できない場合は現在のワールドのスポーンにフォールバック
            respawnLoc = player.getWorld().getSpawnLocation();
        }
        player.teleport(respawnLoc);

        player.setHealth(20.0);
        player.setFoodLevel(20);

        player.sendTitle("§bRESPAWN", "§fColの一部を失いましたが、装備とデータは無事です", 10, 60, 20);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
    }
}