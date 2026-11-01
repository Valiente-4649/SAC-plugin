package sac.plugin.info.SACplugin.task;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.mob.SAOMobData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SAOSpawnerTask extends BukkitRunnable {
    private final SACplugin plugin;
    private int globalTimer = 90; // 60秒からカウントダウン
    private final double ACTIVATION_RANGE = 100.0; // 判定範囲を50マスに変更

    // 追加: ボスのリポップ管理用
    private final Map<String, Boolean> bossAliveStates = new HashMap<>();
    private final Map<String, Long> bossCooldowns = new HashMap<>();

    // ★追加: スポナーのON/OFFが切り替わった瞬間を検知するための変数
    private boolean wasEnabled = true;

    public SAOSpawnerTask(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean isEnabled = plugin.isSpawnerEnabled();

        // OFFからONに切り替わった瞬間に、すべてのスポナーを即座に見えなくする！
        if (isEnabled && !wasEnabled) {
            hideAllSpawners();
        }
        wasEnabled = isEnabled;

        if (!isEnabled) {
            showDisabledSpawnerParticles(); // OFF時はこれを呼んで終了
            return;
        }

        // ==========================================
        // ★追加: 遠くのエリアが読み込まれた時の対策
        // 稼働中(ON)の時は、5秒に1回、読み込まれたスポナーを強制的に隠す！
        // ==========================================
        if (globalTimer % 5 == 0) {
            hideAllSpawners();
        }

        // ★ 元のタイマー処理を一切変更せずに維持
        globalTimer--;
        if (globalTimer <= 0) {
            globalTimer = 90; // 0になったら60秒に戻す
        }
        if (globalTimer % 10 == 0) {
            // Bukkit.getLogger().info("[SAO] Timer: " + globalTimer);
        }

        // タイマーが57秒のときだけ実行 (元は globalTimer == 80)
        // ★ 追記: ボスは2倍の頻度にするため、80と30のタイミングで実行を許可する
        boolean isNormalTick = (globalTimer == 70);
        boolean isBossTick = (globalTimer == 70 || globalTimer == 30);

        if (isNormalTick || isBossTick) {
            // ★ 元の「チャンクからエンティティを取得する」安全なループ構造を完全維持
            for (World world : Bukkit.getWorlds()) {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    for (Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof ArmorStand)) continue;

                        ArmorStand stand = (ArmorStand) entity;

                        // スポナータグを持っているか確認
                        for (String tag : stand.getScoreboardTags()) {
                            if (tag.startsWith("sao_spawner_")) {
                                String mobId = tag.replace("sao_spawner_", "");

                                if (isPlayerNearby(stand)) {
                                    // ★ 元の spawnMobs(stand, mobId) の代わりに、判定用のメソッドを挟む
                                    processSpawner(stand, mobId, world, isNormalTick, isBossTick);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ★ 元の検知メソッドを完全に維持
    private boolean isPlayerNearby(ArmorStand stand) {
        double rangeSq = ACTIVATION_RANGE * ACTIVATION_RANGE;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(stand.getWorld())) {
                if (p.getLocation().distanceSquared(stand.getLocation()) <= rangeSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processSpawner(ArmorStand stand, String mobId, World world, boolean isNormalTick, boolean isBossTick) {
        // ★ ONの時はスポナーを透明化し、名前や発光も消す
        stand.setVisible(false);
        stand.setGlowing(false);
        stand.setCustomNameVisible(false);

        SAOMobData mobData = plugin.getSaoMobManager().getMobData(mobId);
        if (mobData == null) return;

        String category = mobData.category != null ? mobData.category : "NORMAL";
        boolean isBoss = category.equals("FIELD_BOSS") || category.equals("FLOOR_BOSS");
        boolean isMinion = category.equals("MINION");

        // カテゴリごとの湧くタイミングチェック
        if (isBoss && !isBossTick) return;
        if (isMinion && !isBossTick) return; // MINIONはボスと同じタイミング
        if (!isBoss && !isMinion && !isNormalTick) return; // NORMALは60秒ごと

        // ==========================================
        // ★ MINIONのスポーン処理
        // ==========================================
        if (isMinion) {
            sac.plugin.info.SACplugin.boss.BossData bossData = null;
            if (plugin.getBossManager() != null) {
                for (sac.plugin.info.SACplugin.boss.BossData b : plugin.getBossManager().getAllBossData()) {
                    if (mobId.equals(b.minionId)) {
                        bossData = b;
                        break;
                    }
                }
            }
            if (bossData != null) {
                // プレイヤーがいなければ湧かない
                if (plugin.getBossManager().getPlayersInBossRoom(bossData.bossId).isEmpty()) return;

                int currentCount = 0;
                double minX = Math.min(bossData.pos1X, bossData.pos2X);
                double maxX = Math.max(bossData.pos1X, bossData.pos2X);
                double minY = Math.min(bossData.pos1Y, bossData.pos2Y);
                double maxY = Math.max(bossData.pos1Y, bossData.pos2Y);
                double minZ = Math.min(bossData.pos1Z, bossData.pos2Z);
                double maxZ = Math.max(bossData.pos1Z, bossData.pos2Z);

                for (Entity e : world.getNearbyEntities(stand.getLocation(), 100, 50, 100)) {
                    if (e instanceof LivingEntity && e.getScoreboardTags().contains("sao_mob_" + mobId)) {
                        Location l = e.getLocation();
                        if (l.getX() >= minX && l.getX() <= maxX && l.getY() >= minY && l.getY() <= maxY && l.getZ() >= minZ && l.getZ() <= maxZ) {
                            currentCount++;
                        }
                    }
                }
                // 足りない分だけ、1体湧かせる (20秒おきに呼ばれるので1体ずつ増える)
                if (currentCount < bossData.maxMinions) {
                    spawnMobs(stand, mobId, 1, false, true);
                }
            }
            return;
        }

        // ==========================================
        // ★ NORMALのスポーン処理
        // ==========================================
        if (!isBoss) {
            Location centerLoc = stand.getLocation().clone().add(0, 1, 0);

            // ==========================================
            // 1. プレイヤー検知 (スポナーから半径100ブロック以内にいるか)
            // ==========================================
            boolean hasPlayer = false;
            for (Player p : world.getPlayers()) {
                if (p.getWorld().equals(world)) {
                    // ★修正: 100ブロック(100*100)以内に近づいた時点で稼働を開始する！
                    if (p.getLocation().distanceSquared(centerLoc) <= 100 * 100) {
                        hasPlayer = true;
                        break;
                    }
                }
            }
            if (!hasPlayer) return; // 誰も100ブロック以内にいなければ湧かない

            // 2. 範囲内の同種モブをカウント (X±25, Y±4, Z±25)
            int currentCount = 0;
            for (Entity e : world.getNearbyEntities(centerLoc, 25, 4, 25)) {
                if (e instanceof LivingEntity && e.getScoreboardTags().contains("sao_mob_" + mobId)) {
                    currentCount++;
                }
            }

            // 3. 合計「4体」になるように不足分を湧かせる (★変更)
            int maxSpawns = 3;
            int spawnAmount = maxSpawns - currentCount;
            if (spawnAmount > 0) {
                spawnMobs(stand, mobId, spawnAmount, false, false);
            }
            return;
        }

        // ==========================================
        // ★ BOSSのスポーン処理 (既存のコードを維持)
        // ==========================================
        if (isBoss && plugin.getBossManager() != null) {
            if (plugin.getBossManager().getBossData(mobId) != null) {
                if (plugin.getBossManager().getPlayersInBossRoom(mobId).isEmpty()) {
                    return;
                }
            }
        }

        if (isBoss) {
            boolean bossAlreadyExists = false;

            // ★ 連鎖ボス判定: Nato/Baran/Asteriosはどれかが生存中なら全員スポーンしない
            java.util.List<String> chainGroup = java.util.Arrays.asList(
                    "nato_the_colonel_taurus",
                    "baran_the_general_taurus",
                    "asterios_the_taurus_king"
            );
            boolean isChainBoss = chainGroup.contains(mobId);
            if (isChainBoss) {
                for (String chainId : chainGroup) {
                    for (LivingEntity aliveEntity : world.getLivingEntities()) {
                        if (aliveEntity.getScoreboardTags().contains("sao_mob_" + chainId)) {
                            bossAlreadyExists = true;
                            break;
                        }
                    }
                    if (bossAlreadyExists) break;
                }
            } else {
                for (LivingEntity aliveEntity : world.getLivingEntities()) {
                    if (aliveEntity.getScoreboardTags().contains("sao_mob_" + mobId)) {
                        bossAlreadyExists = true;
                        break;
                    }
                }
            }
            if (bossAlreadyExists) {
                bossAliveStates.put(mobId, true);
                return;
            } else {
                if (bossAliveStates.getOrDefault(mobId, false)) {
                    bossCooldowns.put(mobId, System.currentTimeMillis() + 60000L);
                    bossAliveStates.put(mobId, false);
                    Bukkit.getLogger().info("[SAO] ボス " + mobId + " が消滅しました。1分間のリポップ待機を開始します。");
                }
                if (bossCooldowns.containsKey(mobId) && System.currentTimeMillis() < bossCooldowns.get(mobId)) {
                    return;
                }
            }
            spawnMobs(stand, mobId, 1, true, false);
        }
    }

    // ★ 引数に isMinion を追加
    private void spawnMobs(ArmorStand stand, String mobId, int amount, boolean isBoss, boolean isMinion) {
        Location centerLoc = stand.getLocation().clone().add(0, 1, 0);
        World world = stand.getWorld();

        // ==========================================
        // ★追加: レアモブ抽選の対象となる1体だけを事前に決めておく。
        // amount体スポーンしても、レアモブに変わり得るのはこのインデックスの1体だけ。
        // ==========================================
        SAOMobData baseMobData = plugin.getSaoMobManager().getMobData(mobId);
        int rareSlotIndex = (amount > 0) ? (int) (Math.random() * amount) : -1;

        // NORMAL用の既存モブ位置リスト（重なりを防ぐため）
        java.util.List<Location> existingLocs = new java.util.ArrayList<>();
        if (!isBoss && !isMinion) {
            // ★変更: カウント範囲を25(±25)、高さを4(±4)に変更
            for (Entity e : world.getNearbyEntities(centerLoc, 25, 4, 25)) {
                if (e instanceof LivingEntity && e.getScoreboardTags().contains("sao_mob_" + mobId)) {
                    existingLocs.add(e.getLocation());
                }
            }
        }

        for (int i = 0; i < amount; i++) {
            Location s = centerLoc.clone();

            if (!isBoss && !isMinion) {
                // ==========================================
                // ★ NORMAL: 一辺50の正方形内(±25)、高さ±4で安全なY座標を探す
                // ==========================================
                boolean found = false;
                for (int tryCount = 0; tryCount < 15; tryCount++) {
                    double rx = (Math.random() * 50.0) - 25.0; // ★±25の乱数
                    double rz = (Math.random() * 50.0) - 25.0;
                    int targetX = centerLoc.getBlockX() + (int) rx;
                    int targetZ = centerLoc.getBlockZ() + (int) rz;
                    int baseY = centerLoc.getBlockY();

                    Location tempLoc = null;

                    // ★変更: 0から始まり、±4 まで交互に探す（薄いスライス）
                    for (int dy = 0; dy <= 4; dy++) {
                        int[] signs = (dy == 0) ? new int[]{1} : new int[]{-1, 1};

                        for (int sign : signs) {
                            int y = baseY + (dy * sign);

                            org.bukkit.block.Block block = world.getBlockAt(targetX, y, targetZ);
                            org.bukkit.block.Block above1 = world.getBlockAt(targetX, y + 1, targetZ);
                            org.bukkit.block.Block above2 = world.getBlockAt(targetX, y + 2, targetZ);

                            // 条件: 足元が個体かつ葉っぱではない、上2マスが空いている
                            if (block.getType().isSolid() && !block.getType().toString().endsWith("LEAVES")) {
                                if (!above1.getType().isSolid() && !above2.getType().isSolid()) {

                                    // ★RayTrace（視線判定）はそのまま維持！完璧な壁抜け防止！
                                    Location candidateLoc = new Location(world, targetX + 0.5, y + 1, targetZ + 0.5);
                                    Location spawnerEye = centerLoc.clone().add(0, 1.5, 0);
                                    Location targetEye = candidateLoc.clone().add(0, 1.0, 0);
                                    org.bukkit.util.Vector dir = targetEye.toVector().subtract(spawnerEye.toVector());
                                    double dist = dir.length();

                                    boolean isObstructed = false;

                                    if (dist > 0.1) {
                                        dir.normalize();
                                        org.bukkit.util.RayTraceResult rayResult = world.rayTraceBlocks(spawnerEye, dir, dist, org.bukkit.FluidCollisionMode.NEVER, true);

                                        if (rayResult != null && rayResult.getHitBlock() != null) {
                                            isObstructed = true;
                                        }
                                    }

                                    if (!isObstructed) {
                                        tempLoc = candidateLoc;
                                        break;
                                    }
                                }
                            }
                        }
                        if (tempLoc != null) break;
                    }

                    if (tempLoc != null) {
                        boolean isFarEnough = true;
                        for (Location eLoc : existingLocs) {
                            if (eLoc.distanceSquared(tempLoc) < 5 * 5) {
                                isFarEnough = false;
                                break;
                            }
                        }

                        if (isFarEnough) {
                            s = tempLoc;
                            existingLocs.add(s);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) continue;
            } else {
                // BOSS, MINION: アーマースタンドから見て X・Z はランダムに散らし、Y は「上に4マス」固定！
                double rx = (Math.random() * 8.0) - 4.0;
                double rz = (Math.random() * 8.0) - 4.0;

                // ★修正: アーマースタンドの基準位置から「上に4マス(Y+4)」を指定する！
                s = stand.getLocation().clone().add(rx, 4, rz);
            }

            // ==========================================
            // ★追加: このスロットがレア抽選対象なら、raremobから差し替え先を抽選する
            // ==========================================
            String spawnMobId = mobId;
            if (i == rareSlotIndex && baseMobData != null && baseMobData.raremob != null && !baseMobData.raremob.isEmpty()) {
                String rolled = rollRareMob(baseMobData);
                if (rolled != null) {
                    spawnMobId = rolled;
                }
            }

            if (plugin.getSaoMobManager() != null) {
                plugin.getSaoMobManager().spawnMob(spawnMobId, s);
                s.getWorld().spawnParticle(Particle.LARGE_SMOKE, s, 20, 0.5, 0.2, 0.5, 0.1);
            }
        }
        Bukkit.getLogger().info("[SAO] スポナー作動: " + mobId);

        if (isBoss) {
            bossAliveStates.put(mobId, true);
            bossCooldowns.remove(mobId);
        }
    }
    // ==========================================
    // ★追加: raremobマップを1件ずつ順に抽選し、最初に成功したモブIDを返す。
    // どれも成功しなければnull(通常モブのまま)を返す。
    // ==========================================
    private String rollRareMob(SAOMobData baseMobData) {
        for (Map.Entry<String, Double> entry : baseMobData.raremob.entrySet()) {
            if (Math.random() < entry.getValue()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ★追加: OFF時にスポナーを可視化するメソッド
    private void showDisabledSpawnerParticles() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof ArmorStand)) continue;
                    ArmorStand stand = (ArmorStand) entity;
                    String mobId = null;
                    for (String tag : stand.getScoreboardTags()) {
                        if (tag.startsWith("sao_spawner_")) {
                            mobId = tag.replace("sao_spawner_", "");
                            break;
                        }
                    }
                    if (mobId != null) {
                        Location loc = stand.getLocation();

                        // ==========================================
                        // ★追加: 300ブロック以内にプレイヤーがいるかチェック（軽量化対策）
                        // ==========================================
                        boolean isNearPlayer = false;
                        for (Player p : world.getPlayers()) {
                            if (p.getWorld().equals(world) && p.getLocation().distanceSquared(loc) <= 300 * 300) {
                                isNearPlayer = true;
                                break;
                            }
                        }
                        if (!isNearPlayer) continue; // 誰もいなければパーティクルを出さない（スキップ）

                        stand.setVisible(true);
                        stand.setGlowing(true);
                        stand.setCustomName("§e[Spawner] §f" + mobId);
                        stand.setCustomNameVisible(true);

                        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0, 1, 0), 2, 0, 0.5, 0, 0.05);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 2, 0), 5, 0.2, 0.5, 0.2, 0.01);

                        SAOMobData mobData = plugin.getSaoMobManager().getMobData(mobId);
                        String cat = mobData != null && mobData.category != null ? mobData.category : "NORMAL";
                        if ("NORMAL".equals(cat)) {
                            // ==========================================
                            // ★ 箱型（50x50、高さ±4）のボーダーを描画
                            // ==========================================
                            for (int i = -25; i <= 25; i += 2) {
                                // 床面(Y-4)の枠
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(i, -4, 25), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(i, -4, -25), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(25, -4, i), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(-25, -4, i), 1, 0, 0, 0, 0);
                                // 天井面(Y+4)の枠
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(i, 4, 25), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(i, 4, -25), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(25, 4, i), 1, 0, 0, 0, 0);
                                world.spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(-25, 4, i), 1, 0, 0, 0, 0);
                            }

                            // ==========================================
                            // ★ 境界線（25マスの地点）の「青い炎の柱」
                            // ==========================================
                            int[] edges = {-25, 0, 25}; // 四隅と、各辺の中央
                            for (int x : edges) {
                                for (int z : edges) {
                                    if (x == 0 && z == 0) continue; // 中心は除く
                                    for (int y = -4; y <= 4; y++) {
                                        world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, loc.clone().add(x, y, z), 1, 0, 0, 0, 0);
                                    }
                                }
                            }

                            // ==========================================
                            // ★ 追加: 次のスポナーを置く目安（端から25マス＝中心から50マスの地点）
                            // ==========================================
                            int[] nextPoints = {-50, 50};
                            for (int offset : nextPoints) {
                                for (int y = -4; y <= 4; y++) {
                                    // 前後左右の「50マス先」に、緑色のキラキラした柱を立てる！
                                    world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(offset, y, 0), 2, 0, 0, 0, 0);
                                    world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, y, offset), 2, 0, 0, 0, 0);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // ★追加: ONになった瞬間にすべてのスポナーを即座に隠すメソッド
    private void hideAllSpawners() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof ArmorStand) {
                        ArmorStand stand = (ArmorStand) entity;
                        for (String tag : stand.getScoreboardTags()) {
                            if (tag.startsWith("sao_spawner_")) {
                                stand.setVisible(false);
                                stand.setGlowing(false);
                                stand.setCustomNameVisible(false);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
}