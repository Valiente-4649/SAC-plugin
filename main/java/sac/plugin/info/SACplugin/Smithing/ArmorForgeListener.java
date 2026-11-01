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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOArmor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 金属防具鍛造の生産リスト・強化メニュー・初期強化割り振りメニューを扱う。
 * ForgeListener(生成)+UpgradeMenuListener(強化)の防具版で、武器と全く同じ考え方。
 */
public class ArmorForgeListener implements Listener {
    private final SACplugin plugin;
    private final Random random = new Random();

    private static final String CRAFTING_LIST_TITLE = "§b防具生成リスト";
    private static final String UPGRADE_TITLE = "§b防具強化";
    private static final String REPAIR_TITLE = "§b防具修理";
    private static final String INITIAL_ALLOC_TITLE = "§b初期強化を選択(防具)";

    private static final String[] STAT_SLOT_KEYS = {"DEF", "DUR", "WGT", "STR", "AGI"};
    private static final int[] STAT_SLOTS = {19, 20, 21, 22, 23};

    public ArmorForgeListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // 1. 防具生成リスト
    // ==========================================
    public void openArmorCraftingMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, CRAFTING_LIST_TITLE);

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        int prof = stats.proficiency.getOrDefault(ArmorForgeManager.ARMOR_FORGING_SKILL, 1);

        for (ArmorForgeRecipe recipe : plugin.getArmorForgeManager().getRecipes()) {
            if (recipe.requiredProficiency > prof + 1) continue;

            SAOArmor armor = plugin.getSaoItemManager().getArmor(recipe.resultArmorId);
            if (armor == null || !"金属".equals(armor.type)) continue;

            ItemStack icon = plugin.getSaoItemManager().createArmorItem(armor);
            ItemMeta meta = icon.getItemMeta();

            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add(" ");
            lore.add("§d[生産結果]");
            lore.add("§7生産成功 §f70% §8(初期強化+1 / EXP+10)");
            lore.add("§6大成功 §f25% §8(初期強化+3 / EXP+15)");
            lore.add("§b奇跡的成功 §f5% §8(初期強化+5 / EXP+20)");
            lore.add("§8(DEF/DUR/WGT/STR/AGIのいずれかを自分で選んで割り振れます)");
            lore.add(" ");
            lore.add("§e[必要Col]");
            lore.add("§7- " + recipe.cost + " Col");
            lore.add(" ");
            lore.add("§b[必要素材]");
            if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData mainData = plugin.getSaoItemManager().getGeneralItem(recipe.mainMaterialId);
                String mainName = (mainData != null && mainData.name != null) ? mainData.name : recipe.mainMaterialId;
                lore.add("§f[メイン] §7" + mainName + " x" + recipe.mainMaterialAmount);
            }
            if (recipe.subMaterials != null && !recipe.subMaterials.isEmpty()) {
                recipe.subMaterials.forEach((matId, amount) -> {
                    sac.plugin.info.SACplugin.item.SAOGeneralItemData data = plugin.getSaoItemManager().getGeneralItem(matId);
                    String name = (data != null && data.name != null) ? data.name : matId;
                    lore.add("§7[サブ]  " + name + " x" + amount);
                });
            }
            lore.add(" ");

            if (prof >= recipe.requiredProficiency) {
                lore.add("§a[熟練度達成] §7(必要熟練度: " + recipe.requiredProficiency + ")");
            } else {
                lore.add("§c[熟練度不足] §7(必要: " + recipe.requiredProficiency + ")");
            }

            // ★追加: 経験値獲得可否の表示(必要熟練度より30以上高いと獲得不可)
            if (prof - recipe.requiredProficiency < 30) {
                lore.add("§a[経験値獲得可能]");
            } else {
                lore.add("§c[経験値獲得不可] §7(必要熟練度より30以上高いため)");
            }

            lore.add("§0id:" + recipe.recipeId);

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }
        player.openInventory(inv);
    }

    // ==========================================
    // 2. 防具強化メニュー
    // ==========================================
    public void openArmorUpgradeMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, UPGRADE_TITLE);
        setupGlass(inv);
        updateStatButtons(player, inv, null);
        player.openInventory(inv);
    }

    // ==========================================
    // 2.5. 防具修理メニュー(武器のRepairMenuListenerと全く同じ考え方)
    // ==========================================
    public void openArmorRepairMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, REPAIR_TITLE);

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int i = 0; i < 27; i++) {
            if (i != 11 && i != 15) inv.setItem(i, glass);
        }

        updateRepairButton(player, inv, null);
        player.openInventory(inv);
    }

    private void updateRepairButton(Player player, Inventory inv, ItemStack armorItem) {
        if (armorItem == null || armorItem.getType() == Material.AIR) {
            ItemStack btn = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c左に防具をセットしてください");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        ItemMeta armorMeta = armorItem.getItemMeta();
        if (!armorMeta.hasLore()) return;

        String armorId = org.bukkit.ChatColor.stripColor(armorMeta.getLore().get(0));
        ArmorForgeRecipe recipe = plugin.getArmorForgeManager().getRecipeForArmor(armorId);

        if (recipe == null) {
            ItemStack btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§cこの防具は修理対象外です");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        // ==========================================
        // ★追加: armors.jsonの基礎値が変わっていないかチェックし、変わっていれば
        // 投資済みポイントを未割り振りポイントに変換して引き継ぐ(強化メニューと同じ処理)。
        // ==========================================
        sac.plugin.info.SACplugin.item.SAOArmor armorBaseForMigration = plugin.getSaoItemManager().getArmor(armorId);
        if (armorBaseForMigration != null) {
            boolean migrated = ArmorForgeManager.migrateArmorSchemeIfNeeded(armorItem, armorBaseForMigration);
            if (migrated) {
                player.sendMessage("§e[System] §fこの防具の仕様が更新されたため、割り振り済みステータスをリセットし、未割り振りポイントとして引き継ぎました。強化メニューから振り直してください。");
            }
        }

        int currentDura = -1;
        int maxDura = -1;
        for (String line : armorMeta.getLore()) {
            String plain = org.bukkit.ChatColor.stripColor(line);
            if (plain.startsWith("Durability: ")) {
                String[] parts = plain.replace("Durability: ", "").split(" / ");
                if (parts.length == 2) {
                    try {
                        currentDura = Integer.parseInt(parts[0].trim());
                        maxDura = Integer.parseInt(parts[1].trim());
                    } catch (Exception ignored) {}
                }
                break;
            }
        }

        if (currentDura >= 0 && currentDura >= maxDura) {
            ItemStack btn = new ItemStack(Material.GOLD_BLOCK);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§e耐久値は既に最大です");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        int prof = stats.proficiency.getOrDefault(ArmorForgeManager.ARMOR_FORGING_SKILL, 1);
        double totalProb = Math.min(100.0, 60.0 + prof * 0.2);

        // ★武器の修理と同じ比率: 素材は生成の1/2切り上げ
        int repairMain = recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()
                ? (int) Math.ceil(recipe.mainMaterialAmount / 2.0) : 0;

        // ★武器の修理と同じ式: col = 生産コストの0.6倍 + ボーナスステータス合計×100
        int totalPoints = ArmorForgeManager.getTotalBonusStats(armorItem);
        int cost = (int) Math.ceil(recipe.cost * 0.6) + totalPoints * 100;

        ItemStack btn = new ItemStack(Material.CAULDRON);
        ItemMeta meta = btn.getItemMeta();
        meta.setDisplayName("§b修理する");

        List<String> lore = new ArrayList<>();
        lore.add("§7現在の耐久値: §c" + currentDura + " §7/ §a" + maxDura);
        lore.add("");
        lore.add("§7成功確率: §e" + String.format("%.1f", totalProb) + "%");
        lore.add("§8(基本: 60.0% + " + ArmorForgeManager.ARMOR_FORGING_SKILL + " Lv." + prof + " ×0.2% = +"
                + String.format("%.1f", prof * 0.2) + "%)");
        lore.add("");
        lore.add("§e[必要Col]");
        lore.add("§7- " + cost + " Col §8(生産 " + recipe.cost + " ×0.6 + ステ合計" + totalPoints + "×100)");
        lore.add("");
        lore.add("§b[必要素材]（生成の1/2切り上げ）");
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            sac.plugin.info.SACplugin.item.SAOGeneralItemData mainData = plugin.getSaoItemManager().getGeneralItem(recipe.mainMaterialId);
            String mainName = (mainData != null) ? mainData.name : recipe.mainMaterialId;
            lore.add("§f[メイン] §7" + mainName + " x" + repairMain);
        }
        if (recipe.subMaterials != null) {
            recipe.subMaterials.forEach((matId, amount) -> {
                int repairAmt = (int) Math.ceil(amount / 2.0);
                sac.plugin.info.SACplugin.item.SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(matId);
                String name = (itemData != null) ? itemData.name : matId;
                lore.add("§7[サブ]  " + name + " x" + repairAmt);
            });
        }
        lore.add("");
        lore.add("§c失敗しても素材は失われます");

        meta.setLore(lore);
        btn.setItemMeta(meta);
        inv.setItem(15, btn);
    }

    private void attemptRepair(Player player, Inventory inv, ItemStack armorItem) {
        if (armorItem == null || armorItem.getType() == Material.AIR) return;
        if (!armorItem.hasItemMeta() || !armorItem.getItemMeta().hasLore()) return;

        String armorId = org.bukkit.ChatColor.stripColor(armorItem.getItemMeta().getLore().get(0));
        ArmorForgeRecipe recipe = plugin.getArmorForgeManager().getRecipeForArmor(armorId);
        if (recipe == null) return;

        SAOArmor base = plugin.getSaoItemManager().getArmor(armorId);
        if (base == null) return;

        // ★武器の修理と同じく、実際の修理操作のたびに現在の値で表示を再計算する
        ArmorForgeManager.applyBonusStatDeltas(armorItem, base, new int[5]);

        String forgingSkill = ArmorForgeManager.ARMOR_FORGING_SKILL;

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

        int totalPoints = ArmorForgeManager.getTotalBonusStats(armorItem);
        // ★変更: colは生産コストの0.6倍 + ボーナスステータス合計×100
        int cost = (int) Math.ceil(recipe.cost * 0.6) + totalPoints * 100;

        if (stats.col < cost) {
            player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        java.util.Map<String, Integer> repairMaterials = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            repairMaterials.put(recipe.mainMaterialId, (int) Math.ceil(recipe.mainMaterialAmount / 2.0));
        }
        if (recipe.subMaterials != null) {
            recipe.subMaterials.forEach((matId, amount) ->
                    repairMaterials.merge(matId, (int) Math.ceil(amount / 2.0), Integer::sum));
        }

        if (!plugin.getForgeManager().hasMaterials(player, repairMaterials)) {
            player.sendMessage("§b[System] §r§cINSUFFICIENT MATERIALS");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // 消費(成功・失敗に関わらず必ず消費する)
        stats.col -= cost;
        plugin.getForgeManager().consumeMaterials(player, repairMaterials);
        plugin.getPlayerManager().savePlayerData(player, stats);

        if (plugin.getSaoScoreboard() != null) {
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
        }

        int prof = stats.proficiency.getOrDefault(forgingSkill, 1);
        double totalProb = Math.min(100.0, 60.0 + prof * 0.2);
        boolean success = (random.nextDouble() * 100.0) < totalProb;

        if (success) {
            applyArmorRepair(armorItem);

            player.sendMessage("§e=================================");
            player.sendMessage("§b[System] §r§b       !! REPAIR SUCCESS !!");
            player.sendMessage("§b[System] §r§f       防具の修理に成功しました");
            player.sendMessage("§e=================================");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
        } else {
            player.sendMessage("§8=================================");
            player.sendMessage("§b[System] §r§c       ... REPAIR FAILED ...");
            player.sendMessage("§b[System] §r§7   防具の修理に失敗 素材が失われました");
            player.sendMessage("§8=================================");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
        }

        updateRepairButton(player, inv, armorItem);
    }

    /** 防具の耐久値を最大まで回復する(武器のapplyRepairと同じ考え方) */
    private void applyArmorRepair(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<String> lore = new ArrayList<>(meta.getLore());

        for (int i = 0; i < lore.size(); i++) {
            String plain = org.bukkit.ChatColor.stripColor(lore.get(i));
            if (plain.startsWith("Durability: ")) {
                String[] parts = plain.replace("Durability: ", "").split(" / ");
                if (parts.length == 2) {
                    try {
                        int maxDura = Integer.parseInt(parts[1].trim());
                        lore.set(i, "§7Durability: §a" + maxDura + " / " + maxDura);
                    } catch (Exception ignored) {}
                }
                break;
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    // ==========================================
    // 3. 初期強化選択メニュー(生産直後にその場で振らせる)
    // ==========================================
    public void openInitialAllocationMenu(Player player, ItemStack craftedItem) {
        Inventory inv = Bukkit.createInventory(null, 27, INITIAL_ALLOC_TITLE);
        setupGlass(inv);
        inv.setItem(11, craftedItem);
        updateInitialAllocButtons(inv, craftedItem);
        player.openInventory(inv);
    }

    private void setupGlass(Inventory inv) {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);

        for (int i = 0; i < 27; i++) {
            boolean isStatSlot = false;
            for (int s : STAT_SLOTS) if (s == i) isStatSlot = true;
            if (i != 11 && !isStatSlot) inv.setItem(i, glass);
        }
    }

    private static String statLabel(String key) {
        switch (key) {
            case "DEF": return "DEF (防御力+20/回)";
            case "DUR": return "DUR (耐久値+20/回)";
            case "WGT": return "WGT (重量-1/回)";
            case "STR": return "STR (プレイヤーSTR+1/回)";
            case "AGI": return "AGI (プレイヤーAGI+1/回)";
            default: return key;
        }
    }

    private static Material statIcon(String key) {
        switch (key) {
            case "DEF": return Material.SHIELD;
            case "DUR": return Material.ANVIL;
            case "WGT": return Material.FEATHER;
            case "STR": return Material.IRON_INGOT;
            case "AGI": return Material.SUGAR;
            default: return Material.PAPER;
        }
    }

    private void updateInitialAllocButtons(Inventory inv, ItemStack item) {
        int pending = ArmorForgeManager.getPendingPoints(item);

        for (int i = 0; i < STAT_SLOTS.length; i++) {
            String key = STAT_SLOT_KEYS[i];
            int currentValue = ArmorForgeManager.getBonusStat(item, key);

            ItemStack btn = new ItemStack(statIcon(key));
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName(pending > 0
                    ? "§a" + key + " に割り振る §7(現在値: +" + currentValue + ")"
                    : "§7" + key + " §8(現在値: +" + currentValue + ")");

            List<String> lore = new ArrayList<>();
            lore.add("§7" + statLabel(key));
            lore.add("");
            lore.add("§7残り初期強化ポイント: §b" + pending + "回");
            if (pending <= 0) {
                lore.add("§8割り振り完了です。インベントリを閉じて防具を受け取ってください");
            } else {
                lore.add("§eクリックでこのステータスに1ポイント割り振る(無料)");
            }

            meta.setLore(lore);
            btn.setItemMeta(meta);
            inv.setItem(STAT_SLOTS[i], btn);
        }
    }

    private void handleInitialAllocClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();

        ItemStack item = event.getInventory().getItem(11);
        if (item == null || item.getType() == Material.AIR) return;

        for (int i = 0; i < STAT_SLOTS.length; i++) {
            if (slot != STAT_SLOTS[i]) continue;

            int pending = ArmorForgeManager.getPendingPoints(item);
            if (pending <= 0) return;

            if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
            String armorId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
            SAOArmor base = plugin.getSaoItemManager().getArmor(armorId);
            if (base == null) return;

            int[] deltas = new int[5];
            deltas[i] = 1;
            ArmorForgeManager.applyBonusStatDeltas(item, base, deltas);
            ArmorForgeManager.setPendingPoints(item, pending - 1);

            Player player = (Player) event.getWhoClicked();
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

            updateInitialAllocButtons(event.getInventory(), item);

            if (pending - 1 <= 0) {
                player.sendMessage("§b[System] §r§a初期強化の割り振りが完了しました！インベントリを閉じて防具を受け取ってください。");
            }
            return;
        }
    }

    // ==========================================
    // 強化メニューのボタン更新(無料ポイントがあればそちらを優先表示)
    // ==========================================
    private void updateStatButtons(Player player, Inventory inv, ItemStack armorItem) {
        if (armorItem == null || armorItem.getType() == Material.AIR) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§c左に防具をセットしてください");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        ItemMeta armorMeta = armorItem.getItemMeta();
        if (!armorMeta.hasLore()) return;

        String armorId = org.bukkit.ChatColor.stripColor(armorMeta.getLore().get(0));
        ArmorForgeRecipe recipe = plugin.getArmorForgeManager().getRecipeForArmor(armorId);

        if (recipe == null) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.BARRIER);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§cこの防具は強化できません");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        // ==========================================
        // ★追加: armors.jsonの基礎値(Defense/Durability/Weight/強化上限)が変わっていないか
        // チェックし、変わっていれば投資済みポイントを未割り振りポイントに変換して引き継ぐ。
        // ==========================================
        sac.plugin.info.SACplugin.item.SAOArmor armorBaseForMigration = plugin.getSaoItemManager().getArmor(armorId);
        if (armorBaseForMigration != null) {
            boolean migrated = ArmorForgeManager.migrateArmorSchemeIfNeeded(armorItem, armorBaseForMigration);
            if (migrated) {
                player.sendMessage("§e[System] §fこの防具の仕様が更新されたため、割り振り済みステータスをリセットし、未割り振りポイントとして引き継ぎました。");
            }
        }

        int pendingPoints = ArmorForgeManager.getPendingPoints(armorItem);
        if (pendingPoints > 0) {
            for (int i = 0; i < STAT_SLOTS.length; i++) {
                String key = STAT_SLOT_KEYS[i];
                int currentValue = ArmorForgeManager.getBonusStat(armorItem, key);

                ItemStack btn = new ItemStack(statIcon(key));
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§d" + key + " に無料で割り振る §7(現在値: +" + currentValue + ")");

                List<String> lore = new ArrayList<>();
                lore.add("§7" + statLabel(key));
                lore.add("");
                lore.add("§d残りの無料割り振りポイント: §b" + pendingPoints + "回");
                lore.add("§8(鍛造の初期強化・旧仕様の防具の引継ぎ分)");
                lore.add("§eクリックでこのステータスに1ポイント無料で割り振る");

                meta.setLore(lore);
                btn.setItemMeta(meta);
                inv.setItem(STAT_SLOTS[i], btn);
            }
            return;
        }

        int attemptsUsed = ArmorForgeManager.getUpgradeAttempts(armorItem);
        if (attemptsUsed >= recipe.maxUpgrades) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.GOLD_BLOCK);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§e強化上限に達しています");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        // ★変更: 有料強化の成功回数(旧仕様引継ぎ分含む)だけを基準に成功率を計算する。
        // 生産・無料割り振り分は難易度に一切影響しない。colは生産コストの1.2倍固定。
        int upgradeDisplayCount = ArmorForgeManager.getUpgradeDisplayCount(armorItem);
        long cost = (long) Math.ceil(recipe.cost * 1.2);
        int baseRate = Math.max(2, 60 - upgradeDisplayCount * 4);

        java.util.Map<String, Integer> upgradeBase = plugin.getArmorForgeManager().getUpgradeMaterials(recipe);
        int requiredMain = upgradeBase.getOrDefault(recipe.mainMaterialId, 0);
        int remainingUpgrades = recipe.maxUpgrades - attemptsUsed;

        // ==========================================
        // ★追加: 表示時点でも、既に持っているメイン素材の余剰分を反映した「最終的な成功率」を見せる
        // ==========================================
        int heldMain = 0;
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            for (ItemStack inv2 : player.getInventory().getStorageContents()) {
                if (inv2 == null || !inv2.hasItemMeta() || !inv2.getItemMeta().hasLore()) continue;
                String id = org.bukkit.ChatColor.stripColor(inv2.getItemMeta().getLore().get(0));
                if (id.equals(recipe.mainMaterialId)) heldMain += inv2.getAmount();
            }
        }
        int extraMain = Math.max(0, heldMain - requiredMain);
        // ★修正: 実行側(performArmorUpgrade)と同じく、成功率の上限を90%に揃える
        int maxBonus = 90 - baseRate;
        int usableExtra = Math.min(extraMain, maxBonus / 2);
        int bonusRate = usableExtra * 2;
        int finalRate = Math.min(90, baseRate + bonusRate);

        for (int i = 0; i < STAT_SLOTS.length; i++) {
            String key = STAT_SLOT_KEYS[i];
            int currentValue = ArmorForgeManager.getBonusStat(armorItem, key);

            ItemStack btn = new ItemStack(statIcon(key));
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§a" + key + " を強化する §7(現在値: +" + currentValue + ")");

            List<String> lore = new ArrayList<>();
            lore.add("§7" + statLabel(key));
            lore.add("");
            lore.add("§7残り強化試行回数: §b" + remainingUpgrades + "回");
            if (bonusRate > 0) {
                lore.add("§7成功確率: §e" + finalRate + "% §8(基本" + baseRate + "% + メイン余剰" + usableExtra + "個×2%)");
            } else {
                lore.add("§7成功確率: §e" + finalRate + "% §8(基本60% - 有料強化成功" + upgradeDisplayCount + "回×4%)");
            }
            lore.add("§8メイン素材を必要数超えて追加すると +2%/個");
            lore.add("");
            lore.add("§e[必要Col] §7- " + cost + " Col");
            lore.add("§b[必要素材]（生産の2/3切り上げ）");
            if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData mainData = plugin.getSaoItemManager().getGeneralItem(recipe.mainMaterialId);
                String mainName = (mainData != null) ? mainData.name : recipe.mainMaterialId;
                lore.add("§f[メイン] §7" + mainName + " x" + requiredMain + " §8(+余剰1個につき確率+2%)");
            }
            if (recipe.subMaterials != null) {
                recipe.subMaterials.forEach((matId, amount) -> {
                    int upgradeAmt = (int) Math.ceil(amount * 2.0 / 3.0);
                    sac.plugin.info.SACplugin.item.SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(matId);
                    String name = (itemData != null) ? itemData.name : matId;
                    lore.add("§7[サブ]  " + name + " x" + upgradeAmt);
                });
            }
            lore.add("");
            lore.add("§c失敗すると素材・Colの半分と、最大耐久値-20を失います(試行回数も消費)");

            meta.setLore(lore);
            btn.setItemMeta(meta);
            inv.setItem(STAT_SLOTS[i], btn);
        }
    }

    private void attemptUpgrade(Player player, Inventory inv, ItemStack armorItem, String statKey) {
        if (armorItem == null || armorItem.getType() == Material.AIR) return;
        if (!armorItem.hasItemMeta() || !armorItem.getItemMeta().hasLore()) return;

        String armorId = org.bukkit.ChatColor.stripColor(armorItem.getItemMeta().getLore().get(0));
        ArmorForgeRecipe recipe = plugin.getArmorForgeManager().getRecipeForArmor(armorId);
        if (recipe == null) return;

        SAOArmor base = plugin.getSaoItemManager().getArmor(armorId);
        if (base == null) return;

        // ★追加: DEF/DUR/WGT/STR/AGI等の「1点あたりの上がり幅」を開発側で調整した場合に、
        // 既存アイテムにもその変更を反映させるため、実際の強化操作のたびに現在の値で
        // Defense/Durability/Weight/Bonus Stats表示を再計算する(差分0での再適用)。
        ArmorForgeManager.applyBonusStatDeltas(armorItem, base, new int[5]);

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        boolean hasSkillEquipped = false;
        for (String s : stats.equippedSkills) {
            if (ArmorForgeManager.ARMOR_FORGING_SKILL.equals(s)) { hasSkillEquipped = true; break; }
        }
        if (!hasSkillEquipped) {
            player.sendMessage("§b[System] §r§cスキルスロットに「" + ArmorForgeManager.ARMOR_FORGING_SKILL + "」をセットしていないため強化できません！");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // 無料の未割り振りポイントがあれば、そちらを優先して無料で消費する
        int pendingPoints = ArmorForgeManager.getPendingPoints(armorItem);
        if (pendingPoints > 0) {
            int[] freeDeltas = new int[5];
            for (int i = 0; i < ArmorForgeManager.STAT_KEYS.length; i++) {
                if (ArmorForgeManager.STAT_KEYS[i].equals(statKey)) freeDeltas[i] = 1;
            }
            ArmorForgeManager.applyBonusStatDeltas(armorItem, base, freeDeltas);
            ArmorForgeManager.setPendingPoints(armorItem, pendingPoints - 1);

            player.sendMessage("§d" + statKey + " §aに無料ポイントを1振りました！ §7(残り" + (pendingPoints - 1) + "回)");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

            updateStatButtons(player, inv, armorItem);
            return;
        }

        int attemptsUsed = ArmorForgeManager.getUpgradeAttempts(armorItem);
        if (attemptsUsed >= recipe.maxUpgrades) return;

        // ★変更: 有料強化の成功回数(旧仕様引継ぎ分含む)だけを基準にする。
        // 生産・無料割り振り分は難易度に一切影響しない。colは生産コストの1.2倍固定。
        int upgradeDisplayCount = ArmorForgeManager.getUpgradeDisplayCount(armorItem);
        long cost = (long) Math.ceil(recipe.cost * 1.2);

        if (stats.col < cost) {
            player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        java.util.Map<String, Integer> upgradeBase = plugin.getArmorForgeManager().getUpgradeMaterials(recipe);

        java.util.Map<String, Integer> subCheck = new java.util.HashMap<>();
        if (recipe.subMaterials != null) {
            recipe.subMaterials.forEach((matId, amount) -> {
                subCheck.put(matId, (int) Math.ceil(amount * 2.0 / 3.0));
            });
        }
        if (!plugin.getForgeManager().hasMaterials(player, subCheck)) {
            player.sendMessage("§b[System] §r§cサブINSUFFICIENT MATERIALS");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        int requiredMain = upgradeBase.getOrDefault(recipe.mainMaterialId, 0);
        java.util.Map<String, Integer> mainCheck = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && requiredMain > 0) {
            mainCheck.put(recipe.mainMaterialId, requiredMain);
        }
        if (!plugin.getForgeManager().hasMaterials(player, mainCheck)) {
            player.sendMessage("§b[System] §r§cメインINSUFFICIENT MATERIALS（最低 " + requiredMain + " 個必要）");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // ==========================================
        // ★追加: メイン素材を必要数より多く持っていれば、余剰分を消費して成功率を上げる
        // (武器の強化と同じ仕様。100%を超えないようにキャップする)
        // ==========================================
        int heldMain = 0;
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            for (ItemStack inv2 : player.getInventory().getStorageContents()) {
                if (inv2 == null || !inv2.hasItemMeta() || !inv2.getItemMeta().hasLore()) continue;
                String id = org.bukkit.ChatColor.stripColor(inv2.getItemMeta().getLore().get(0));
                if (id.equals(recipe.mainMaterialId)) heldMain += inv2.getAmount();
            }
        }

        int extraMain = Math.max(0, heldMain - requiredMain);

        int baseRate = Math.max(2, 60 - upgradeDisplayCount * 4);
        // ★変更: メイン素材の上乗せによる上限を100%→90%に変更(武器と同じ仕様)
        int maxBonus = 90 - baseRate;
        int usableExtra = Math.min(extraMain, maxBonus / 2);
        int bonusRate = usableExtra * 2;
        int finalRate = Math.min(90, baseRate + bonusRate);

        java.util.Map<String, Integer> consumeMap = new java.util.HashMap<>(subCheck);
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            consumeMap.put(recipe.mainMaterialId, requiredMain + usableExtra);
        }

        boolean success = random.nextInt(100) < finalRate;

        ArmorForgeManager.incrementUpgradeAttempts(armorItem);

        // ==========================================
        // ★追加: 武器と同じ強化ミニゲームを挟む。成功/失敗はすでに決まっており、
        // 目標回数は「生産時に叩いた回数」を基準に、成功なら+0〜2回、失敗ならそれ未満になる。
        // ==========================================
        int originalHitCount = ArmorForgeManager.getCraftHitCount(armorItem);
        int hitTarget = sac.plugin.info.SACplugin.Smithing.ForgeMinigameListener
                .computeUpgradeHitTarget(success, originalHitCount);

        player.closeInventory();

        plugin.getForgeMinigameListener().start(player, hitTarget, () -> {
            // ==========================================
            // ★変更: 消費量が成功/失敗で異なる(武器と同じ仕様)。
            // 成功時: 素材・Colを全額消費。
            // 失敗時: 素材・Colを"半分"だけ失い(端数切り上げ)、さらに防具の最大耐久値が20減少する。
            // ==========================================
            if (success) {
                stats.col -= cost;
                plugin.getForgeManager().consumeMaterials(player, consumeMap);
            } else {
                long halfCost = (long) Math.ceil(cost / 2.0);
                java.util.Map<String, Integer> halfConsumeMap = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, Integer> e : consumeMap.entrySet()) {
                    halfConsumeMap.put(e.getKey(), (int) Math.ceil(e.getValue() / 2.0));
                }
                stats.col -= halfCost;
                plugin.getForgeManager().consumeMaterials(player, halfConsumeMap);
                ArmorForgeManager.reduceMaxDurability(armorItem, 20);
            }
            plugin.getPlayerManager().savePlayerData(player, stats);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            if (success) {
                ArmorForgeManager.incrementUpgradeDisplayCount(armorItem);

                int[] deltas = new int[5];
                for (int i = 0; i < ArmorForgeManager.STAT_KEYS.length; i++) {
                    if (ArmorForgeManager.STAT_KEYS[i].equals(statKey)) deltas[i] = 1;
                }
                ArmorForgeManager.applyBonusStatDeltas(armorItem, base, deltas);

                player.sendMessage("§e=================================");
                player.sendMessage("§b[System] §r§a       !! UPGRADE SUCCESS !!");
                player.sendMessage("§f       " + statKey + " の強化に成功しました！ (+1)");
                if (bonusRate > 0) player.sendMessage("§b[System] §r§7       (メイン余剰" + usableExtra + "個消費で確率+" + bonusRate + "%)");
                player.sendMessage("§e=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

                int currentProf = stats.proficiency.getOrDefault(ArmorForgeManager.ARMOR_FORGING_SKILL, 1);
                int currentExp = stats.proficiencyExp.getOrDefault(ArmorForgeManager.ARMOR_FORGING_SKILL, 0);
                int limit = plugin.getLimitManager().getSkillLimit(ArmorForgeManager.ARMOR_FORGING_SKILL);
                // ★変更: 強化成功時の経験値を2に変更
                int expGain = (currentProf >= limit) ? 0 : 2;

                if (expGain > 0) {
                    currentExp += expGain;
                    player.sendMessage("§7[" + ArmorForgeManager.ARMOR_FORGING_SKILL + "] 経験値獲得: +" + expGain);

                    while (true) {
                        if (currentProf >= limit) {
                            currentExp = 0;
                            break;
                        }
                        // ★変更: 熟練度必要expを「20+L×(L-1)」の式に変更
                        int requiredExp = 20 + currentProf * (currentProf - 1);
                        if (currentExp >= requiredExp) {
                            currentExp -= requiredExp;
                            currentProf++;
                            player.sendMessage("§b[" + ArmorForgeManager.ARMOR_FORGING_SKILL + "] 熟練度が上がりました！ Lv." + (currentProf - 1) + " -> Lv." + currentProf);
                            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        } else {
                            break;
                        }
                    }

                    stats.proficiency.put(ArmorForgeManager.ARMOR_FORGING_SKILL, currentProf);
                    stats.proficiencyExp.put(ArmorForgeManager.ARMOR_FORGING_SKILL, currentExp);
                    plugin.getPlayerManager().savePlayerData(player, stats);

                    if (plugin.getSaoScoreboard() != null) {
                        plugin.getSaoScoreboard().updateScoreboard(player, stats);
                    }
                }
            } else {
                player.sendMessage("§8=================================");
                player.sendMessage("§b[System] §r§c       ... UPGRADE FAILED ...");
                player.sendMessage("§7   " + statKey + " の強化に失敗しました。素材とColの§c半分§7が失われ、");
                player.sendMessage("§b[System] §r§7   防具の最大耐久値が§c20§7減少しました");
                if (bonusRate > 0) player.sendMessage("§b[System] §r§7   (メイン余剰" + usableExtra + "個消費で確率+" + bonusRate + "% でも失敗...)");
                player.sendMessage("§8=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
        });
    }

    // ==========================================
    // クリック処理
    // ==========================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(INITIAL_ALLOC_TITLE)) {
            handleInitialAllocClick(event);
            return;
        }

        if (title.equals(CRAFTING_LIST_TITLE)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player)) return;
            Player player = (Player) event.getWhoClicked();
            ItemStack current = event.getCurrentItem();
            if (current == null || !current.hasItemMeta() || !current.getItemMeta().hasLore()) return;

            String recipeId = null;
            for (String line : current.getItemMeta().getLore()) {
                if (line.startsWith("§0id:")) {
                    recipeId = line.replace("§0id:", "");
                    break;
                }
            }
            if (recipeId == null) return;

            ArmorForgeRecipe recipe = plugin.getArmorForgeManager().getRecipe(recipeId);
            if (recipe == null) return;

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            int prof = stats.proficiency.getOrDefault(ArmorForgeManager.ARMOR_FORGING_SKILL, 1);

            if (prof < recipe.requiredProficiency) {
                player.sendMessage("§b[System] §r§c熟練度が足りません！");
                return;
            }

            boolean hasSkillEquipped = false;
            for (String s : stats.equippedSkills) {
                if (ArmorForgeManager.ARMOR_FORGING_SKILL.equals(s)) { hasSkillEquipped = true; break; }
            }
            if (!hasSkillEquipped) {
                player.sendMessage("§b[System] §r§cスキルスロットに「" + ArmorForgeManager.ARMOR_FORGING_SKILL + "」をセットしていないため生成できません！");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            if (stats.col < recipe.cost) {
                player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            java.util.Map<String, Integer> materials = plugin.getArmorForgeManager().getAllMaterials(recipe);
            if (!plugin.getForgeManager().hasMaterials(player, materials)) {
                player.sendMessage("§b[System] §r§cINSUFFICIENT MATERIALS");
                return;
            }

            stats.col -= recipe.cost;
            plugin.getPlayerManager().savePlayerData(player, stats);
            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            plugin.getForgeManager().consumeMaterials(player, materials);

            // ==========================================
            // ★変更: 武器と同じく、成功段階を先に抽選してから鍛造ミニゲームを挟む。
            // ==========================================
            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.CraftRoll craftRoll =
                    plugin.getArmorForgeManager().rollCraftTier();
            int hitTarget = sac.plugin.info.SACplugin.Smithing.ForgeMinigameListener
                    .computeCraftHitTarget(craftRoll.tierName, recipe.baseHitCount);

            player.closeInventory();

            plugin.getForgeMinigameListener().start(player, hitTarget, () -> {
                ItemStack craftedItem = plugin.getArmorForgeManager().craftArmor(player, recipe, craftRoll);
                if (craftedItem != null) {
                    sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.setCraftHitCount(craftedItem, hitTarget);
                    ItemStack finalItem = craftedItem;
                    Bukkit.getScheduler().runTask(plugin, () -> openInitialAllocationMenu(player, finalItem));
                }
            });
            return;
        }

        if (title.equals(UPGRADE_TITLE)) {
            Player player = (Player) event.getWhoClicked();
            int slot = event.getRawSlot();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.getOpenInventory().getTitle().equals(UPGRADE_TITLE)) {
                    updateStatButtons(player, event.getInventory(), event.getInventory().getItem(11));
                }
            }, 1L);

            if (event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }

            for (int i = 0; i < STAT_SLOTS.length; i++) {
                if (slot == STAT_SLOTS[i]) {
                    event.setCancelled(true);
                    attemptUpgrade(player, event.getInventory(), event.getInventory().getItem(11), STAT_SLOT_KEYS[i]);
                    return;
                }
            }

            if (slot == 11 || slot > 26) return;
            event.setCancelled(true);
        }

        if (title.equals(REPAIR_TITLE)) {
            Player player = (Player) event.getWhoClicked();
            int slot = event.getRawSlot();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.getOpenInventory().getTitle().equals(REPAIR_TITLE)) {
                    updateRepairButton(player, event.getInventory(), event.getInventory().getItem(11));
                }
            }, 1L);

            if (event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }

            if (slot == 15) {
                event.setCancelled(true);
                attemptRepair(player, event.getInventory(), event.getInventory().getItem(11));
                return;
            }

            if (slot == 11 || slot > 26) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(INITIAL_ALLOC_TITLE)) {
            event.setCancelled(true);
            return;
        }

        if (!title.equals(UPGRADE_TITLE) && !title.equals(REPAIR_TITLE)) return;

        for (int slot : event.getRawSlots()) {
            if (slot < 27 && slot != 11) {
                event.setCancelled(true);
                break;
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String openTitle = event.getWhoClicked().getOpenInventory().getTitle();
            if (openTitle.equals(UPGRADE_TITLE)) {
                updateStatButtons((Player) event.getWhoClicked(), event.getInventory(), event.getInventory().getItem(11));
            } else if (openTitle.equals(REPAIR_TITLE)) {
                updateRepairButton((Player) event.getWhoClicked(), event.getInventory(), event.getInventory().getItem(11));
            }
        }, 1L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(INITIAL_ALLOC_TITLE)) {
            Player player = (Player) event.getPlayer();
            ItemStack item = event.getInventory().getItem(11);

            if (item != null && item.getType() != Material.AIR) {
                int pending = ArmorForgeManager.getPendingPoints(item);
                if (pending > 0 && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                    String armorId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
                    SAOArmor base = plugin.getSaoItemManager().getArmor(armorId);
                    if (base != null) {
                        int[] deltas = new int[5];
                        for (int i = 0; i < pending; i++) deltas[random.nextInt(5)]++;
                        ArmorForgeManager.applyBonusStatDeltas(item, base, deltas);
                    }
                    ArmorForgeManager.setPendingPoints(item, 0);
                    player.sendMessage("§b[System] §r§7(残りの初期強化ポイントはランダムに割り振られました)");
                }

                if (!player.getInventory().addItem(item).isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), item);
                }
            }
            return;
        }

        if (!title.equals(UPGRADE_TITLE) && !title.equals(REPAIR_TITLE)) return;
        Player player = (Player) event.getPlayer();
        ItemStack armorItem = event.getInventory().getItem(11);

        if (armorItem != null && armorItem.getType() != Material.AIR) {
            if (!player.getInventory().addItem(armorItem).isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), armorItem);
            }
        }
    }
}