package sac.plugin.info.SACplugin.Event;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.item.WeaponTypeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

public class SAOSTRListener implements Listener {
    private final SACplugin plugin;
    private final UUID ATTACK_SPEED_UUID = UUID.fromString("f693d251-5b72-4632-849c-a2f009968433");

    // ★追加: 両手剣が最高速度に達しているかどうかの前回状態(プレイヤーごと)。
    // trueからfalseに戻った時にリセットすることで、再度最高速度に達した時にまた通知できるようにする。
    private final java.util.Map<UUID, Boolean> greatswordMaxSpeedNotified = new java.util.HashMap<>();

    public SAOSTRListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            // インベントリを閉じた時に攻撃速度を更新
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> updateAttackSpeed(player), 1L);
        }
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // プレイヤーのインベントリでホットバーのスロットをクリックした場合
        if (event.getInventory().getType() == InventoryType.PLAYER) {
            int slot = event.getSlot();
            // ホットバーのスロット（0-8）で、現在選択中のスロットの場合
            if (slot >= 0 && slot <= 8 && slot == player.getInventory().getHeldItemSlot()) {
                // 2tick後に攻撃速度を更新（アイテム変更が確実に反映されるように）
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> updateAttackSpeed(player), 2L);
            }
        }
    }

    @EventHandler
    public void onHeldItem(PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        // 持ち替え直後のバグを防ぐため1tick待つ
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> updateAttackSpeed(player), 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateAttackSpeed(event.getPlayer());
    }

    public void updateAttackSpeed(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        ItemStack item = player.getInventory().getItemInMainHand();

        // 1.0 = 100% (バニラの最速状態 4.0)
        double speedRatio = 1.0;
        // ★追加: 今回の判定で「両手剣が最高速度に達しているか」
        boolean reachedGreatswordMaxSpeed = false;

        if (item != null && item.hasItemMeta() && item.getItemMeta().hasLore()) {
            String weaponId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
            SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(weaponId);

            if (weapon != null) {
                // --- 【新・SAO計算式】 ---
                // weight 15 (Small Sword) / STR 0 のとき: 0.4 - (15 / 100) = 0.25 (1秒以上に1回攻撃)
                // weight 15 (Small Sword) / STR 10 のとき: 0.25 + 0.1 = 0.35 (少し速くなる)
                // 武器種ごとの最速値(BPM)を上限として、STR極振りでも上限を超えないようにする。
                WeaponTypeManager weaponTypeManager = plugin.getWeaponTypeManager();
                double maxSpeedRatio = weaponTypeManager != null
                        ? weaponTypeManager.getMaxSpeedRatio(weapon.category)
                        : 1.0;
                boolean strAffectsSpeed = weaponTypeManager == null
                        || weaponTypeManager.isStrAffectingSpeed(weapon.category);

                if (!strAffectsSpeed) {
                    // 短剣など、STR・重量を一切考慮せず常に最速値固定で振る武器種
                    speedRatio = maxSpeedRatio;
                } else {
                    boolean weightAffectsSpeed = weaponTypeManager == null
                            || weaponTypeManager.isWeightAffectingSpeed(weapon.category);
                    // 重量を無視する武器種はweight項を計算に含めない
                    double weightTerm = weightAffectsSpeed ? (weapon.weight / 100.0) : 0.0;
                    // ★変更: 防具のSTRボーナス(金属防具鍛造)も合算する
                    int effectiveStr = stats.str + stats.armorStrBonus;
                    speedRatio = 0.4 - weightTerm + (effectiveStr * 0.025);

                    // ★追加: 両手剣が「重量を上回って最高速度に達しているか」をクランプ前の値で判定する
                    if ("両手剣".equals(weapon.category) && speedRatio >= maxSpeedRatio) {
                        reachedGreatswordMaxSpeed = true;
                    }

                    // 最低速度 (0.1 = ゲージが溜まるのに非常に時間がかかる)
                    if (speedRatio < 0.1) speedRatio = 0.1;
                    // 最大速度 = 武器種ごとの最速値(BPMから換算)
                    if (speedRatio > maxSpeedRatio) speedRatio = maxSpeedRatio;
                }
            }
        }

        applyAttribute(player, speedRatio);

        // ==========================================
        // ★追加: 両手剣が最高速度に達した瞬間(未達成→達成に変わった時)だけ通知する。
        // 武器を持ち替えたり、STRで重量を上回った瞬間に表示され、外れたり武器を
        // 変えたりすると次に達成した時にまた表示されるようにする。
        // ==========================================
        UUID uuid = player.getUniqueId();
        boolean wasNotified = greatswordMaxSpeedNotified.getOrDefault(uuid, false);
        if (reachedGreatswordMaxSpeed && !wasNotified) {
            player.sendMessage("§b[STR] §fSTRボーナスにより通常攻撃§c1.3倍");
        }
        greatswordMaxSpeedNotified.put(uuid, reachedGreatswordMaxSpeed);
    }

    private void applyAttribute(Player player, double ratio) {
        AttributeInstance ai = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (ai == null) return;

        // 現在のModifierをすべてリセット（バニラの鉄の剣の-2.4などを消去）
        new ArrayList<>(ai.getModifiers()).forEach(ai::removeModifier);

        // ADD_SCALAR を使うと、ベース(4.0)に対して「1.0 + modifier」を掛け算する
        // ratioが0.25なら、4.0 * 0.25 = 1.0 (斧よりも遅い) になる
        double modifierValue = ratio - 1.0;

        AttributeModifier modifier = new AttributeModifier(
                ATTACK_SPEED_UUID,
                "SAO_STR_SPEED",
                modifierValue,
                AttributeModifier.Operation.ADD_SCALAR
        );

        ai.addModifier(modifier);
    }
    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> updateAttackSpeed(player), 1L);
    }
}