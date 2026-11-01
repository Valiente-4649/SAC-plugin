package sac.plugin.info.SACplugin.mob;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class SAOMobSkillExecutor {

    // スキルを発動するメインメソッド
    public static void castSkill(SACplugin plugin, LivingEntity mob, MobSkillData skill, SAOMobData mobData) {
        if (mob.getScoreboardTags().contains("sao_casting")) return; // 既に詠唱中なら無視
        // ★追加: パリィによる硬直中はスキルを発動しない
        if (mob.getScoreboardTags().contains("sao_parried")) return;
        mob.addScoreboardTag("sao_casting");

        // 1. モブの足を止める (元の速度を保存)
        double originalSpeed = mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        // ★【追加】スキル開始時の「視点の方向」を完全にロックする！
        final Vector lockedDir = mob.getLocation().getDirection().setY(0).normalize();
        final float lockedYaw = mob.getLocation().getYaw();
        final float lockedPitch = mob.getLocation().getPitch();

        // 赤く塗りつぶすパーティクルの設定
        Particle.DustOptions redDust = new Particle.DustOptions(Color.RED, 1.0f);

        // 2. 溜め時間（chargeTicks）中のエフェクト描画ループ
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (mob.isDead() || !mob.isValid()) {
                    this.cancel();
                    return;
                }

                // ★【追加】モブがプレイヤーの方を向こうとするのを防ぐため、向きを強制固定する
                Location currentLoc = mob.getLocation();
                if (Math.abs(currentLoc.getYaw() - lockedYaw) > 1.0f) {
                    currentLoc.setYaw(lockedYaw);
                    currentLoc.setPitch(lockedPitch);
                    mob.teleport(currentLoc); // 向きがズレたら即座に戻す
                }

                if (ticks >= skill.chargeTicks) {
                    this.cancel();
                    // --- 3. 溜め完了！攻撃実行 ---
                    executeAttack(plugin, mob, skill, mobData, originalSpeed, lockedDir);
                    return;
                }

                // 4tickに1回、範囲エフェクトを描画する (重さ対策)
                if (ticks % 4 == 0) {
                    drawAoE(mob, skill, redDust, lockedDir);
                    // ★ リアルタイムの向きではなく、固定された向き(lockedDir)を渡す
                    drawAoE(mob, skill, redDust, lockedDir);
                    mob.getWorld().playSound(mob.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f + (ticks * 0.05f));
                }

                ticks += 2; // ループ間隔
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // --- 形状ごとの赤い範囲エフェクト描画 ---
    private static void drawAoE(LivingEntity mob, MobSkillData skill, Particle.DustOptions dust, Vector lockedDir) {
        Location loc = mob.getLocation();
        Vector dir = lockedDir.clone();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize();
        int lv = skill.rangeLevel;

        switch (skill.attackId.toLowerCase()) {
            case "charge": // 突撃 (幅2固定、長さ 2 + lv)
                double chargeLen = 2.0 + lv;
                for (double f = 0.5; f <= chargeLen; f += 0.5) {
                    for (double s = -1.0; s <= 1.0; s += 0.5) {
                        spawnParticle(loc, dir, side, f, s, dust);
                    }
                }
                break;

            case "circle": // 円形 (半径 lv)
                double radius = lv;
                for (double r = 0.5; r <= radius; r += 0.5) {
                    for (int deg = 0; deg < 360; deg += 15) {
                        double rad = Math.toRadians(deg);
                        double x = Math.cos(rad) * r;
                        double z = Math.sin(rad) * r;
                        mob.getWorld().spawnParticle(Particle.DUST, loc.clone().add(x, 0.1, z), 1, dust);
                    }
                }
                break;

            case "cone_front": // 前方扇形 (半径 2 + lv)
                double coneRad = 2.0 + lv;
                for (double r = 0.5; r <= coneRad; r += 0.5) {
                    for (int deg = -45; deg <= 45; deg += 10) {
                        Vector rotDir = rotateVector(dir, deg);
                        mob.getWorld().spawnParticle(Particle.DUST, loc.clone().add(rotDir.multiply(r)).add(0, 0.1, 0), 1, dust);
                    }
                }
                break;

            case "rect_front": // 前方縦攻撃 (幅 2+lv、長さ 3+lv)
                double rLen = 3.0 + lv;
                double rWidth = 1.0 + (lv * 0.5); // 片側の幅
                for (double f = 0.5; f <= rLen; f += 0.5) {
                    for (double s = -rWidth; s <= rWidth; s += 0.5) {
                        spawnParticle(loc, dir, side, f, s, dust);
                    }
                }
                break;

            case "cone_side": // 横扇形 (左右に半径 2+lv)
                double sideRad = 2.0 + lv;
                for (double r = 0.5; r <= sideRad; r += 0.5) {
                    for (int deg = 45; deg <= 135; deg += 10) { // 右
                        mob.getWorld().spawnParticle(Particle.DUST, loc.clone().add(rotateVector(dir, deg).multiply(r)).add(0, 0.1, 0), 1, dust);
                        mob.getWorld().spawnParticle(Particle.DUST, loc.clone().add(rotateVector(dir, -deg).multiply(r)).add(0, 0.1, 0), 1, dust); // 左
                    }
                }
                break;

            case "jump_slam": // ジャンプ攻撃 (着地点の円形予測 半径 2+lv)
                double jumpRad = 2.0 + lv;
                for (double r = 0.5; r <= jumpRad; r += 0.5) {
                    for (int deg = 0; deg < 360; deg += 15) {
                        double rad = Math.toRadians(deg);
                        mob.getWorld().spawnParticle(Particle.DUST, loc.clone().add(Math.cos(rad) * r, 0.1, Math.sin(rad) * r), 1, dust);
                    }
                }
                break;
        }
    }

    // --- 実際の攻撃と移動処理 ---
    private static void executeAttack(SACplugin plugin, LivingEntity mob, MobSkillData skill, SAOMobData mobData, double originalSpeed, Vector lockedDir) {
        Location loc = mob.getLocation();
        Vector dir = lockedDir.clone(); // ★ 攻撃の進行方向もロックされた視点に固定
        int lv = skill.rangeLevel;

        // 特殊移動処理
        if (skill.attackId.equalsIgnoreCase("charge")) {
            // 突進 (先端まで素早く移動)
            mob.setVelocity(dir.clone().multiply(1.5 + (lv * 0.2)));
            mob.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.8f);

        } else if (skill.attackId.equalsIgnoreCase("jump_slam")) {
            // ジャンプ (高さ 1.0 + lv*0.5)
            mob.setVelocity(new Vector(0, 1.0 + (lv * 0.5), 0));
            mob.getWorld().playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 0.5f);
            // 落下ダメージ無効化のためのタグ
            mob.addScoreboardTag("sao_no_falldamage");
        }

        // 攻撃音
        mob.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);

        // ダメージ判定 (ジャンプ攻撃以外は即座に判定)
        if (!skill.attackId.equalsIgnoreCase("jump_slam")) {
            applyDamageToPlayers(plugin, mob, skill, mobData, lockedDir);
            resetMob(mob, originalSpeed);
        } else {
            // ジャンプ攻撃は着地するまで待ってからダメージ判定
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (mob.isDead() || !mob.isValid()) {
                        this.cancel();
                        return;
                    }
                    // Yの速度が下向きで、かつ地面にいる場合 (着地判定)
                    if (mob.getVelocity().getY() <= 0 && ((org.bukkit.entity.Entity) mob).isOnGround()) {
                        mob.getWorld().spawnParticle(Particle.EXPLOSION, mob.getLocation(), 2);
                        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_IRON_GOLEM_DEATH, 1.0f, 0.5f);
                        applyDamageToPlayers(plugin, mob, skill, mobData, lockedDir);
                        resetMob(mob, originalSpeed);
                        mob.removeScoreboardTag("sao_no_falldamage");
                        this.cancel();
                    }
                }
            }.runTaskTimer(plugin, 10L, 1L);
        }
    }

    // --- プレイヤーへのダメージ判定ロジック ---
    private static void applyDamageToPlayers(SACplugin plugin, LivingEntity mob, MobSkillData skill, SAOMobData mobData, Vector lockedDir) {
        Location mLoc = mob.getLocation();
        Vector dir = lockedDir.clone();
        int lv = skill.rangeLevel;

        double damage = mobData.attackDamage * skill.damageMultiplier;

        // ==========================================
        // ★修正1: 索敵レーダーを固定の10マスから、スキルの範囲(lv)に合わせて拡大！
        // ==========================================
        double searchRadius = lv + 5.0; // 範囲ギリギリのプレイヤーも拾えるように+5の余裕を持たせる
        for (org.bukkit.entity.Entity e : mob.getNearbyEntities(searchRadius, 10.0, searchRadius)) {
            if (!(e instanceof Player)) continue;
            Player p = (Player) e;

            // ==========================================
            // ★追加: クリエイティブとスペクテイターは攻撃を食らわないようにする！
            // ==========================================
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }

            Location pLoc = p.getLocation();

            // ==========================================
            // ★新規追加: Y軸(高さ)の厳密な当たり判定！
            // ==========================================
            double yDiff = pLoc.getY() - mLoc.getY(); // モブの足元を基準にしたプレイヤーの高さの差
            boolean isYHit = false;

            if (skill.attackId.equalsIgnoreCase("charge")) {
                // 突進: 下は -1ブロックまで。
                // 上はプレイヤーが「ジャンプで躱せる」絶妙な高さ(+1.2ブロック)までにする
                if (yDiff >= -1.0 && yDiff <= 1.2) {
                    isYHit = true;
                }
            } else {
                // それ以外のスキル(円範囲など): 上 +3ブロック、下 -2ブロック
                if (yDiff >= -2.0 && yDiff <= 3.0) {
                    isYHit = true;
                }
            }

            // 高さの条件を満たしていない場合(ジャンプで避けた、高台に避難した等)はノーダメージ！
            if (!isYHit) continue;

            Vector toPlayer = pLoc.toVector().subtract(mLoc.toVector()).setY(0);
            double dist = toPlayer.length();

            // ==========================================
            // ★修正2: ボスと完全に重なっている(距離0)時に無敵になるバグを修正！
            // ==========================================
            double angle = 0;
            if (dist > 0) {
                toPlayer.normalize();
                angle = Math.toDegrees(dir.angle(toPlayer));
            }

            boolean isHit = false;

            // 各スキルごとの当たり判定計算
            switch (skill.attackId.toLowerCase()) {
                case "charge": // 幅2、前方 2+lv
                    double forwardDist = toPlayer.dot(dir) * dist;
                    double sideDist = Math.abs(toPlayer.crossProduct(dir).getY() * dist); // クロス積のYで横ズレ取得
                    if (forwardDist >= 0 && forwardDist <= (2.0 + lv) && sideDist <= 1.0) isHit = true;
                    break;
                case "circle": // 半径 lv
                    if (dist <= lv) isHit = true;
                    break;
                case "cone_front": // 半径 2+lv、前方90度(±45度)
                    if (dist <= (2.0 + lv) && angle <= 45) isHit = true;
                    break;
                case "rect_front": // 前方3+lv、幅2+(lv)
                    double fDist2 = toPlayer.dot(dir) * dist;
                    double sDist2 = Math.abs(toPlayer.crossProduct(dir).getY() * dist);
                    if (fDist2 >= 0 && fDist2 <= (3.0 + lv) && sDist2 <= (1.0 + (lv * 0.5))) isHit = true;
                    break;
                case "cone_side": // 左右90度
                    if (dist <= (2.0 + lv) && angle >= 45 && angle <= 135) isHit = true;
                    break;
                case "jump_slam": // 着地時 半径 2+lv
                    if (dist <= (2.0 + lv)) isHit = true;
                    break;
            }

            if (isHit) {
                // ソードスキルと同様に独自HPシステムを削る
                PlayerData stats = plugin.getPlayerManager().getStats(p);
                if (stats != null) {

                    // ==========================================
                    // ★新規追加: 特殊攻撃の「パリィ」判定！
                    // ==========================================
                    sac.plugin.info.SACplugin.SACplugin mainPlugin = sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class);
                    if (mainPlugin.getParryManager() != null && mainPlugin.getParryManager().isParrying(p)) {

                        // ① ジャンプ攻撃や円形範囲はパリィ不可！
                        if (skill.attackId.equalsIgnoreCase("jump_slam") || skill.attackId.equalsIgnoreCase("circle")) {
                            p.sendMessage("§c[Parry] 攻撃が重すぎる！弾き返せない！");
                            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.7f);
                            mainPlugin.getParryManager().removeParry(p);
                        }
                        // ② パリィ可能な攻撃の場合
                        // ★追加: Heavinessボーナスが1以上あれば、パリィ可能な敵レベルを自分Lv+1まで許容する
                        else if (mobData.level <= stats.level + (sac.plugin.info.SACplugin.Smithing.ForgeManager.getHeavinessBonus(p.getInventory().getItemInMainHand()) > 0 ? 1 : 0)) {
                            // ★変更: 硬直させるため、removeParry()ではなくonParrySuccess()を呼ぶ
                            mainPlugin.getParryManager().onParrySuccess(p, mob);
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_PLACE, 1.0f, 1.8f);
                            p.getWorld().spawnParticle(org.bukkit.Particle.CRIT, p.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.5);
                            p.sendMessage("§a[Parry] 特殊攻撃を弾いた！");

                            org.bukkit.util.Vector kb = mob.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
                            kb.multiply(1.5).setY(0.4);
                            mob.setVelocity(kb);

                            sac.plugin.info.SACplugin.Event.SAODamageListener.reduceDurabilityOnly(p, p.getInventory().getItemInMainHand(), 1);
                            continue; // ★ パリィ成功！これより下のダメージ計算をすべてスキップする！
                        }
                        // ③ 敵のレベルが高すぎる場合
                        else {
                            p.sendMessage("§c[Parry] 敵のレベルが高すぎて弾き返せない！");
                            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f);
                            mainPlugin.getParryManager().removeParry(p);
                        }
                    }

                    // ==========================================
                    // ★追加: 刀チャージ中に特殊攻撃を受けたら「抜刀カウンター」発動！
                    // ==========================================
                    if (plugin.getSkillListener() != null && plugin.getSkillListener().isKatanaCharging(p)) {
                        plugin.getSkillListener().triggerBattouCounter(p);
                        continue;
                    }

                    // ==========================================
                    // ★追加: 特殊攻撃に対する「回避(AGI)」判定！
                    // ==========================================
                    if (mobData.level <= (stats.agi * 2)) {
                        if (Math.random() < 0.20) { // 20%の確率で回避
                            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f);
                            p.sendMessage("§7[System] §f特殊攻撃を回避しました");
                            p.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, p.getLocation().add(0, 1, 0), 5, 0.2, 0.5, 0.2, 0.05);
                            continue; // 避けたのでこのプレイヤーのダメージ処理をスキップ！
                        }
                    }

                    // ==========================================
                    // ★追加: setInvulnerable(true)(チュートリアル等)中は
                    // カスタムダメージ処理自体を完全にスキップする。
                    // ==========================================
                    if (p.isInvulnerable() ||
                            sac.plugin.info.SACplugin.Tutorial.TutorialFlowListener
                                    .isProtectedFromTutorialBoar(p, mob)) {
                        continue;
                    }

                    // ==========================================
                    // ★追加: 特殊攻撃に対する「防御力(DEF)」の計算！
                    // ==========================================
                    double finalDamage = Math.max(1.0, damage - stats.defense);

                    stats.currentHP -= finalDamage; // 計算後のダメージで削る

                    if (stats.currentHP <= 0) {
                        stats.currentHP = 0;
                        p.setHealth(0);
                    } else {
                        double visualHealth = (stats.currentHP / stats.maxHP) * 20.0;
                        p.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));
                    }
                    plugin.getSaoScoreboard().updateScoreboard(p, stats);
                    p.damage(0.01, mob); // 被弾モーション用

                    // ==========================================
                    // ★変更: スタン(旧・攻撃も封じる)と麻痺(新・解毒ポーションで解除可)を
                    // それぞれ独立して抽選付与する
                    // ==========================================
                    if (skill.stunChance > 0 && Math.random() < skill.stunChance) {
                        plugin.getStatusEffectManager().applyStun(p, skill.stunLevel);
                    }
                    if (skill.paralyzeChance > 0 && Math.random() < skill.paralyzeChance) {
                        plugin.getStatusEffectManager().applyParalysis(p, skill.paralyzeLevel);
                    }
                    if (skill.poisonChance > 0 && Math.random() < skill.poisonChance) {
                        plugin.getStatusEffectManager().applyPoison(p, skill.poisonDuration);
                    }
                }
            }
        }
    }

    private static void resetMob(LivingEntity mob, double originalSpeed) {
        mob.removeScoreboardTag("sao_casting");
        mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(originalSpeed);
    }

    // ヘルパーメソッド: パーティクルを方向に基づいて出す
    private static void spawnParticle(Location base, Vector dir, Vector side, double forward, double sideways, Particle.DustOptions dust) {
        Location pLoc = base.clone().add(dir.clone().multiply(forward)).add(side.clone().multiply(sideways)).add(0, 0.1, 0);
        base.getWorld().spawnParticle(Particle.DUST, pLoc, 1, dust);
    }

    // ヘルパーメソッド: ベクトルを指定角度回転させる
    private static Vector rotateVector(Vector v, double degrees) {
        double rad = Math.toRadians(degrees);
        double currentX = v.getX();
        double currentZ = v.getZ();
        double cosine = Math.cos(rad);
        double sine = Math.sin(rad);
        return new Vector((cosine * currentX) - (sine * currentZ), v.getY(), (sine * currentX) + (cosine * currentZ)).normalize();
    }
}