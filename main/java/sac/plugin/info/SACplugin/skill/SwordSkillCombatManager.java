package sac.plugin.info.SACplugin.skill;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SwordSkillCombatManager {
    private final SACplugin plugin;
    // モンスターごとの硬直解除タスクを管理
    private final Map<UUID, BukkitTask> freezeTasks = new HashMap<>();
    // ★追加: モンスターの「本来のノックバック耐性」を記憶するマップ
    private final Map<UUID, Double> originalKbMap = new HashMap<>();

    public SwordSkillCombatManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ソードスキル命中時に呼び出す
     * @param player 攻撃者
     * @param target 対象
     * @param isLastHit これがコンボの最後の一撃かどうか
     */
    public void applySkillHit(Player player, LivingEntity target, boolean isLastHit) {

        if (target instanceof org.bukkit.entity.Villager) {
            org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) target;
            if (villager.getCustomName() != null) return;
            for (String tag : villager.getScoreboardTags()) {
                if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) return;
            }
        }
        // 1. カテゴリーを取得
        String category = plugin.getSaoMobManager().getMobCategory(target);

        // 2. ボス判定 (FIELD_BOSS, FLOOR_BOSS は効果無効)
        boolean isBoss = category.equals("FIELD_BOSS") || category.equals("FLOOR_BOSS");

        if (isBoss) {
            target.getWorld().spawnParticle(Particle.CRIT, target.getEyeLocation(), 5, 0.5, 0.5, 0.5, 0.1);
            return;
        }

        // --- 以下、NORMAL / MINION 向けの処理 ---

        UUID uuid = target.getUniqueId();

        // 既存の硬直解除タスクがあればキャンセル（コンボ継続のため）
        if (freezeTasks.containsKey(uuid)) {
            freezeTasks.get(uuid).cancel();
            freezeTasks.remove(uuid);
        }

        AttributeInstance kbAttr = target.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
        double currentKb = (kbAttr != null) ? kbAttr.getBaseValue() : 0.0;

        // ★修正: まだ記憶していない場合のみ、現在の値を「本来の値」として保存する
        // これにより、連撃中に1.0になっても、最初の0.0を維持できる
        if (!originalKbMap.containsKey(uuid)) {
            originalKbMap.put(uuid, currentKb);
        }

        if (!isLastHit) {
            // === 連撃中 (硬直させる) ===

            target.setAI(false);

            // ノックバック耐性 MAX
            if (kbAttr != null) kbAttr.setBaseValue(1.0);

            // 動きを止める
            target.setVelocity(new Vector(0, 0, 0));

            // 硬直解除タイマーのセット
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    unfreezeMob(target);
                }
            }.runTaskLater(plugin, 12L); // 0.6秒猶予

            freezeTasks.put(uuid, task);

        } else {
            // === フィニッシュ (吹き飛ばす) ===

            // まず硬直を解き、耐性を元に戻す
            restoreMobStatus(target);

            // プレイヤーの向いている方向へ吹き飛ばす
            Vector direction = player.getLocation().getDirection().normalize();
            direction.setY(0.4);

            double strength = 1.5;
            target.setVelocity(direction.multiply(strength));

            target.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation().add(0, 1, 0), 1);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);

            // タスク管理から削除（念のため）
            if (freezeTasks.containsKey(uuid)) {
                freezeTasks.get(uuid).cancel();
                freezeTasks.remove(uuid);
            }
        }
    }

    /**
     * モブの状態（AIとKB耐性）を元に戻す共通メソッド
     */
    private void restoreMobStatus(LivingEntity target) {
        if (target == null || !target.isValid()) return;
        UUID uuid = target.getUniqueId();

        // AIを戻す
        target.setAI(true);

        // KB耐性をマップから取得して戻す
        AttributeInstance kbAttr = target.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
        if (kbAttr != null && originalKbMap.containsKey(uuid)) {
            kbAttr.setBaseValue(originalKbMap.get(uuid));
        } else if (kbAttr != null) {
            // マップになければ念のため0に戻す
            kbAttr.setBaseValue(0.0);
        }

        // データを削除
        originalKbMap.remove(uuid);
    }

    /**
     * タイマーによる自然解除用
     */
    private void unfreezeMob(LivingEntity target) {
        if (target == null || !target.isValid()) {
            if (target != null) {
                freezeTasks.remove(target.getUniqueId());
                originalKbMap.remove(target.getUniqueId());
            }
            return;
        }

        restoreMobStatus(target);
        freezeTasks.remove(target.getUniqueId());
    }
}
