package sac.plugin.info.SACplugin.accessory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.List;

/**
 * ★新規: 鑑定NPC。ボスドロップ素材を入れて鑑定すると、
 * ステータスが抽選済みの「鑑定素材」になって返ってくる。
 */
public class AppraisalListener implements Listener {
    private final SACplugin plugin;
    private static final String GUI_TITLE = "§d《鑑定》";
    private static final String NPC_TAG = "sao_appraisal_npc";

    public AppraisalListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★設置コマンド: /sac appraisalnpc spawn
    // ==========================================
    public static class SpawnCommand implements CommandExecutor {
        private final SACplugin plugin;
        public SpawnCommand(SACplugin plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED");
                return true;
            }
            Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
            npc.setCustomName("§d《鑑定屋》");
            npc.setCustomNameVisible(true);
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.addScoreboardTag(NPC_TAG);
            player.sendMessage("§a[System] 鑑定NPCを設置しました！");
            return true;
        }
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        if (plugin.getVitalityManager() != null && plugin.getVitalityManager().isNpcClosed(event.getRightClicked())) return;
        if (!event.getRightClicked().getScoreboardTags().contains(NPC_TAG)) return;
        event.setCancelled(true);
        openAppraisalMenu(event.getPlayer());
    }

    private void openAppraisalMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        for (int i = 0; i < 27; i++) {
            if (i != 13 && i != 22) inv.setItem(i, createBackgroundItem());
        }

        // ★修正: slot13にヒント用アイテム(エンダーアイ)を置いていたが、
        // このスロットはそのまま素材投入スロットでもあるため、
        // 誤ってヒントアイテム自体を持ち出せてしまっていた。
        // ヒントアイテムは廃止し、slot13は空のまま素材投入用にする。
        // 説明文は確定ボタンのLoreに移す。

        ItemStack confirmBtn = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        confirmMeta.setDisplayName("§a鑑定する");
        List<String> confirmLore = new ArrayList<>();
        confirmLore.add("§7中央のスロットに鑑定したい");
        confirmLore.add("§7ボスドロップを入れてクリック");
        confirmMeta.setLore(confirmLore);
        confirmBtn.setItemMeta(confirmMeta);
        inv.setItem(22, confirmBtn);

        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    private ItemStack createBackgroundItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        // 背景・ヒント表示部分はクリック不可
        if (slot >= 0 && slot < 27 && slot != 13 && slot != 22) {
            event.setCancelled(true);
            return;
        }

        if (slot == 22) {
            event.setCancelled(true);
            performAppraisal(player, event.getInventory());
            return;
        }

        // slot13(素材投入スロット)はプレイヤー側アイテムの出し入れのみ許可
        if (slot == 13) {
            // 通常のアイテム入れ替えは許可(キャンセルしない)
            return;
        }
    }

    private void performAppraisal(Player player, Inventory inv) {
        ItemStack material = inv.getItem(13);
        if (material == null || material.getType() == Material.AIR || !material.hasItemMeta() || !material.getItemMeta().hasLore()) {
            player.sendMessage("§b[System] §r§c鑑定する素材を入れてください。");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        String idLine = org.bukkit.ChatColor.stripColor(material.getItemMeta().getLore().get(0));
        AccessoryMaterialData data = plugin.getAccessoryManager().getMaterial(idLine);
        if (data == null) {
            player.sendMessage("§b[System] §r§cこのアイテムは鑑定できません。");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // 素材を1個消費
        material.setAmount(material.getAmount() - 1);
        if (material.getAmount() <= 0) inv.setItem(13, null);

        List<AccessoryStatEntry> stats = plugin.getAccessoryManager().rollAppraisal(data.floor);
        ItemStack appraised = plugin.getAccessoryManager().createAppraisedMaterialItem(data, stats);

        var leftover = player.getInventory().addItem(appraised);
        for (ItemStack l : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), l);
        }

        StringBuilder msg = new StringBuilder("§d[鑑定結果] §f" + data.name + " §7→ ");
        List<String> parts = new ArrayList<>();
        for (AccessoryStatEntry s : stats) parts.add(s.type);
        msg.append(String.join(", ", parts)).append(" §7(計").append(stats.size()).append("個)");
        player.sendMessage(msg.toString());
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        Player player = (Player) event.getPlayer();
        ItemStack leftInSlot = event.getInventory().getItem(13);
        if (leftInSlot != null && leftInSlot.getType() != Material.AIR) {
            var leftover = player.getInventory().addItem(leftInSlot);
            for (ItemStack l : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), l);
            }
        }
    }
}