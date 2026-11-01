package sac.plugin.info.SACplugin.Event;

import org.bukkit.ChatColor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.SACplugin;

public class SAOSpawnerListener implements Listener {

    private final SACplugin plugin;

    public SAOSpawnerListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlaceSpawner(org.bukkit.event.entity.EntityPlaceEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof ArmorStand)) return;

        Player player = event.getPlayer();
        if (player == null) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta()) {
            item = player.getInventory().getItemInOffHand();
        }

        if (item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("SAO Spawner:")) {
            String name = item.getItemMeta().getDisplayName();
            String mobId = ChatColor.stripColor(name).replace("SAO Spawner: ", "").trim();

            ArmorStand stand = (ArmorStand) entity;
            stand.addScoreboardTag("sao_spawner_" + mobId); //
            stand.setPersistent(true);         // サーバー再起動後もデータを完全に保持する
            stand.setRemoveWhenFarAway(false);
            stand.setGravity(false);      // 重力オフ
            stand.setBasePlate(true);     // 台座あり
            stand.setCustomNameVisible(false); // 名前非表示

            player.sendMessage("§a[SAO] スポナー設置完了: §e" + mobId);
        }
    }
}