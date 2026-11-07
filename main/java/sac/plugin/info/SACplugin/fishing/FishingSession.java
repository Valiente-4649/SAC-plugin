package sac.plugin.info.SACplugin.fishing;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class FishingSession {
    private final SACplugin plugin;
    private final Player player;
    private final FishingRodData rod;
    private final BossBar tensionBar;
    private BukkitRunnable task;
    private final Random rand = new Random();

    private final ItemStack rodItem; // ★追加: 失敗時の追加耐久値消費のため、竿の実体を保持
    private final org.bukkit.Location bobberLoc; // 浮きの座標
    // ==========================================
    // ★追加: 装備中のリール・ルアー(無ければnull)と、釣りスポットのランク。
    // ==========================================
    private final FishingReelData reel;
    private final FishingLureData lure;
    private final String spotRank;

    private double distance = 18.0; // ★変更: 難易度アップのため15→18
    private double tension = 50.0;
    private boolean isHit = false;
    private boolean isRaging = false;
    private boolean isWarning = false;

    // ★変更: 竿の実体(rodItem)・リール・ルアー・スポットランクを追加
    public FishingSession(SACplugin plugin, Player player, FishingRodData rod, ItemStack rodItem, org.bukkit.Location bobberLoc,
                          FishingReelData reel, FishingLureData lure, String spotRank) {
        this.plugin = plugin;
        this.player = player;
        this.rod = rod;
        this.rodItem = rodItem;
        this.bobberLoc = bobberLoc;
        this.reel = reel;
        this.lure = lure;
        this.spotRank = spotRank;
        this.tensionBar = Bukkit.createBossBar("§b←緩んでいる　〈釣り糸のテンション〉　張っている→", BarColor.BLUE, BarStyle.SOLID);
        startWaitTask();
    }

    private void startWaitTask() {
        // ★変更: 待ち時間を 10〜60秒 から 3〜15秒 に短縮
        int waitSeconds = (int) ((3 + rand.nextInt(13)) * rod.waitMultiplier);
        int totalWaitTicks = waitSeconds * 20; // Tickに変換

        task = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                // ==========================================
                // ★修正: 毎Tickではなく、4Tick(0.2秒)に1回だけ描画して軽くする
                // ==========================================
                if (ticks % 4 == 0) drawFishingLine();

                if (ticks >= totalWaitTicks) {
                    this.cancel();
                    startHitGame();
                }
                ticks += 2;
            }
        };
        task.runTaskTimer(plugin, 0L, 2L);
    }

    // ==========================================
    // ★修正箇所①: startHitGame() メソッドの中盤 (70行目付近)
    // ==========================================
    private void startHitGame() {
        isHit = true;
        tensionBar.addPlayer(player);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

        // ==========================================
        // ★追加: リールの暴れ抑制率(rageSuppressPercent)を、
        // 「暴れる間隔への加算(ms)」「暴れる持続時間からの減算(ms)」に換算する。
        // 抑制率が高いほど、暴れの間隔が延びて持続時間が短くなる。
        // ==========================================
        double suppress = (reel != null) ? reel.rageSuppressPercent : 0.0;
        int rageIntervalAddMs = (int) (suppress * 30);
        int rageDurationSubMs = (int) (suppress * 30);

        task = new BukkitRunnable() {
            int ticks = 0;
            long nextRageTime = System.currentTimeMillis() + (3000 + rand.nextInt(5001) + rageIntervalAddMs);

            @Override
            public void run() {
                long now = System.currentTimeMillis();

                // ==========================================
                // ★修正: テンションの自然減少を 10Tick(0.5秒) から 5Tick(0.25秒) ごとに変更！
                // ==========================================
                if (ticks % 5 == 0 && !isRaging) tension -= 5;

                if (!isRaging) {
                    if (now >= nextRageTime - 500) isWarning = true;
                    if (now >= nextRageTime) {
                        isRaging = true;
                        isWarning = false;
                        int rageDuration = (2000 + rand.nextInt(6001)) - rageDurationSubMs;
                        nextRageTime = now + Math.max(1000, rageDuration);
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.2f);
                    }
                } else {
                    if (now >= nextRageTime) {
                        isRaging = false;
                        nextRageTime = now + (3000 + rand.nextInt(5001) + rageIntervalAddMs);
                    }
                }

                if (ticks % 20 == 0) {
                    if (isRaging) {
                        // ==========================================
                        // ★変更: リールの暴れ抑制(rageSuppressPercent)を反映する。
                        // 例えば50%なら、本来1.0離れるところを0.5に抑える。
                        // ==========================================
                        if (!player.isSneaking()) {
                            double suppress = (reel != null) ? reel.rageSuppressPercent : 0.0;
                            distance += 1.5 * (1.0 - Math.min(100.0, suppress) / 100.0); // ★変更: 基礎値1.0→1.5
                        }
                    } else {
                        if (tension >= 47 && tension <= 53) distance -= 1; // ★変更: 安全圏45-55→47-53に縮小
                    }
                }

                updateUI();
                // ==========================================
                // ★修正: ヒット中も毎Tickではなく、5Tick(0.25秒)に1回だけ描画する
                // ==========================================
                if (ticks % 5 == 0) {
                    drawFishingLine();
                }

                // ★暴れ中の水しぶきも少しだけ間引いて軽くする
                if (isRaging) {
                    if (ticks % 2 == 0) {
                        player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, bobberLoc, 4, 0.3, 0.2, 0.3, 0.1);
                    }
                }

                if (tension >= 100 || tension <= 0 || distance >= 22) end(false); // ★変更: 開始距離18に合わせ、失敗閾値も18→22に引き上げ
                else if (distance <= 0) end(true);

                ticks++;
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
    }

    // ==========================================
    // ★修正箇所②: drawFishingLine() メソッド (130行目付近)
    // ==========================================
    private void drawFishingLine() {
        org.bukkit.Location start = player.getEyeLocation().subtract(0, 0.3, 0);
        org.bukkit.util.Vector dirLook = start.getDirection().normalize();
        org.bukkit.util.Vector right = dirLook.clone().crossProduct(new org.bukkit.util.Vector(0, 1, 0)).normalize();

        start.add(dirLook.multiply(0.5)).add(right.multiply(0.4));

        org.bukkit.util.Vector dir = bobberLoc.toVector().subtract(start.toVector());
        double dist = dir.length();
        dir.normalize();

        // 隙間が空く分、少しだけ太さ(0.4f)を戻して見えやすくする
        org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.WHITE, 0.4f);

        // ==========================================
        // ★最重要修正: 刻み幅を 0.5 から 1.5 に広げる！
        // これだけで生成されるパーティクルの数が 1/3 に激減します！
        // ==========================================
        for (double d = 0; d <= dist; d += 1.5) {
            org.bukkit.Location pLoc = start.clone().add(dir.clone().multiply(d));
            player.getWorld().spawnParticle(Particle.DUST, pLoc, 1, 0, 0, 0, 0, dust);
        }

        if (rand.nextInt(5) == 0) {
            player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, bobberLoc, 2, 0.2, 0.1, 0.2, 0.05);
        }
    }

    public void onReel() {
        // ==========================================
        // ★修正: 魚がヒットする前なら「キャンセル」として扱う
        // ==========================================
        if (!isHit) {
            player.sendMessage("§c[!] 釣りをキャンセルしました");
            cancel();
            SAOFishingListener.activeSessions.remove(player.getUniqueId());
            return;
        }

        if (player.isSneaking()) return;
        tension += isRaging ? 10 : 5;
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 2f);

        // リールを巻いた時にも水しぶきを出す
        player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, bobberLoc, 10, 0.2, 0.1, 0.2, 0.1);
    }

    private void updateUI() {
        tensionBar.setProgress(Math.max(0, Math.min(1.0, tension / 100.0)));
        tensionBar.setColor(tension > 80 ? BarColor.RED : (tension < 20 ? BarColor.YELLOW : BarColor.BLUE));

        String color = isWarning || isRaging ? "§c" : "§b";
        String msg = color + "魚までの距離: " + String.format("%.1f", distance) + "m";
        if (isRaging) msg += " [!! 暴れている !!]";
        else if (player.isSneaking()) msg += " §7(踏ん張っている)";

        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(msg));
    }

    private void end(boolean success) {
        if (task != null) task.cancel();
        tensionBar.removeAll();
        SAOFishingListener.activeSessions.remove(player.getUniqueId());

        if (success) {
            player.sendMessage("§a[FISHING SUCCESS] §f魚を釣り上げた！");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats != null) {
                int prof = stats.proficiency.getOrDefault("Fishing", 1);
                int limit = plugin.getLimitManager().getSkillLimit("Fishing");
                // ★追加: 上限未満のときだけ経験値を足す
                if (prof < limit) {
                    int exp = stats.proficiencyExp.getOrDefault("Fishing", 0);
                    exp += 10;
                    if (exp >= prof * 50) {
                        exp -= prof * 50;
                        prof++;
                        player.sendMessage("§b[SKILL UP] 釣りの熟練度が Lv." + prof + " に上がりました！");
                    }
                    stats.proficiency.put("Fishing", prof);
                    stats.proficiencyExp.put("Fishing", exp);
                } else {
                    // 上限到達時は通知だけ出すのも親切です
                    // player.sendMessage("§7[System] 釣りの熟練度が上限(Lv." + limit + ")に達しています。");
                }
                plugin.getPlayerManager().savePlayerData(player, stats);
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            dropFish();
            // ==========================================
            // ★ 追加: チュートリアル「釣り」の進行判定
            // ==========================================
            if ("釣り".equals(sac.plugin.info.SACplugin.Tutorial.TutorialManager.activeTutorial.get(player.getUniqueId()))) {
                sac.plugin.info.SACplugin.Tutorial.TutorialManager.tutorialProgress.put(player.getUniqueId(), 2);

                // 釣り上げ演出と被らないように1秒(20Tick)遅らせてメッセージとナビを出す
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.sendMessage("§a[System] お見事！見事に魚を釣り上げましたね！");
                    player.sendMessage("§e[System] 釣りの基本は完璧です。Tutorial NPCのところへ戻って報告しましょう！");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

                    // NPCの場所へナビを切り替え
                    sac.plugin.info.SACplugin.Tutorial.TutorialManager.startGuidance(player, sac.plugin.info.SACplugin.Tutorial.TutorialManager.TUTORIAL_NPC_LOC, plugin);
                }, 20L);
            }
        } else {
            player.sendMessage("§c[FISHING FAILED] §f魚に逃げられてしまった...");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.5f);

            // ==========================================
            // ★追加: 失敗すると、竿を試みた際の通常消費(1)に加えて、
            // 追加で2消費する(合計3消費)。無理な釣りへのペナルティ。
            // ==========================================
            int newDurability = plugin.getFishingManager().consumeRodDurability(rodItem, rod);
            newDurability = plugin.getFishingManager().consumeRodDurability(rodItem, rod);
            player.sendMessage("§b[System] §r§7※逃げられたため、竿の耐久値が多めに減少しました。(残り " + newDurability + ")");
        }
    }

    private void dropFish() {
        List<FishData> allFishes = new ArrayList<>(plugin.getFishingManager().getAllFishes().values());

        // ==========================================
        // ★変更: 釣れる魚のランクは「竿のランク」ではなく「釣りスポットのランク」が上限になる。
        // Cランクから始まり、1段階ずつ「ランクアップ判定」を行う。基礎確率1%に
        // ルアーのrareRankBonusPercentを上乗せし、スポットの上限ランクに達したら打ち止め。
        // ==========================================
        String[] rankOrder = {"C", "B", "A", "S"};
        int spotMaxIndex = indexOfRank(spotRank, rankOrder, 0);

        double bonus = (lure != null) ? lure.rareRankBonusPercent : 0.0;
        double stepChance = 1.0 + bonus;

        int resultIndex = 0;
        for (int i = 0; i < spotMaxIndex; i++) {
            if (rand.nextDouble() * 100.0 < stepChance) {
                resultIndex = i + 1;
            } else {
                break;
            }
        }
        String finalRank = rankOrder[resultIndex];

        List<FishData> candidates = allFishes.stream()
                .filter(f -> f.rank.equalsIgnoreCase(finalRank))
                .collect(Collectors.toList());

        FishData resultFish = null;
        if (!candidates.isEmpty()) {
            resultFish = candidates.get(rand.nextInt(candidates.size()));
            if (resultIndex > 0) {
                player.sendMessage("§6[RARE] §f運良く上のランクの魚が釣れました！");
            }
        }

        if (resultFish != null) {
            ItemStack fishItem = plugin.getFishingManager().createFishItem(resultFish);
            if (!player.getInventory().addItem(fishItem).isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), fishItem);
            }
            player.sendMessage("§b[System] §r§f獲得: §b" + resultFish.name);

            // ★追加(実績): 大物(Sizeのloreに[大物]が付く)を釣ったらランク別に記録する
            if (plugin.getAchievementManager() != null) {
                boolean achBig = false;
                if (fishItem.hasItemMeta() && fishItem.getItemMeta().hasLore()) {
                    for (String l : fishItem.getItemMeta().getLore()) {
                        if (l != null && l.contains("大物")) { achBig = true; break; }
                    }
                }
                plugin.getAchievementManager().onFishCaught(player, resultFish.rank, achBig);
            }

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats != null) {
                // ★追加: もしリストがnull(未作成)だったら初期化する
                if (stats.caughtFishes == null) {
                    stats.caughtFishes = new java.util.ArrayList<>();
                }

                if (!stats.caughtFishes.contains(resultFish.id)) {
                    stats.caughtFishes.add(resultFish.id);
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    player.sendMessage("§e[図鑑] §fはじめて「" + resultFish.name + "§f」を釣り上げました！図鑑に登録されます。");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
            }
        }
    }

    /** rankOrder配列内での該当ランクのインデックスを返す。見つからなければdefaultIndexを返す */
    private int indexOfRank(String rank, String[] rankOrder, int defaultIndex) {
        if (rank == null) return defaultIndex;
        for (int i = 0; i < rankOrder.length; i++) {
            if (rankOrder[i].equalsIgnoreCase(rank)) return i;
        }
        return defaultIndex;
    }

    public void cancel() {
        if (task != null) task.cancel();
        tensionBar.removeAll();
    }
}