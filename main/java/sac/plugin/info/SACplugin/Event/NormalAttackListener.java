package sac.plugin.info.SACplugin.Event;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.NormalAttackManager;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.skill.SwordSkillData;
import sac.plugin.info.SACplugin.skill.SwordSkillExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通常攻撃（左クリック）。
 *  - バニラの攻撃は SAODamageListener 側でキャンセルされる
 *  - 左クリックのたびに、武器種の stages を 1段目→2段目→…→最後→1段目 とループして発動
 *  - 各段の depth / width で範囲判定し、白い剣筋 or 突きを飛ばす
 *  - ダメージ計算は既存の SAODamageListener#onAttack をそのまま使う
 *    （target.damage() で内部ヒットを発生させる。その間だけ isInternalHit() が true になる）
 */
public class NormalAttackListener implements Listener {

    /** 判定を少しだけ後ろに伸ばす量（足元に重なった敵の取りこぼし防止） */
    private static final double HIT_BACKWARD = 0.5;

    /** 通常攻撃による内部ヒットを処理している間だけ true（メインスレッド専用） */
    private static boolean internalHit = false;

    public static boolean isInternalHit() {
        return internalHit;
    }

    private static final class ComboState {
        String category;
        int nextStage = 0;
        long lastAttackMs = 0L;
        /** この時刻になるまで次の通常攻撃はできない（武器を持ち替えても消えない） */
        long nextAllowedMs = 0L;
    }

    private final SACplugin plugin;
    private final Map<UUID, ComboState> states = new HashMap<>();

    public NormalAttackListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 左クリック = 腕を振った瞬間。空振り・ブロック・エンティティ攻撃のどれでも必ず飛ぶ。
     * （PlayerInteractEvent は敵を殴ったときに飛ばない環境があるため、こちらを使う）
     * 連打間隔は performNormalAttack 内で攻撃速度から計算して制限する。
     */
    @EventHandler(ignoreCancelled = false)
    public void onLeftClick(org.bukkit.event.player.PlayerAnimationEvent event) {
        if (event.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        performNormalAttack(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void performNormalAttack(Player player) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return;

        // スタン・麻痺中／疾走中は通常攻撃できない（SAODamageListener と同じ条件）
        if (plugin.getStatusEffectManager().isActionLocked(player)) return;
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats != null && "dash".equals(stats.selectedSkillId)) return;

        String category = resolveCategory(player, stats);
        NormalAttackManager.Config config = category == null ? null : plugin.getNormalAttackManager().get(category);
        ComboState state = states.computeIfAbsent(player.getUniqueId(), k -> new ComboState());
        if (config == null) {
            // 素手・オフハンド持ち・武器以外を持っているとき。
            // stateを消すと連打間隔の記録まで消えて「持ち替え連打で無限に攻撃できる」ので、コンボだけリセットする。
            state.category = null;
            state.nextStage = 0;
            return;
        }

        // ---- 連打間隔 ----
        // バニラの getAttackCooldown() は敵を殴った時点でバニラ側にリセットされてしまうので使わず、
        // 攻撃速度（maxSpeedBpm・重量・STR などが反映された GENERIC_ATTACK_SPEED）から自前で間隔を計算する。
        long now = System.currentTimeMillis();
        // 攻撃速度は SAOSTRListener が持ち替え時などにしか更新しない。ドロップ→同じスロットに拾い直し等では
        // 素手の速さのまま残るので、通常攻撃のたびに「今手に持っている物」で最新の値に更新してから読む。
        plugin.getStrListener().updateAttackSpeed(player);
        AttributeInstance speedAttr = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        double attackSpeed = speedAttr != null ? speedAttr.getValue() : 4.0;
        long intervalMs = Math.max(50L, (long) (1000.0 / Math.max(0.1, attackSpeed)) - 25L);
        // 念のための保険: 武器種の最速(maxSpeedBpm)より速くは絶対に振れないようにする。
        intervalMs = Math.max(intervalMs, config.minIntervalMs - 25L);
        // 直前の攻撃から intervalMs 以上空いていて、かつ前の武器で決まった次回可能時刻を過ぎていること
        if (now < state.nextAllowedMs || now - state.lastAttackMs < intervalMs) return;

        // ---- 段階の決定（時間切れ・武器種変更で1段目に戻る） ----
        if (!category.equals(state.category)
                || now - state.lastAttackMs > config.comboResetTicks * 50L
                || state.nextStage >= config.stages.size()) {
            state.category = category;
            state.nextStage = 0;
        }
        NormalAttackManager.Stage stage = config.stages.get(state.nextStage);
        state.nextStage = (state.nextStage + 1) % config.stages.size();
        state.lastAttackMs = now;
        state.nextAllowedMs = now + intervalMs;

        // ---- 見た目 → 判定 ----
        if (!"NONE".equals(stage.position)) {
            SwordSkillExecutor.SlashStyle style = SwordSkillExecutor.normalAttackStyle(
                    stage.position, stage.depth, stage.width, stage.height, stage.visualScale);
            SwordSkillExecutor.spawnNormalSlash(player, stage.position, style);
        }
        hitTargets(player, stage);
    }

    private void hitTargets(Player player, NormalAttackManager.Stage stage) {
        List<LivingEntity> targets = SwordSkillExecutor.getTargetsInBox(
                player, stage.depth, HIT_BACKWARD, stage.width, stage.height);
        if (targets.isEmpty()) return;

        internalHit = true;
        try {
            for (LivingEntity target : targets) {
                if (!target.isValid() || target.isDead()) continue;
                target.setNoDamageTicks(0);
                // 数値は何でもよい（0.05超であれば onAttack が武器ステータスから再計算する）
                target.damage(1.0, player);
            }
        } finally {
            internalHit = false;
        }
    }

    /**
     * 手に持っているものから武器種を決める。
     *  - SAO武器 → その武器の category
     *  - 素手 → セット中のスキルが体術系ならその category（SAODamageListener の素手判定と同じ）
     *  - それ以外 → null（通常攻撃なし）
     */
    private String resolveCategory(Player player, PlayerData stats) {
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() == Material.AIR) {
            if (stats == null || stats.selectedSkillId == null || stats.selectedSkillId.isEmpty()) return null;
            SwordSkillData skill = plugin.getSwordSkillManager().getSkill(stats.selectedSkillId);
            if (skill != null && plugin.getWeaponTypeManager().isUnarmed(skill.category)) return skill.category;
            return null;
        }

        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore() || meta.getLore().isEmpty()) return null;

        // Loreの1行目にweaponIdが入っている（WeaponReachListener / SAODamageListener と同じ方式）
        String weaponId = ChatColor.stripColor(meta.getLore().get(0));
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(weaponId);
        return weapon == null ? null : weapon.category;
    }
}