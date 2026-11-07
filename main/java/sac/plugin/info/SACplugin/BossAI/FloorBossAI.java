package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

import java.util.ArrayList;
import java.util.List;

/**
 * フロアボス専用AIの基底クラス。
 * 通常モブのAIループ(SAOMobManager)とは完全に独立して動作します。
 */

public abstract class FloorBossAI {
    protected final SACplugin plugin;
    protected final LivingEntity boss;
    protected final SAOMobData mobData;
    protected final BossData bossData;

    // 次の行動までのクールダウン(tick)
    protected int cooldownTick = 0;
    // 現在スキル実行中か
    protected boolean isCasting = false;

    // 赤パーティクル設定
    protected static final Particle.DustOptions RED_DUST = new Particle.DustOptions(Color.RED, 1.0f);

    public FloorBossAI(SACplugin plugin, LivingEntity boss, SAOMobData mobData, BossData bossData) {
        this.plugin = plugin;
        this.boss = boss;
        this.mobData = mobData;
        this.bossData = bossData;
    }

    /** 毎tick呼ばれるメインループ */
    public abstract void tick();

    /** 部屋内プレイヤーリストを取得 */
    /**
     * ★追加: mobs.jsonのspecialAttacksから、指定したattackIdに一致するMobSkillDataを探す。
     * 見つからなければnull(=状態異常なしとして扱われる)。
     */
    protected sac.plugin.info.SACplugin.mob.MobSkillData findSkill(String attackId) {
        if (mobData.specialAttacks == null) return null;
        for (sac.plugin.info.SACplugin.mob.MobSkillData s : mobData.specialAttacks) {
            if (s != null && attackId.equals(s.attackId)) return s;
        }
        return null;
    }

    protected List<Player> getPlayersInRoom() {
        if (bossData == null) {
            org.bukkit.World w = boss.getWorld();
            return w != null ? new ArrayList<>(w.getPlayers()) : new ArrayList<>();
        }
        return plugin.getBossManager().getPlayersInBossRoom(mobData.id);
    }

    protected Player getNearestPlayer() {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : getPlayersInRoom()) {
            double d = boss.getLocation().distanceSquared(p.getLocation());
            if (d < minDist) { minDist = d; nearest = p; }
        }
        return nearest;
    }

    protected Player getRandomPlayer() {
        List<Player> players = getPlayersInRoom();
        if (players.isEmpty()) return null;
        return players.get((int)(Math.random() * players.size()));
    }

    protected boolean isValid() {
        return boss != null && boss.isValid() && !boss.isDead();
    }

    /** ランダムなクールダウン (6〜10秒 = 120〜200tick) */
    protected int randomCooldown() {
        return 120 + (int)(Math.random() * 81);
    }

    // ==========================================
    // スキル共通発動メソッド
    // SAOMobSkillExecutorと同じ構造:
    // 1. 足を止める
    // 2. chargeTicks 分の予告パーティクル (BukkitRunnable)
    // 3. ダメージ適用
    // 4. 足を戻す
    // ==========================================

    /**
     * 自身中心の円範囲攻撃
     * @param radius 半径
     * @param chargeTicks 溜めtick数
     * @param damageMultiplier ダメージ倍率
     * @param onComplete 攻撃後のコールバック
     */
    protected void castCircleAttack(double radius, int chargeTicks, double damageMultiplier, Runnable onComplete) {
        castCircleAttack(radius, chargeTicks, damageMultiplier, onComplete, null);
    }

    /** ★追加: skillを渡すと、着弾ダメージでスタン/麻痺/毒も抽選される */
    protected void castCircleAttack(double radius, int chargeTicks, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        Location center = boss.getLocation().clone();

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    // ダメージ適用
                    afterVisual(() -> applyCircleDamage(center, radius, damageMultiplier, skill));
                    boss.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                    boss.getWorld().spawnParticle(Particle.EXPLOSION, center, 5, 1, 0.5, 1, 0);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                // 4tickに1回パーティクル描画
                if (ticks % 4 == 0) {
                    drawCircle(center, radius, RED_DUST);
                    boss.getWorld().playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * 前方ジャンプ攻撃 (前方distance先を中心にjumpRadius範囲)
     */
    protected void castJumpAttack(double distance, double jumpRadius, int chargeTicks, double damageMultiplier, Runnable onComplete) {
        castJumpAttack(distance, jumpRadius, chargeTicks, damageMultiplier, onComplete, null);
    }

    /** ★追加: skillを渡すと、着地ダメージでスタン/麻痺/毒も抽選される */
    protected void castJumpAttack(double distance, double jumpRadius, int chargeTicks, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        // 発動時点の向きをロック
        Vector lockedDir = boss.getLocation().getDirection().setY(0).normalize();
        double yaw = Math.toRadians(boss.getLocation().getYaw());
        double dx = -Math.sin(yaw) * distance;
        double dz =  Math.cos(yaw) * distance;
        Location targetCenter = boss.getLocation().clone().add(dx, 0, dz);
        Location safeLand = getSafeLocation(targetCenter);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    // ジャンプ着地
                    boss.teleport(safeLand);
                    afterVisual(() -> applyCircleDamage(safeLand, jumpRadius, damageMultiplier, skill));
                    boss.getWorld().playSound(safeLand, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
                    boss.getWorld().spawnParticle(Particle.EXPLOSION, safeLand, 20, 0.5, 0.5, 0.5, 0.1);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    // 着地予測地点に橙色パーティクル
                    drawCircle(safeLand, jumpRadius, RED_DUST);
                    boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * 対象プレイヤーへのジャンプ攻撃
     */
    protected void castJumpToPlayer(Player target, double jumpRadius, int chargeTicks, double damageMultiplier, Runnable onComplete) {
        castJumpToPlayer(target, jumpRadius, chargeTicks, damageMultiplier, onComplete, null);
    }

    /** ★追加: skillを渡すと、着地ダメージでスタン/麻痺/毒も抽選される */
    protected void castJumpToPlayer(Player target, double jumpRadius, int chargeTicks, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting || target == null) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        Location safeLand = getSafeLocation(target.getLocation().clone());

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    boss.teleport(safeLand);
                    afterVisual(() -> applyCircleDamage(safeLand, jumpRadius, damageMultiplier, skill));
                    boss.getWorld().playSound(safeLand, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
                    boss.getWorld().spawnParticle(Particle.EXPLOSION, safeLand, 20, 0.5, 0.5, 0.5, 0.1);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    drawCircle(safeLand, jumpRadius, RED_DUST);
                    boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * Asterios専用: 3連続ランダム爆撃
     */
    protected void castTripleBombard(double bombRadius, double damageMultiplier, Runnable onComplete) {
        castTripleBombard(bombRadius, damageMultiplier, onComplete, null);
    }

    /** ★追加: skillを渡すと、着弾ダメージでスタン/麻痺/毒も抽選される */
    protected void castTripleBombard(double bombRadius, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        // 3回分の爆撃をチェーンする
        chainBombard(bombRadius, damageMultiplier, 0, originalSpeed, onComplete, skill);
    }

    private void chainBombard(double radius, double mult, int hitCount, double originalSpeed, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        Player target = getRandomPlayer();
        if (target == null) {
            resetBoss(originalSpeed);
            isCasting = false;
            cooldownTick = randomCooldown();
            if (onComplete != null) onComplete.run();
            return;
        }
        Location bombLoc = target.getLocation().clone();

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= 15) { // 15tick後に爆発
                    cancel();
                    afterVisual(() -> applyCircleDamage(bombLoc, radius, mult, skill));
                    boss.getWorld().playSound(bombLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.4f);
                    boss.getWorld().spawnParticle(Particle.EXPLOSION, bombLoc, 3, 0.3, 0.3, 0.3, 0);

                    if (hitCount < 2) {
                        // 5tick後に次の爆撃
                        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                                chainBombard(radius, mult, hitCount + 1, originalSpeed, onComplete, skill), 5L);
                    } else {
                        // 3回終了
                        resetBoss(originalSpeed);
                        isCasting = false;
                        cooldownTick = randomCooldown();
                        if (onComplete != null) onComplete.run();
                    }
                    return;
                }
                // 予告パーティクル
                if (ticks % 2 == 0) {
                    drawCircle(bombLoc, radius, RED_DUST);
                    boss.getWorld().playSound(bombLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.5f);
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ==========================================
    // ★追加: ジャンプで回避可能な円範囲攻撃(3層ボス Nerius用)
    // 着弾の瞬間、空中(ジャンプ中)にいるプレイヤーはダメージを受けない。
    // ==========================================
    protected void castCircleAttackDodgeable(double radius, int chargeTicks, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        Location center = boss.getLocation().clone();

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    afterVisual(() -> applyCircleDamageDodgeable(center, radius, damageMultiplier, skill));
                    boss.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                    boss.getWorld().spawnParticle(Particle.EXPLOSION, center, 5, 1, 0.5, 1, 0);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    drawCircle(center, radius, RED_DUST);
                    boss.getWorld().playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    protected void applyCircleDamageDodgeable(Location center, double radius, double multiplier, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        double rawDamage = mobData.attackDamage * multiplier;
        for (Player p : getPlayersInRoom()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            // ★ジャンプ中(空中)なら回避成功として無視する
            if (!p.isOnGround()) continue;
            Location pLoc = p.getLocation();
            double dx = pLoc.getX() - center.getX();
            double dz = pLoc.getZ() - center.getZ();
            double dy = pLoc.getY() - center.getY();
            if (dx * dx + dz * dz <= radius * radius && dy >= -2.0 && dy <= 3.0) {
                applyDamage(p, rawDamage, skill);
            }
        }
    }

    // ==========================================
    // ★追加: 前方長方形攻撃(3層ボス Nerius用)
    // ボスの向いている方向を基準に、幅width・奥行きlengthの長方形範囲を攻撃する。
    // ==========================================
    protected void castRectangleAttack(double width, double length, int chargeTicks, double damageMultiplier, Runnable onComplete, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        Location origin = boss.getLocation().clone();
        float yaw = origin.getYaw();
        double rad = Math.toRadians(yaw);
        // 前方ベクトル・右方ベクトル(yawからの計算はcastJumpAttackと同じ規則)
        Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad)).normalize();
        Vector right = new Vector(forward.getZ(), 0, -forward.getX()).normalize();

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    afterVisual(() -> applyRectangleDamage(origin, forward, right, width, length, damageMultiplier, skill));
                    boss.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    drawRectangle(origin, forward, right, width, length);
                    boss.getWorld().playSound(origin, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    protected void applyRectangleDamage(Location origin, Vector forward, Vector right, double width, double length, double multiplier, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        double rawDamage = mobData.attackDamage * multiplier;
        for (Player p : getPlayersInRoom()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            Vector offset = p.getLocation().toVector().subtract(origin.toVector());
            double forwardDist = offset.dot(forward);
            double rightDist = offset.dot(right);
            double dy = p.getLocation().getY() - origin.getY();
            if (forwardDist >= 0 && forwardDist <= length && Math.abs(rightDist) <= width / 2.0 && dy >= -2.0 && dy <= 3.0) {
                applyDamage(p, rawDamage, skill);
            }
        }
    }

    /** 長方形範囲の予告パーティクルを描画 */
    protected void drawRectangle(Location origin, Vector forward, Vector right, double width, double length) {
        for (double f = 0; f <= length; f += 1.0) {
            for (double r = -width / 2.0; r <= width / 2.0; r += 1.0) {
                Location loc = origin.clone().add(forward.clone().multiply(f)).add(right.clone().multiply(r));
                loc.getWorld().spawnParticle(Particle.DUST, loc.getX(), loc.getY() + 0.1, loc.getZ(), 1, RED_DUST);
            }
        }
    }

    // ==========================================
    // ★変更: ドレイン攻撃(3層ボス Nerius用)
    // 部屋内の全プレイヤーの「現在の足元」に個別に円範囲を発動し、
    // 命中したプレイヤーにダメージを与えつつ、命中者数×healPerHitだけボスが回復する。
    // ==========================================
    protected void castDrainAttack(double radius, int chargeTicks, double damageMultiplier, long healPerHit, Runnable onComplete) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        // 発動時点の各プレイヤーの足元座標を固定しておく(詠唱中に動いても座標は変わらない)
        java.util.Map<Player, Location> targetLocs = new java.util.HashMap<>();
        for (Player p : getPlayersInRoom()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            targetLocs.put(p, p.getLocation().clone());
        }

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    double rawDamage = mobData.attackDamage * damageMultiplier;
                    int hitCount = 0;
                    for (Player p : getPlayersInRoom()) {
                        Location target = targetLocs.get(p);
                        if (target == null) continue;
                        if (p.getLocation().distanceSquared(target) <= radius * radius
                                && Math.abs(p.getLocation().getY() - target.getY()) <= 3.0) {
                            hitCount++;
                            final Player drainTarget = p;
                            afterVisual(() -> applyDamage(drainTarget, rawDamage, null));
                            p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
                            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.5f);
                        }
                    }
                    if (hitCount > 0 && isValid()) {
                        double maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                        double newHealth = Math.min(maxHealth, boss.getHealth() + (hitCount * healPerHit));
                        boss.setHealth(newHealth);
                        boss.getWorld().spawnParticle(Particle.HEART, boss.getLocation().add(0, 2, 0), hitCount, 0.5, 0.5, 0.5, 0);
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1f, 0.6f);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    for (Location target : targetLocs.values()) {
                        drawCircle(target, radius, RED_DUST);
                    }
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ==========================================
    // ★追加: 毒確定付与の円範囲攻撃(3層ボス Nerius用)
    // 命中したプレイヤーは確率に関わらず100%毒状態になる。
    // ==========================================
    protected void castPoisonCircleAttack(double radius, int chargeTicks, double damageMultiplier, double poisonDurationSeconds, Runnable onComplete) {
        if (isCasting) return;
        // ★追加: パリィによる硬直中は攻撃を発動しない
        if (boss.getScoreboardTags().contains("sao_parried")) return;
        isCasting = true;

        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        Location center = boss.getLocation().clone();

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); return; }

                if (ticks >= chargeTicks) {
                    cancel();
                    double rawDamage = mobData.attackDamage * damageMultiplier;
                    for (Player p : getPlayersInRoom()) {
                        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                        Location pLoc = p.getLocation();
                        double dx = pLoc.getX() - center.getX();
                        double dz = pLoc.getZ() - center.getZ();
                        double dy = pLoc.getY() - center.getY();
                        if (dx * dx + dz * dz <= radius * radius && dy >= -2.0 && dy <= 3.0) {
                            final Player poisonTarget = p;
                            afterVisual(() -> applyDamage(poisonTarget, rawDamage, null));
                            // ★確率に関わらず必ず毒を付与する
                            plugin.getStatusEffectManager().applyPoison(p, poisonDurationSeconds);
                        }
                    }
                    boss.getWorld().playSound(center, Sound.ENTITY_WITCH_DRINK, 1.2f, 0.6f);
                    boss.getWorld().spawnParticle(Particle.ITEM_SLIME, center, 40, radius / 2.0, 0.3, radius / 2.0, 0);
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                if (ticks % 4 == 0) {
                    drawCircle(center, radius, new Particle.DustOptions(Color.fromRGB(80, 200, 80), 1.0f));
                    boss.getWorld().playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.03f));
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ==========================================
    // ★ジャストパリィ対応: 攻撃の見た目(爆発の音・パーティクル)とダメージ判定を1tick(0.05秒)ずらす。
    // プレイヤーは見た目を見てパリィでき、見た目の直後〜判定までの間にパリィ受付が開始されていればジャストになる。
    // ==========================================
    protected static final long VISUAL_TO_DAMAGE_TICKS = 1L;

    protected void afterVisual(Runnable damage) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!isValid()) return;
            damage.run();
        }, VISUAL_TO_DAMAGE_TICKS);
    }

    // ==========================================
    // ダメージ適用
    // ==========================================
    protected void applyCircleDamage(Location center, double radius, double multiplier) {
        applyCircleDamage(center, radius, multiplier, null);
    }

    /** ★追加: この攻撃に対応するMobSkillDataを渡すと、状態異常(スタン/麻痺/毒)も抽選される */
    protected void applyCircleDamage(Location center, double radius, double multiplier, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        double rawDamage = mobData.attackDamage * multiplier;
        for (Player p : getPlayersInRoom()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            Location pLoc = p.getLocation();
            double dx = pLoc.getX() - center.getX();
            double dz = pLoc.getZ() - center.getZ();
            double dy = pLoc.getY() - center.getY();
            if (dx * dx + dz * dz <= radius * radius && dy >= -2.0 && dy <= 3.0) {
                applyDamage(p, rawDamage, skill);
            }
        }
    }

    protected void applyDamage(Player player, double rawDamage) {
        applyDamage(player, rawDamage, null);
    }

    /** ★追加: skillを渡すと、命中時にスタン/麻痺/毒を独立抽選して付与する */
    protected void applyDamage(Player player, double rawDamage, sac.plugin.info.SACplugin.mob.MobSkillData skill) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        // パリィ判定
        if (plugin.getParryManager() != null && plugin.getParryManager().isParrying(player)) {
            if (mobData.level <= stats.level) {
                // ★変更: 本当にパリィが成功した(ダメージを完全ブロックした)場合のみ
                // onParrySuccess()を呼び、攻撃してきたボスを1秒硬直させる。
                // ★ジャストパリィ: 受付開始が判定の0.1秒以内なら、円状のキラキラ演出 → 最大の瞬間に吹っ飛ばし・硬直 +
                // 特殊攻撃ダメージ(防御力を引く前の値)を等倍でボスへ反射する
                if (plugin.getParryManager().isJustParry(player)) {
                    plugin.getParryManager().onJustParrySuccess(player, boss, rawDamage);
                    player.sendMessage("§6§l[JUST PARRY!] §f完璧なタイミング！ 攻撃を跳ね返した！");
                    return;
                }
                plugin.getParryManager().onParrySuccess(player, boss);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_PLACE, 1f, 1.8f);
                player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.5);
                player.sendMessage("§a[Parry] 特殊攻撃を弾いた！");
                return;
            } else {
                plugin.getParryManager().removeParry(player);
                player.sendMessage("§c[Parry] 敵のレベルが高すぎて弾き返せない！");
            }
        }

        // 回避判定
        if (mobData.level <= stats.agi * 2 && Math.random() < 0.20) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
            player.sendMessage("§7[System] §f特殊攻撃を回避しました");
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0), 5, 0.2, 0.5, 0.2, 0.05);
            return;
        }

        double finalDamage = Math.max(1.0, rawDamage - stats.defense);
        stats.currentHP -= finalDamage;
        if (stats.currentHP <= 0) {
            stats.currentHP = 0;
            player.setHealth(0.0);
        } else {
            double visualHealth = (stats.currentHP / stats.maxHP) * 20.0;
            player.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));
        }

        // ★修正: player.damage(0.01, boss) は新たな EntityDamageByEntityEvent を発火させ、
        // SAODamageListener.onPlayerDamage がそれを「ボスの通常攻撃」として再度フルダメージ計算してしまい
        // 二重ダメージ(ワンパン)の原因になっていた。
        // 被弾モーション・赤い画面エフェクトのみを、新たなダメージイベントを発火させない方法で再現する。
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        player.spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0, 1, 0), 1, 0.2, 0.2, 0.2, 0);

        // ==========================================
        // ★追加: この攻撃にskillが紐付いていれば、命中時にスタン・麻痺・毒を独立抽選する
        // (通常モブの特殊攻撃と全く同じ仕組み)
        // ==========================================
        if (skill != null) {
            if (skill.stunChance > 0 && Math.random() < skill.stunChance) {
                plugin.getStatusEffectManager().applyStun(player, skill.stunLevel);
            }
            if (skill.paralyzeChance > 0 && Math.random() < skill.paralyzeChance) {
                plugin.getStatusEffectManager().applyParalysis(player, skill.paralyzeLevel);
            }
            if (skill.poisonChance > 0 && Math.random() < skill.poisonChance) {
                plugin.getStatusEffectManager().applyPoison(player, skill.poisonDuration);
            }
        }

        plugin.getPlayerManager().savePlayerData(player, stats);
        plugin.getSaoScoreboard().updateScoreboard(player, stats);
    }

    // ==========================================
    // ユーティリティ
    // ==========================================

    /** 円形の赤パーティクルを描画（内側も塗りつぶす） */
    protected void drawCircle(Location center, double radius, Particle.DustOptions dust) {
        // ★修正: 外周だけでなく、半径0.5刻みで内側まで塗りつぶす（通常モブのcircleと同じ方式）
        for (double r = 0.5; r <= radius; r += 0.5) {
            int points = Math.max(16, (int)(r * 8));
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double x = center.getX() + r * Math.cos(angle);
                double z = center.getZ() + r * Math.sin(angle);
                center.getWorld().spawnParticle(Particle.DUST, x, center.getY() + 0.1, z, 1, dust);
            }
        }
    }

    /** 壁を考慮した安全な着地場所を返す */
    protected Location getSafeLocation(Location target) {
        org.bukkit.block.Block feet = target.getBlock();
        org.bukkit.block.Block head = target.clone().add(0, 1, 0).getBlock();
        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return boss.getLocation();
        }
        return target;
    }

    /** ボスの速度・状態を元に戻す */
    protected void resetBoss(double originalSpeed) {
        // ★注意: パリィによる拘束はポーション効果(鈍足)で行っており、移動速度の基準値には
        // 一切触れないため、ここではパリィ中かどうかに関係なく常に元の速度へ戻してよい。
        // (以前はパリィ中の復元をスキップしていたが、溜め終わりと同時にパリィされると
        //  速度0のまま誰も戻さなくなるため、ガードは撤廃した)
        if (isValid()) {
            boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(originalSpeed);
        }
    }
}