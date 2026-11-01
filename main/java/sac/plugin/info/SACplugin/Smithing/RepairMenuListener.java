package sac.plugin.info.SACplugin.Smithing;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.*;

public class RepairMenuListener implements Listener {
    private final SACplugin plugin;
    private final Random random = new Random();

    public RepairMenuListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    public void openRepairMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b武器修理");

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);

        for (int i = 0; i < 27; i++) {
            if (i != 11 && i != 15) inv.setItem(i, glass);
        }

        updateRightButton(player, inv, null);
        player.openInventory(inv);
    }

    // セットされた武器IDから、生成レシピ(weapon_forge.json)を逆引きする
    private ForgeRecipe getRecipeForWeapon(String weaponId) {
        for (ForgeRecipe recipe : plugin.getForgeManager().getRecipes()) {
            if (recipe.resultWeaponId.equals(weaponId)) {
                return recipe;
            }
        }
        return null; // 生成レシピが存在しないアイテムの場合はnull
    }

    private void updateRightButton(Player player, Inventory inv, ItemStack weaponItem) {
        if (weaponItem == null || weaponItem.getType() == Material.AIR) {
            ItemStack btn = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c左に武器をセットしてください");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        ItemMeta weaponMeta = weaponItem.getItemMeta();
        if (!weaponMeta.hasLore()) return;

        String weaponId = org.bukkit.ChatColor.stripColor(weaponMeta.getLore().get(0));
        ForgeRecipe recipe = getRecipeForWeapon(weaponId);

        // 生成レシピが存在しない武器（非売品・ドロップ専用など）は修理不可とする
        if (recipe == null) {
            ItemStack btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§cこの武器は修理対象外です");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        // 耐久値がそもそも減っているかチェック
        boolean isDamaged = false;
        for (String line : weaponMeta.getLore()) {
            if (line.contains("Durability:")) {
                try {
                    String[] parts = org.bukkit.ChatColor.stripColor(line).replace("Durability:", "").trim().split("/");
                    if (parts.length == 2) {
                        int currentDur = Integer.parseInt(parts[0].trim());
                        int maxDur = Integer.parseInt(parts[1].trim());
                        if (currentDur < maxDur) isDamaged = true;
                    }
                } catch (Exception ignored) {}
                break;
            }
        }

        // 耐久値がMAXならボタンを金ブロックにする
        if (!isDamaged) {
            ItemStack btn = new ItemStack(Material.GOLD_BLOCK);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§e耐久値は既に最大です");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        ItemStack btn = new ItemStack(Material.GRINDSTONE);
        ItemMeta meta = btn.getItemMeta();
        meta.setDisplayName("§b修理を開始する");

        List<String> lore = new ArrayList<>();
        // ★変更: 武器の攻撃属性に対応する鍛造スキルの熟練度で成功率を計算する
        SAOWeapon repairWeaponData = plugin.getSaoItemManager().getWeapon(weaponId);
        String forgingSkill = repairWeaponData != null
                ? sac.plugin.info.SACplugin.Smithing.ForgeManager.getForgingSkillForType(repairWeaponData.type)
                : null;

        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
        if (repairWeaponData != null) {
            sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(weaponItem, repairWeaponData);
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        int prof = (forgingSkill != null) ? stats.proficiency.getOrDefault(forgingSkill, 1) : 1;
        double bonusProb = prof * 0.2;
        double totalProb = Math.min(100.0, 60.0 + bonusProb);
        lore.add("§7成功確率: §e" + String.format("%.1f", totalProb) + "%");
        lore.add("§8(基本: 60.0% + " + (forgingSkill != null ? forgingSkill : "?") + " Lv." + prof + " ×0.2% = +" + String.format("%.1f", bonusProb) + "%)");
        lore.add("");

        // ★変更: 「強化値」の代わりに、5ステータス(STR/DUR/DEX/ACC/WGT)合計値を基準にする
        int totalPoints = sac.plugin.info.SACplugin.Smithing.ForgeManager.getTotalBonusStats(weaponItem);

        // ★変更: 修理コスト = 生産コストの0.6倍 + ボーナスステータス合計×100
        int cost = (int) Math.ceil(recipe.cost * 0.6) + totalPoints * 100;
        lore.add("§e[必要Col]");
        lore.add("§7- " + cost + " Col §8(生成 " + recipe.cost + " ×0.6 + ステ合計" + totalPoints + "×100)");
        lore.add("");
        lore.add("§b[必要素材]（生成の1/3切り上げ）");

        // ★修正: メイン・サブ素材ともに生成時の1/3切り上げ
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            int repairMain = (int) Math.ceil(recipe.mainMaterialAmount / 3.0);
            sac.plugin.info.SACplugin.item.SAOGeneralItemData mainData = plugin.getSaoItemManager().getGeneralItem(recipe.mainMaterialId);
            String mainName = (mainData != null) ? mainData.name : recipe.mainMaterialId;
            lore.add("§f[メイン] §7" + mainName + " x" + repairMain);
        }
        if (recipe.subMaterials != null) {
            recipe.subMaterials.forEach((matId, amount) -> {
                int repairSub = (int) Math.ceil(amount / 3.0);
                sac.plugin.info.SACplugin.item.SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(matId);
                String name = (itemData != null) ? itemData.name : matId;
                lore.add("§7[サブ]  " + name + " x" + repairSub);
            });
        }

        meta.setLore(lore);
        btn.setItemMeta(meta);
        inv.setItem(15, btn);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§b武器修理")) return;

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTitle().equals("§b武器修理")) {
                updateRightButton(player, event.getInventory(), event.getInventory().getItem(11));
            }
        }, 1L);

        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (slot == 15) {
            event.setCancelled(true);

            ItemStack weaponItem = event.getInventory().getItem(11);
            if (weaponItem == null || weaponItem.getType() == Material.AIR) return;

            String weaponId = org.bukkit.ChatColor.stripColor(weaponItem.getItemMeta().getLore().get(0));
            ForgeRecipe recipe = getRecipeForWeapon(weaponId);
            if (recipe == null) return;

            // ★追加: 武器の攻撃属性に対応する鍛造スキルがセットされているか確認
            SAOWeapon repairTargetWeapon = plugin.getSaoItemManager().getWeapon(weaponId);
            String forgingSkill = repairTargetWeapon != null
                    ? sac.plugin.info.SACplugin.Smithing.ForgeManager.getForgingSkillForType(repairTargetWeapon.type)
                    : null;

            // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
            if (repairTargetWeapon != null) {
                sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(weaponItem, repairTargetWeapon);
            }

            if (forgingSkill == null) {
                player.sendMessage("§b[System] §r§c未対応の攻撃属性のため修理できません。");
                return;
            }

            PlayerData stats = plugin.getPlayerManager().getStats(player);

            boolean hasSkillEquipped = false;
            for (String s : stats.equippedSkills) {
                if (forgingSkill.equals(s)) { hasSkillEquipped = true; break; }
            }
            if (!hasSkillEquipped) {
                player.sendMessage("§b[System] §r§cスキルスロットに「" + forgingSkill + "」をセットしていないため修理できません！");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            // ★変更: 「強化値」の代わりに、5ステータス合計値を基準にする
            int totalPoints = sac.plugin.info.SACplugin.Smithing.ForgeManager.getTotalBonusStats(weaponItem);

            // ★変更: 修理コスト = 生産コストの0.6倍 + ボーナスステータス合計×100
            int cost = (int) Math.ceil(recipe.cost * 0.6) + totalPoints * 100;
            if (stats.col < cost) {
                player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            // ★修正: 修理素材はメイン・サブともに生成の1/3切り上げ
            java.util.Map<String, Integer> repairMaterials = new java.util.HashMap<>();
            if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
                repairMaterials.put(recipe.mainMaterialId, (int) Math.ceil(recipe.mainMaterialAmount / 3.0));
            }
            if (recipe.subMaterials != null) {
                recipe.subMaterials.forEach((matId, amount) ->
                        repairMaterials.merge(matId, (int) Math.ceil(amount / 3.0), Integer::sum));
            }

            if (!plugin.getForgeManager().hasMaterials(player, repairMaterials)) {
                player.sendMessage("§b[System] §r§cINSUFFICIENT MATERIALS");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            // 消費
            stats.col -= cost;
            plugin.getForgeManager().consumeMaterials(player, repairMaterials);
            plugin.getPlayerManager().savePlayerData(player, stats);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            // ★修正: 成功率60% + 鍛造熟練度×0.2%
            int prof2 = stats.proficiency.getOrDefault(forgingSkill, 1);
            double totalProb = Math.min(100.0, 60.0 + prof2 * 0.2);
            boolean success = (random.nextDouble() * 100.0) < totalProb;

            if (success) {
                applyRepair(weaponItem);

                // ★追加: STR/DUR/DEX/ACC/WGT等の「1点あたりの上がり幅」を開発側で調整した場合に、
                // 既存アイテムにもその変更を反映させるため、修理成功時に現在の値で
                // Attack/Durability/Weight/Bonus Stats表示を再計算する(差分0での再適用)。
                if (repairTargetWeapon != null) {
                    sac.plugin.info.SACplugin.Smithing.ForgeManager.applyBonusStatDeltas(weaponItem, repairTargetWeapon, new int[5]);
                }

                player.sendMessage("§e=================================");
                player.sendMessage("§b[System] §r§b       !! REPAIR SUCCESS !!");
                player.sendMessage("§b[System] §r§f       武器の修理に成功しました");
                player.sendMessage("§e=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            } else {
                player.sendMessage("§8=================================");
                player.sendMessage("§b[System] §r§c       ... REPAIR FAILED ...");
                player.sendMessage("§b[System] §r§7   武器の修理に失敗 素材が失われました");
                player.sendMessage("§8=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
            return;
        }

        if (slot == 11 || slot > 26) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getView().getTitle().equals("§b武器修理")) return;
        for (int slot : event.getRawSlots()) {
            if (slot < 27 && slot != 11) {
                event.setCancelled(true);
                break;
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getWhoClicked().getOpenInventory().getTitle().equals("§b武器修理")) {
                updateRightButton((Player)event.getWhoClicked(), event.getInventory(), event.getInventory().getItem(11));
            }
        }, 1L);
    }

    // 耐久値を全回復する処理
    private void applyRepair(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;

        List<String> lore = meta.getLore();
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            if (line.contains("Durability:")) {
                try {
                    // 前回追加した「最大耐久値をそのまま読む」ロジックを流用！
                    String[] parts = org.bukkit.ChatColor.stripColor(line).replace("Durability:", "").trim().split("/");
                    if (parts.length == 2) {
                        int maxDur = Integer.parseInt(parts[1].trim());
                        // 現在の耐久値の数値を、最大値(maxDur)に上書きする
                        lore.set(i, "§7Durability: §a" + maxDur + " / " + maxDur);
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals("§b武器修理")) return;
        Player player = (Player) event.getPlayer();
        ItemStack weaponItem = event.getInventory().getItem(11);

        if (weaponItem != null && weaponItem.getType() != Material.AIR) {
            if (!player.getInventory().addItem(weaponItem).isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), weaponItem);
            }
        }
    }
}