package sac.plugin.info.SACplugin.skill;

import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.ArrayList;
import java.util.List;

public class SwordSkillExecutor {
    // Five-stage slash model animation. Each frame uses a separate CMD model.
    // Minecraft 1.21.1: PAPER, CustomModelData 900001..900005.
    private static final int SLASH_CMD_BASE = 900000;
    private static final int SLASH_TOTAL_TICKS = 8;
    // The Blockbench slash is drawn in XZ (Y is the extrusion depth).
    // Keep XZ horizontal for Horizontal Arc: no X rotation.
    // Use the real slash5 geometry bounds, not the assumed 0..16 square.
    private static final float SLASH_CENTER_X = (3.9375f + 9.5625f) / 32.0f;
    private static final float SLASH_CENTER_Z = (0.625f + 15.0f) / 32.0f;
    private static final float SLASH_CENTER_Y = (-0.025f + 0.0625f) / 32.0f;
    private static final float SLASH_SCALE = 2.7f;

    // Restore the original world-space particle slash for every direction.
    // The existing legacy routine calculates its arc from the player's
    // forward and side vectors, so it can serve as a visual reference
    // for later 3D model alignment.
    public static void spawnSlash(Player player, SwordSkillData.SkillSlash slash, String colorStr, boolean isCounter) {
        if (player == null || slash == null || slash.position == null) return;
        spawnLegacySlash(player, slash, colorStr, isCounter);
    }

    private static void spawnLegacySlash(Player player, SwordSkillData.SkillSlash slash, String colorStr, boolean isCounter) {
        if (slash == null || slash.position == null) return;

        // ★変更: 体術専用エフェクト（WAVE_）。
        // 旧「波動」エフェクトから「紅の砂漠風の格闘エフェクト（突進＋打撃）」に置き換えました。
        if (slash.position.toUpperCase().startsWith("WAVE_")) {
            spawnMartialArtsEffect(player, slash.position.toUpperCase(), colorStr);
            return;
        }

        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return;

        final Location origin = player.getEyeLocation();

        // ★【修正3】真下や真上を向いている時にエラーでクラッシュするのを防ぐ
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() > 0.0001) {
            dir.normalize();
        } else {
            dir = origin.getDirection(); // 真上・真下の場合は元の向きをそのまま使う
        }

        final Vector direction = origin.getDirection().setY(0).normalize();
        final Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize();

        Vector startRel = new Vector(0, 0, 0);
        Vector endRel = new Vector(0, 0, 0);
        boolean isThrust = false;

        double dist = 3.0, w = 2.8, h = 1.3;
        String pos = slash.position.toUpperCase();

        // --- 判定ロジック ---
        switch (pos) {
            case "DIAGONAL_LEFT_DOWN": // 1: ↙ (右上から左下)
                startRel = side.clone().multiply(w * 0.8).add(new Vector(0, h, 0)); // 右上
                endRel = side.clone().multiply(-w * 0.8).add(new Vector(0, -h, 0)); // 左下
                break;
            case "DIAGONAL_RIGHT_DOWN": // 2: ↘ (左上から右下)
                startRel = side.clone().multiply(-w * 0.8).add(new Vector(0, h, 0)); // 左上
                endRel = side.clone().multiply(w * 0.8).add(new Vector(0, -h, 0)); // 右下
                break;
            case "HORIZONTAL_TOP_LEFT": // 上段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                break;
            case "HORIZONTAL_TOP_RIGHT": // 上段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                break;
            case "HORIZONTAL_MIDDLE_LEFT": // 中段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, 0, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, 0, 0));
                break;
            case "HORIZONTAL_MIDDLE_RIGHT": // 中段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, 0, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, 0, 0));
                break;
            case "HORIZONTAL_BOTTOM_LEFT": // 下段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                break;
            case "HORIZONTAL_BOTTOM_RIGHT": // 下段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                break;
            case "UP_LEFT": case "UP_MIDDLE": case "UP_RIGHT":// ↑
                double uOff = pos.contains("LEFT") ? -w*0.2 : pos.contains("RIGHT") ? w*0.2 : 0;
                startRel = side.clone().multiply(uOff).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(uOff).add(new Vector(0, h, 0));
                break;
            case "DOWN_LEFT": case "DOWN_MIDDLE": case "DOWN_RIGHT":// ↓
                double dOff = pos.contains("LEFT") ? -w*0.2 : pos.contains("RIGHT") ? w*0.2 : 0;
                startRel = side.clone().multiply(dOff).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(dOff).add(new Vector(0, -h, 0));
                break;
            case "DIAGONAL_UP_LEFT": // ↖
                startRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                break;
            case "DIAGONAL_UP_RIGHT": // ↗
                startRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                break;
            default:
                if (pos.startsWith("THRUST_")) {
                    isThrust = true;
                    double tx = pos.contains("LEFT") ? -w*0.3 : pos.contains("RIGHT") ? w*0.3 : 0;
                    double ty = pos.contains("UP") ? h*0.3 : pos.contains("DOWN") ? -h*0.3 : 0;
                    if (pos.contains("UP_RIGHT")) { tx = w*0.3; ty = h*0.3; }
                    else if (pos.contains("UP_LEFT")) { tx = -w*0.3; ty = h*0.3; }
                    else if (pos.contains("DOWN_RIGHT")) { tx = w*0.3; ty = -h*0.3; }
                    else if (pos.contains("DOWN_LEFT")) { tx = -w*0.3; ty = -h*0.3; }
                    startRel = side.clone().multiply(tx).add(new Vector(0, ty, 0));
                }
                break;
        }

        // --- 実行部分 ---
        final Color pColor = parseColor(colorStr);
        final Particle.DustOptions dustOptions = new Particle.DustOptions(pColor, 0.5f);
        final boolean finalIsThrust = isThrust;
        final Vector fStart = startRel, fEnd = endRel;

        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0;
            // ★短く設定（10 = 約0.5秒）。これで「キレ」のある残像になります。
            final int stayTicks = 10;

            @Override
            public void run() {
                if (step >= stayTicks) {
                    this.cancel();
                    return;
                }

                int points = 100;
                for (int i = 0; i < points; i++) {
                    double t = (double) i / points;
                    org.bukkit.util.Vector finalPos;

                    if (finalIsThrust) {
                        finalPos = direction.clone().multiply(1.0 + t * 4.5).add(fStart);
                    } else {
                        // --- 斬撃：奥行きのある高速な弧 ---
                        // stepごとに一気に前方へ押し出す（0.1 -> 0.4へ加速）
                        double expansion = step * 0.4;

                        // 手前に奥行きを出すために、初期距離(dist)を少し手前(3.0)から開始する設定もアリ
                        double currentDist = (dist - 1.0) + expansion;

                        org.bukkit.util.Vector offset = fStart.clone().add(fEnd.clone().subtract(fStart).multiply(t));

                        // 弧の膨らみ（奥行きの強調）
                        // 1.2 -> 2.5 に上げることで、よりプレイヤー側に突き刺さるような鋭い角度に
                        double curveFactor = Math.sin(t * Math.PI) * 3.0;

                        org.bukkit.util.Vector arcBoost = direction.clone().multiply(curveFactor);

                        finalPos = direction.clone().multiply(currentDist).add(offset).add(arcBoost);
                    }

                    org.bukkit.Location pLoc = origin.clone().add(finalPos);

                    // カウンター時はFLAME、通常はREDSTONE
                    if (isCounter) {
                        player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, pLoc, 1, 0, 0, 0, 0.01);
                    } else {
                        player.getWorld().spawnParticle(Particle.DUST, pLoc, 1, dustOptions);
                        player.getWorld().spawnParticle(Particle.DUST, pLoc, 1, dustOptions);
                    }
                }
                step++;
            }
        }.runTaskTimer(main, 0L, 1L);
    }

    // ==========================================
    // ★変更: 体術専用エフェクト（紅の砂漠風 格闘エフェクト）
    // 旧「WAVE_」のトリガーをそのまま使い、突進＋重厚な打撃エフェクトに置き換えました。
    // ==========================================
    private static void spawnMartialArtsEffect(Player player, String position, String colorStr) {
        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return;

        final Location origin = player.getLocation();
        final Vector direction = origin.getDirection().normalize();

        // WAVE_HIGH / WAVE_MID / WAVE_LOW に応じて打撃エフェクト（爆発）の高さを変える
        final double heightOffset;
        switch (position) {
            case "WAVE_HIGH":
                heightOffset = 1.3;
                break;
            case "WAVE_LOW":
                heightOffset = -1.3;
                break;
            default: // WAVE_MID
                heightOffset = 0.0;
                break;
        }

        // 突進開始の風切り音
        player.playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.8f);

        new BukkitRunnable() {
            int step = 0;
            // エフェクトの継続時間（約0.4秒後に打撃エフェクト）
            final int dashTicks = 8;

            @Override
            public void run() {
                Location loc = player.getLocation();

                // 1. 突進中のエフェクト（足元の土煙と体の周りの風）
                if (step < dashTicks) {
                }
                // 2. 打撃瞬間の重厚なエフェクト
                else if (step == dashTicks) {
                    // 少し前方に打撃判定の見た目をオフセットし、上中下段の高さを反映
                    Location impactLoc = loc.add(direction.clone().multiply(1.5)).add(0, 1.2 + heightOffset, 0);

                    // 重い打撃音と爆発音
                    player.getWorld().playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.2f);
                    player.getWorld().playSound(impactLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);

                    // 爆発とドーム状の衝撃波
                    player.getWorld().spawnParticle(Particle.EXPLOSION, impactLoc, 1, 0, 0, 0, 0);

                    // ※バージョンによって SONIC_BOOM が存在しない環境への対策
                    try {
                        player.getWorld().spawnParticle(Particle.valueOf("SONIC_BOOM"), impactLoc.subtract(0, 0.5, 0), 1, 0, 0, 0, 0);
                    } catch (IllegalArgumentException e) {
                        // SONIC_BOOMがない場合は煙で代用
                        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, impactLoc, 15, 0.5, 0.5, 0.5, 0.1);
                    }

                    // スキルの色（魔力）を混ぜてダークファンタジー感を強調
                    Color pColor = parseColor(colorStr);
                    Particle.DustOptions dustOptions = new Particle.DustOptions(pColor, 2.0f);
                    player.getWorld().spawnParticle(Particle.DUST, impactLoc, 20, 0.8, 0.8, 0.8, 1, dustOptions);

                    this.cancel();
                    return;
                }
                step++;
            }
        }.runTaskTimer(main, 0L, 1L);
    }

    // ★重要: spawnSlash メソッドの外に配置する
    private static Color parseColor(String str) {
        try {
            if (str != null && str.startsWith("#")) {
                int r = Integer.valueOf(str.substring(1, 3), 16);
                int g = Integer.valueOf(str.substring(3, 5), 16);
                int b = Integer.valueOf(str.substring(5, 7), 16);
                return Color.fromRGB(r, g, b);
            }
        } catch (Exception e) {
            // 解析失敗時はログを出さずデフォルトへ
        }
        return (str != null && str.equalsIgnoreCase("RED")) ? Color.RED : Color.AQUA;
    }

    // SwordSkillExecutor.java に追加
    public static void showRangePreview(Player player, SwordSkillData.SkillRange range) {
        Location origin = player.getLocation();
        Vector dir = origin.getDirection().setY(0).normalize(); // 前方
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize(); // 左右

        // 四隅の地点を計算
        // 前方の左右、後方の左右
        for (double f = -range.backward; f <= range.forward; f += 0.5) { // 縦の線
            for (double s : new double[]{-range.width / 2.0, range.width / 2.0}) {
                Location dot = origin.clone().add(dir.clone().multiply(f)).add(side.clone().multiply(s));
                player.getWorld().spawnParticle(Particle.DUST, dot.add(0, 0.1, 0), 1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1));
            }
        }
        for (double s = -range.width / 2.0; s <= range.width / 2.0; s += 0.5) { // 横の線
            for (double f : new double[]{-range.backward, range.forward}) {
                Location dot = origin.clone().add(dir.clone().multiply(f)).add(side.clone().multiply(s));
                player.getWorld().spawnParticle(Particle.DUST, dot.add(0, 0.1, 0), 1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1));
            }
        }
    }

    public static List<LivingEntity> getTargetsInRange(Player player, SwordSkillData.SkillRange range) {
        List<LivingEntity> targets = new ArrayList<>();
        Location origin = player.getLocation();
        // ★【修正3】ここも同様にエラー対策
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() > 0.0001) {
            dir.normalize();
        } else {
            dir = origin.getDirection();
        }
        Vector direction = dir;
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize(); // 横方向のベクトル

        // 周囲のエフェクト範囲内のエンティティを取得
        double searchRadius = Math.max(range.forward, range.width) + 2;
        for (Entity entity : player.getNearbyEntities(searchRadius, searchRadius, searchRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(player)) continue;

            if (entity instanceof Player) continue;

            if (entity instanceof org.bukkit.entity.ArmorStand) continue;

            // ==========================================
            // ★追加: NPC(村人)をスキルのロックオン対象から完全に除外する！
            // ==========================================
            if (entity instanceof org.bukkit.entity.Villager) {
                org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) entity;
                // 名前付き、またはショップ・クエストタグがあれば完全に無視する
                if (villager.getCustomName() != null) continue;

                boolean isNpc = false;
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) {
                        isNpc = true;
                        break;
                    }
                }
                if (isNpc) continue;
            }

            // プレイヤーから敵へのベクトル
            Vector toEntity = entity.getLocation().toVector().subtract(origin.toVector());

            // 各軸への投影（距離計算）
            double forwardDist = toEntity.dot(direction); // 前後の距離
            double sideDist = Math.abs(toEntity.dot(side)); // 左右の幅
            double heightDist = Math.abs(entity.getLocation().getY() - origin.getY()); // 高さの差

            // JSONの範囲内に収まっているか判定
            if (forwardDist >= -range.backward && forwardDist <= range.forward &&
                    sideDist <= (range.width / 2.0) &&
                    heightDist <= range.height) {
                targets.add((LivingEntity) entity);
            }
        }
        return targets;
    }

    // ★変更: 引数に double multiplier を追加
    public static void applySkillDamage(Player player, LivingEntity target, SwordSkillData skill, SAOWeapon weapon, double multiplier) {

        if (target instanceof org.bukkit.entity.Villager) {
            // ...(村人判定のコードはそのまま維持)...
            if (player.getInventory().getItemInMainHand().getType() != org.bukkit.Material.NETHERITE_SWORD) {
                org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) target;
                if (villager.getCustomName() != null) return; // 名前付きは無敵
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) return; // タグ付きも無敵
                }
            }
        }
        // ==========================================
        // ★修正: 実際のダメージ計算にも品質・強化ボーナスを適用する
        // ==========================================
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();

        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
        sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(item, weapon);

        double forgeBonus = sac.plugin.info.SACplugin.Event.SAODamageListener.getWeaponBonus(item);
        double upgradeBonus = sac.plugin.info.SACplugin.Event.SAODamageListener.getUpgradeBonus(item);

        // ★変更: skill.damageMultiplier ではなく、渡された multiplier を使う！
        double damage = (weapon.maxAttack + forgeBonus + upgradeBonus) * multiplier;

        // ==========================================
        // ★追加: 会心判定 (DEXによる会心率、ACCによる会心追加ダメージ)。通常攻撃と同じ仕様。
        // ==========================================
        int dexStat = sac.plugin.info.SACplugin.Smithing.ForgeManager.getBonusStat(item, "DEX");
        int accStat = sac.plugin.info.SACplugin.Smithing.ForgeManager.getBonusStat(item, "ACC");
        java.util.Random critRandom = new java.util.Random();
        boolean isCrit = dexStat > 0 && critRandom.nextInt(100) < (dexStat * 2);
        if (isCrit) {
            // ★変更: ACCは1につき「攻撃力の5%」を追加ダメージとして加算する
            damage += damage * accStat * 0.05;
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
        }

        // 2. 無敵時間を強制リセット（1回目から全段ヒットさせるため）
        target.setNoDamageTicks(0);

        // 4. 重要: 0.0ダメージで「攻撃した事実」だけを作る
        // これにより SAODamageListener が finalDamage を再計算するのを防ぎます
        target.damage(0.0, player);

        // 3. 直接HPを削る（これがバニラの防御や他リスナーを無視する）
        double currentHealth = target.getHealth();
        double newHealth = Math.max(0.0, currentHealth - damage);
        target.setHealth(newHealth);

        // ==========================================
        // ★追加: ソードスキルのダメージが入ったので、ここで即座にHPバーを更新！
        // ==========================================
        sac.plugin.info.SACplugin.SACplugin mainPlugin = sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class);
        if (mainPlugin != null && mainPlugin.getSaoMobManager() != null) {
            mainPlugin.getSaoMobManager().updateHpBar(target);
        }

        // ヒット演出 — 抜刀カウンター中はFLAME、通常スキルはCRIT
        org.bukkit.plugin.Plugin rawPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        sac.plugin.info.SACplugin.SACplugin sacPlugin = (rawPlugin instanceof sac.plugin.info.SACplugin.SACplugin)
                ? (sac.plugin.info.SACplugin.SACplugin) rawPlugin : null;
        boolean isBattouCounter = sacPlugin != null
                && sacPlugin.getSkillListener() != null
                && sacPlugin.getSkillListener().isBattouCounter(player);
        if (isBattouCounter) {
            target.getWorld().spawnParticle(org.bukkit.Particle.FLAME, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
            target.getWorld().spawnParticle(org.bukkit.Particle.LAVA, target.getLocation().add(0, 1, 0), 5, 0.2, 0.3, 0.2, 0);
        } else {
            target.getWorld().spawnParticle(org.bukkit.Particle.CRIT, target.getLocation().add(0, 1, 0), 10);
        }
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1.0f, 1.2f);

        // ★ ソードスキルのダメージポップアップ（濃い水色）
        sac.plugin.info.SACplugin.Event.SAODamageListener.showDamagePopup(
                sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class),
                target, damage, true
        );
    }

    /**
     * JSONのcolor文字列から対応するガラスブロックを返す
     */
    private static org.bukkit.Material getSlashMaterial(String color) {
        if (color == null) return org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS;
        switch (color.toUpperCase()) {
            case "RED": return org.bukkit.Material.RED_STAINED_GLASS;
            case "YELLOW": return org.bukkit.Material.YELLOW_STAINED_GLASS;
            case "PURPLE": return org.bukkit.Material.PURPLE_STAINED_GLASS;
            case "GREEN": return org.bukkit.Material.LIME_STAINED_GLASS;
            default: return org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS;
        }
    }
}