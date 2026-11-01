package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

/**
 * ★新規: 3層フロアボス「Nerius The Evil Treant」のAI。
 * 攻撃パターン(合計5種類):
 * 1. 範囲攻撃(20%): 15tick溜め、直径15、倍率1.0。着弾の瞬間ジャンプしていれば回避可能。
 * 2. 前方長方形攻撃(20%): 20tick溜め、幅10×奥行30、倍率1.5。
 * 3. ドレイン攻撃(20%): 20tick溜め、各プレイヤーの足元に直径4の範囲。命中者数×1000HPボスが回復(ダメージなし)。
 * 4. 毒範囲攻撃(40%): 10tick溜め、直径10、倍率0.5。命中したプレイヤーは100%毒状態になる。
 * 5. 特殊条件攻撃(HPが50%以下になった瞬間、1回だけ): 15秒詠唱。
 *    15秒間に2000ダメージ与えられなければ、最大HPの75%を回復する。
 */
public class NeriusAI extends FloorBossAI {

    // ★特殊条件攻撃は1体につき1回のみ
    private boolean specialUsed = false;

    public NeriusAI(SACplugin plugin, LivingEntity boss, SAOMobData mobData, BossData bossData) {
        super(plugin, boss, mobData, bossData);
        cooldownTick = 60; // 開幕3秒の猶予
    }

    @Override
    public void tick() {
        if (!isValid()) return;
        if (isCasting) return;
        if (getPlayersInRoom().isEmpty()) return;

        // ==========================================
        // ★特殊条件攻撃: HPが30%以下(BossBarManagerの「黄色」判定と同じ基準)になった瞬間、
        // クールダウンを無視して即発動する
        // ==========================================
        if (!specialUsed) {
            double maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (boss.getHealth() <= maxHealth * 0.30) {
                specialUsed = true;
                castHealChannel(maxHealth);
                return;
            }
        }

        if (cooldownTick > 0) { cooldownTick--; return; }

        double roll = Math.random() * 100.0;
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.5f);

        if (roll < 20) {
            // 1. 範囲攻撃(ジャンプで回避可能) 直径26
            castCircleAttackDodgeable(13, 10, 1.0, null, findSkill("circle"));
        } else if (roll < 40) {
            // 2. 前方長方形攻撃
            castRectangleAttack(10, 30, 20, 1.5, null, findSkill("rectangle"));
        } else if (roll < 60) {
            // 3. ドレイン攻撃(倍率2.0のダメージ + 命中者数×1000HP回復) 直径5
            castDrainAttack(2.5, 20, 2.0, 1000L, null);
        } else {
            // 4. 毒範囲攻撃(100%毒付与) 半径3、10tickで発生、持続20秒
            castPoisonCircleAttack(3.0, 10, 0.5, 20.0, null);
        }
    }

    /**
     * ★特殊条件攻撃: 15秒間(300tick)詠唱に入り、その間ボスは移動不可になる。
     * 詠唱終了時点で「詠唱開始時からのHP減少量」が2000未満なら、最大HPの75%を回復する。
     * 2000以上のダメージを与えられていれば阻止成功として何も起きない。
     */
    private void castHealChannel(double maxHealth) {
        isCasting = true;
        double originalSpeed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);

        double healthAtStart = boss.getHealth();
        broadcastToRoom("§4[Nerius] §cボスが大地の力を吸収");
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!isValid()) { cancel(); resetBoss(originalSpeed); isCasting = false; return; }

                if (ticks >= 300) { // 15秒
                    cancel();
                    double damageDealt = healthAtStart - boss.getHealth();
                    if (damageDealt < 2500) {
                        double healed = Math.min(maxHealth, boss.getHealth() + maxHealth * 0.75);
                        boss.setHealth(healed);
                        broadcastToRoom("§4[Nerius] §c阻止失敗　ボス回復");
                        boss.getWorld().spawnParticle(Particle.HEART, boss.getLocation().add(0, 2, 0), 30, 1, 1, 1, 0);
                        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.2f);
                    } else {
                        broadcastToRoom("§a[Nerius] §f詠唱　阻止成功");
                        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_HURT, 1.2f, 1.5f);
                    }
                    resetBoss(originalSpeed);
                    isCasting = false;
                    cooldownTick = randomCooldown();
                    return;
                }
                if (ticks % 10 == 0) {
                    boss.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, boss.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.05);
                    boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1f, 0.5f);
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public void onDeath() {
        broadcastToRoom("§b[BOSS CLEAR] Congratulations‼ ");
        broadcastToRoom("§b[BOSS CLEAR] Nerius The Evil Treant を討伐した！");
    }

    private void broadcastToRoom(String msg) {
        for (Player p : getPlayersInRoom()) p.sendMessage(msg);
    }
}