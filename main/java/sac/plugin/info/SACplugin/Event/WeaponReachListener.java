package sac.plugin.info.SACplugin.Event;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * プレイヤーが武器を持ち替えたとき、武器種に対応したリーチを適用する。
 * 武器でないアイテム（素手含む）を持ったときはリーチをデフォルトに戻す。
 */
public class WeaponReachListener implements Listener {
    private final SACplugin plugin;

    public WeaponReachListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** 持ち替え時 */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        updateReach(player, newItem);
    }

    /** ログイン時（持ち物を復元後にリーチを適用） */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 1tick後に適用（インベントリが完全にロードされてから）
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            updateReach(player, player.getInventory().getItemInMainHand());
        }, 2L);
    }

    /**
     * 指定したアイテムがSAO武器なら武器種のリーチを適用し、
     * そうでなければデフォルトリーチに戻す。
     */
    private void updateReach(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            plugin.getWeaponTypeManager().removeReach(player);
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore() || meta.getLore().isEmpty()) {
            plugin.getWeaponTypeManager().removeReach(player);
            return;
        }

        // Loreの1行目にweaponIdが入っている（SAODamageListenerと同じ方式）
        String weaponId = ChatColor.stripColor(meta.getLore().get(0));
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(weaponId);

        if (weapon == null || weapon.category == null) {
            plugin.getWeaponTypeManager().removeReach(player);
            return;
        }

        // 武器種に対応したリーチを適用
        plugin.getWeaponTypeManager().applyReach(player, weapon.category);
    }
}
