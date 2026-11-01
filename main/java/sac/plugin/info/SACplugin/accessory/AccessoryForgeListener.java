package sac.plugin.info.SACplugin.accessory;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * ★新規: 加工台。鑑定素材を入れると、加工スキルの熟練度が足りていれば
 * 装備可能なアクセサリーに加工できる。武器の鍛冶台・料理台と同じ
 * 「BlockDisplay(見た目)+TextDisplay(ラベル)+Interaction(クリック判定)」方式で設置する。
 */
public class AccessoryForgeListener implements Listener {
    private final SACplugin plugin;
    private static final String GUI_TITLE = "§d《加工》";
    private static final String DISPLAY_TAG = "sao_accessoryforge_display";
    private static final String STATION_TAG = "sao_accessoryforge_station";

    // ★加工スキル名。熟練度はPlayerData.proficiency/proficiencyExpの既存マップを流用する。
    public static final String ACCESSORY_CRAFT_SKILL = "加工";
    // ★アクセサリー1個加工するごとに得られる経験値
    private static final int EXP_PER_CRAFT = 2;

    public AccessoryForgeListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** 熟練度Lvから、次のLvに必要な経験値を計算する: 20 + (Lv-1)×2 */
    public static int requiredExpForLevel(int level) {
        return 20 + (level - 1) * 2;
    }

    /** ★料理台(CookingManager)と全く同じ方式で加工台を設置する */
    public static void spawnAccessoryForgeStation(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        // 床に埋もれないよう、Y座標を1ブロック分上げます
        loc.setY(loc.getBlockY() + 1.0);
        loc.setZ(loc.getBlockZ() + 0.5);
        // ★追加: 元のlocationが持つyaw/pitchが意図せず影響している可能性があるため、明示的に0にリセットする
        loc.setYaw(0f);
        loc.setPitch(0f);

        BlockDisplay display = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setBlock(Bukkit.createBlockData(Material.LODESTONE));
        // TranslationのY軸が0のため、+1.0した位置から上に向かって綺麗にレンダリングされます
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, -0.5f), new org.joml.Quaternionf(),
                new Vector3f(1, 1, 1), new org.joml.Quaternionf()
        ));
        display.addScoreboardTag(DISPLAY_TAG);

        // テキストの高さは1.2のままで、ブロックの上に綺麗に表示されます
        Location textLoc = loc.clone().add(0, 1.2, 0);
        TextDisplay textDisplay = (TextDisplay) loc.getWorld().spawnEntity(textLoc, EntityType.TEXT_DISPLAY);
        textDisplay.setText("§d加工台");
        textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        textDisplay.addScoreboardTag(DISPLAY_TAG);

        Interaction interaction = (Interaction) loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionHeight(1.0f);
        interaction.setInteractionWidth(1.0f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag(STATION_TAG);
    }

    /** ★料理台と全く同じ方式で近くの加工台を削除する */
    public static void removeAccessoryForgeStation(Location location) {
        for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location, 3, 3, 3)) {
            if (entity.getScoreboardTags().contains(DISPLAY_TAG) || entity.getScoreboardTags().contains(STATION_TAG)) {
                entity.remove();
                entity.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, entity.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, 0.05);
            }
        }
    }

    // ==========================================
    // ★設置・削除コマンド: /accessoryforge spawn / remove
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

            if (args.length > 0 && args[0].equalsIgnoreCase("remove")) {
                removeAccessoryForgeStation(player.getLocation());
                player.sendMessage("§a[System] 近くの加工台を削除しました。");
                return true;
            }

            spawnAccessoryForgeStation(player.getLocation());
            player.sendMessage("§a[System] 加工台を設置しました！");
            return true;
        }
    }

    @EventHandler
    public void onStationClick(PlayerInteractAtEntityEvent event) {
        if (!event.getRightClicked().getScoreboardTags().contains(STATION_TAG)) return;
        event.setCancelled(true);

        // ==========================================
        // ★追加: 加工スキルをセットしているかチェック！(料理台と同じロジック)
        // ==========================================
        Player player = event.getPlayer();
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        boolean hasSkill = false;
        if (stats != null) {
            for (String s : stats.equippedSkills) {
                if (ACCESSORY_CRAFT_SKILL.equals(s)) { hasSkill = true; break; }
            }
        }
        if (!hasSkill) {
            player.sendMessage("§b[System] §r§cスキルスロットに「加工」をセットしていないため、加工台を使用できません！");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        openAccessoryForgeMenu(player);
    }

    private void openAccessoryForgeMenu(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
        for (int i = 0; i < 27; i++) {
            if (i != 13 && i != 22) inv.setItem(i, createBackgroundItem());
        }

        // ★修正: slot13にヒント用アイテム(金床)を置いていたが、
        // このスロットはそのまま素材投入スロットでもあるため、
        // 誤ってヒントアイテム自体を持ち出せてしまっていた。
        // ヒントアイテムは廃止し、slot13は空のまま素材投入用にする。
        // 説明文・熟練度は確定ボタンのLoreに移す。

        int prof = stats.proficiency.getOrDefault(ACCESSORY_CRAFT_SKILL, 1);
        ItemStack confirmBtn = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        confirmMeta.setDisplayName("§a加工する");
        java.util.List<String> confirmLore = new java.util.ArrayList<>();
        confirmLore.add("§7中央のスロットに鑑定素材を入れてクリック");
        confirmLore.add("§7加工熟練度: §eLv." + prof);
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

        if (slot >= 0 && slot < 27 && slot != 13 && slot != 22) {
            event.setCancelled(true);
            return;
        }

        if (slot == 22) {
            event.setCancelled(true);
            performCraft(player, event.getInventory());
        }
    }

    private void performCraft(Player player, Inventory inv) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        ItemStack material = inv.getItem(13);
        if (material == null || !plugin.getAccessoryManager().isAppraisedMaterial(material)) {
            player.sendMessage("§b[System] §r§c加工する鑑定素材を入れてください。");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        String materialId = plugin.getAccessoryManager().getMaterialId(material);
        AccessoryMaterialData data = plugin.getAccessoryManager().getMaterial(materialId);
        if (data == null) return;

        int currentProf = stats.proficiency.getOrDefault(ACCESSORY_CRAFT_SKILL, 1);
        if (currentProf < data.requiredProficiency) {
            player.sendMessage("§b[System] §r§c加工熟練度が足りません(必要: Lv." + data.requiredProficiency + " / 現在: Lv." + currentProf + ")");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // ★先にアクセサリーを生成してから、素材を1個消費する
        // (ItemMeta/PDCの読み取りは数量変更の影響を受けないため、順序を気にする必要はないが、
        //  可読性のため生成→消費の順にしておく)
        ItemStack accessory = plugin.getAccessoryManager().createAccessoryItem(material);
        if (accessory == null) {
            player.sendMessage("§b[System] §r§c加工に失敗しました(素材データの読み取りエラー)。");
            return;
        }

        material.setAmount(material.getAmount() - 1);
        if (material.getAmount() <= 0) inv.setItem(13, null);

        var leftover = player.getInventory().addItem(accessory);
        for (ItemStack l : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), l);
        }

        // 加工経験値の加算
        int currentExp = stats.proficiencyExp.getOrDefault(ACCESSORY_CRAFT_SKILL, 0);
        currentExp += EXP_PER_CRAFT;
        int limit = plugin.getLimitManager().getSkillLimit(ACCESSORY_CRAFT_SKILL);
        while (currentProf < limit) {
            int required = requiredExpForLevel(currentProf);
            if (currentExp >= required) {
                currentExp -= required;
                currentProf++;
                player.sendMessage("§b[" + ACCESSORY_CRAFT_SKILL + "] 熟練度が上がりました！ Lv." + (currentProf - 1) + " -> Lv." + currentProf);
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            } else {
                break;
            }
        }
        stats.proficiency.put(ACCESSORY_CRAFT_SKILL, currentProf);
        stats.proficiencyExp.put(ACCESSORY_CRAFT_SKILL, currentExp);
        plugin.getPlayerManager().savePlayerData(player, stats);
        if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

        player.sendMessage("§b[System] §r§aアクセサリーの加工に成功しました！ §7(加工経験値+" + EXP_PER_CRAFT + ")");
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