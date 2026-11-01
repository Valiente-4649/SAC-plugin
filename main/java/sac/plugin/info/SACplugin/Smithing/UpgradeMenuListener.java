package sac.plugin.info.SACplugin.Smithing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class UpgradeMenuListener implements Listener, Reloadable {
    private final SACplugin plugin;
    private final List<WeaponUpgradeData> upgradeDataList = new ArrayList<>();
    private final Random random = new Random();

    // ★変更: 強化ではSharpness/Durability/Quickness/Accuracy/Heavinessのいずれか1つを選んで挑戦する。
    // それぞれのボタンをスロット19〜23に配置する。
    private static final String[] STAT_SLOT_KEYS = {"SHARP", "DURA", "QUICK", "ACCU", "HEAVY"};
    private static final int[] STAT_SLOTS = {19, 20, 21, 22, 23};

    // ★追加: 鍛造で生産した直後、その場でSTR/DUR/DEX/ACC/WGTに割り振らせる専用メニュー
    private static final String INITIAL_ALLOC_TITLE = "§b初期強化を選択";

    public UpgradeMenuListener(SACplugin plugin) {
        this.plugin = plugin;
        loadUpgradeData();
    }

    public void loadUpgradeData() {
        File file = new File(plugin.getDataFolder(), "weapon_upgrades.json");
        if (!file.exists()) plugin.saveResource("weapon_upgrades.json", false);
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<WeaponUpgradeData>>() {
            }.getType();
            List<WeaponUpgradeData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                upgradeDataList.clear();
                upgradeDataList.addAll(loaded);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public WeaponUpgradeData getUpgradeData(String weaponId) {
        for (WeaponUpgradeData data : upgradeDataList) {
            if (data.weaponId.equals(weaponId)) return data;
        }
        return null;
    }

    @Override
    public void reload() {
        loadUpgradeData();
    }

    public void openUpgradeMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b武器強化");

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);

        for (int i = 0; i < 27; i++) {
            boolean isStatSlot = false;
            for (int s : STAT_SLOTS) if (s == i) isStatSlot = true;
            if (i != 11 && !isStatSlot) inv.setItem(i, glass);
        }

        updateStatButtons(player, inv, null);
        player.openInventory(inv);
    }

    // ==========================================
    // ★追加: 鍛造で生産した直後に開く、初期強化ポイントの割り振り専用メニュー。
    // 武器は自動でスロット11にセットされ、材料・Colは一切消費しない(無料の割り振り)。
    // ==========================================
    public void openInitialAllocationMenu(Player player, ItemStack craftedItem) {
        Inventory inv = Bukkit.createInventory(null, 27, INITIAL_ALLOC_TITLE);

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);

        for (int i = 0; i < 27; i++) {
            boolean isStatSlot = false;
            for (int s : STAT_SLOTS) if (s == i) isStatSlot = true;
            if (i != 11 && !isStatSlot) inv.setItem(i, glass);
        }

        inv.setItem(11, craftedItem);
        updateInitialAllocButtons(inv, craftedItem);
        player.openInventory(inv);
    }

    private void updateInitialAllocButtons(Inventory inv, ItemStack item) {
        int pending = ForgeManager.getPendingPoints(item);

        for (int i = 0; i < STAT_SLOTS.length; i++) {
            String key = STAT_SLOT_KEYS[i];
            int currentValue = ForgeManager.getBonusStat(item, key);

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
                lore.add("§8割り振り完了です。インベントリを閉じて武器を受け取ってください");
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

            int pending = ForgeManager.getPendingPoints(item);
            if (pending <= 0) return;

            if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
            String weaponId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
            SAOWeapon base = plugin.getSaoItemManager().getWeapon(weaponId);
            if (base == null) return;

            int[] deltas = new int[5];
            deltas[i] = 1;
            ForgeManager.applyBonusStatDeltas(item, base, deltas);
            ForgeManager.setPendingPoints(item, pending - 1);

            Player player = (Player) event.getWhoClicked();
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

            updateInitialAllocButtons(event.getInventory(), item);

            if (pending - 1 <= 0) {
                player.sendMessage("§b[System] §r§a初期強化の割り振りが完了しました！インベントリを閉じて武器を受け取ってください。");
            }
            return;
        }
    }

    // ==========================================
    // ★追加: セットされた武器IDから、生成レシピ(weapon_forge.json)を逆引きする
    // ==========================================
    private ForgeRecipe getRecipeForWeapon(String weaponId) {
        for (ForgeRecipe recipe : plugin.getForgeManager().getRecipes()) {
            if (recipe.resultWeaponId.equals(weaponId)) {
                return recipe;
            }
        }
        return null; // 生成レシピが存在しないアイテムの場合はnull
    }

    // ==========================================
    // 「強化試行回数」は成功・失敗に関わらず攻撃を試みるたびに必ず+1、修理などでは変化しない。
    // ==========================================
    // ==========================================
    // ★修正: 隠しデータ(§0で色を消したLore行)は、使用中のリソースパックの
    // ツールチップ背景によっては非表示にならず文字が見えてしまう不具合があったため、
    // PersistentDataContainer(PDC)へ移行した。
    // ==========================================
    private org.bukkit.NamespacedKey upgradeAttemptsKey() {
        return new org.bukkit.NamespacedKey(plugin, "upgrade_attempts");
    }

    private int getUpgradeAttempts(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Integer stored = meta.getPersistentDataContainer().get(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        if (stored != null) return stored;
        // 後方互換: 過去にLoreへ書き込まれてしまった旧タグが残っていれば読む
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line.startsWith("§0UpgradeAttempts:")) {
                    try {
                        return Integer.parseInt(line.replace("§0UpgradeAttempts:", ""));
                    } catch (Exception e) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    private void incrementUpgradeAttempts(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        int current = getUpgradeAttempts(item);
        meta.getPersistentDataContainer().set(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER, current + 1);
        item.setItemMeta(meta);

        // 過去にLoreへ紛れ込んでしまった旧タグがあれば掃除する
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            List<String> cleaned = new ArrayList<>();
            boolean changed = false;
            for (String line : lore) {
                if (line.startsWith("§0UpgradeAttempts:")) { changed = true; continue; }
                cleaned.add(line);
            }
            if (changed) {
                ItemMeta cleanMeta = item.getItemMeta();
                cleanMeta.setLore(cleaned);
                item.setItemMeta(cleanMeta);
            }
        }
    }

    private static String statLabel(String key) {
        switch (key) {
            case "SHARP": return "Sharpness (Attack+3/回)";
            case "DURA": return "Durability (耐久値+50/回)";
            case "QUICK": return "Quickness (重量-1/回, スキル発動-0.1秒/回 ※下限0.5秒)";
            case "ACCU": return "Accuracy (命中率+1%/回)";
            case "HEAVY": return "Heaviness (重量+1/回, パリィ可能Lv+1, Attack上乗せ)";
            default: return key;
        }
    }

    private static Material statIcon(String key) {
        switch (key) {
            case "SHARP": return Material.IRON_SWORD;
            case "DURA": return Material.SHIELD;
            case "QUICK": return Material.FEATHER;
            case "ACCU": return Material.TARGET;
            case "HEAVY": return Material.ANVIL;
            default: return Material.PAPER;
        }
    }

    // ==========================================
    // ★変更: 5つのステータスボタン(STR/DUR/DEX/ACC/WGT)を更新する。
    // それぞれのボタンをクリックすると、そのステータスを狙って強化を1回試みる。
    // ==========================================
    private void updateStatButtons(Player player, Inventory inv, ItemStack weaponItem) {
        if (weaponItem == null || weaponItem.getType() == Material.AIR) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§c左に武器をセットしてください");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        ItemMeta weaponMeta = weaponItem.getItemMeta();
        if (!weaponMeta.hasLore()) return;

        String weaponId = org.bukkit.ChatColor.stripColor(weaponMeta.getLore().get(0));
        WeaponUpgradeData data = getUpgradeData(weaponId);
        ForgeRecipe recipe = getRecipeForWeapon(weaponId);

        if (data == null || recipe == null) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.BARRIER);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§cこの武器は強化できません");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
        SAOWeapon baseForMigration = plugin.getSaoItemManager().getWeapon(weaponId);
        if (baseForMigration != null) {
            ForgeManager.migrateLegacyWeaponIfNeeded(weaponItem, baseForMigration);
        }

        // ==========================================
        // ★追加: 無料の未割り振りポイント(鍛造の初期強化・旧仕様からの引継ぎ)があれば、
        // 試行回数の上限やCol・素材に関わらず、まずそちらを優先して無料で振れるようにする
        // ==========================================
        int pendingPoints = ForgeManager.getPendingPoints(weaponItem);
        if (pendingPoints > 0) {
            for (int i = 0; i < STAT_SLOTS.length; i++) {
                String key = STAT_SLOT_KEYS[i];
                int currentValue = ForgeManager.getBonusStat(weaponItem, key);

                ItemStack btn = new ItemStack(statIcon(key));
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§d" + key + " に無料で割り振る §7(現在値: +" + currentValue + ")");

                List<String> lore = new ArrayList<>();
                lore.add("§7" + statLabel(key));
                lore.add("");
                lore.add("§d残りの無料割り振りポイント: §b" + pendingPoints + "回");
                lore.add("§8(鍛造の初期強化・旧仕様の武器の引継ぎ分)");
                lore.add("§eクリックでこのステータスに1ポイント無料で割り振る");

                meta.setLore(lore);
                btn.setItemMeta(meta);
                inv.setItem(STAT_SLOTS[i], btn);
            }
            return;
        }

        int attemptsUsed = getUpgradeAttempts(weaponItem);
        if (attemptsUsed >= data.maxUpgrades) {
            for (int slot : STAT_SLOTS) {
                ItemStack btn = new ItemStack(Material.GOLD_BLOCK);
                ItemMeta meta = btn.getItemMeta();
                meta.setDisplayName("§e強化上限に達しています");
                btn.setItemMeta(meta);
                inv.setItem(slot, btn);
            }
            return;
        }

        // ★変更: 「強化値」の代わりに、有料強化の成功回数(旧仕様引継ぎ分含む)だけを
        // 基準に成功率を計算する。生産・無料割り振り分は難易度に一切影響しない。
        // colは強化回数に関わらず、生産コストの1.2倍固定。
        int upgradeDisplayCount = ForgeManager.getUpgradeDisplayCount(weaponItem);
        long cost = (long) Math.ceil(recipe.cost * 1.2);
        int baseRate = Math.max(2, 60 - upgradeDisplayCount * 4);

        java.util.Map<String, Integer> upgradeBase = plugin.getForgeManager().getUpgradeMaterials(recipe);
        int requiredMain = upgradeBase.getOrDefault(recipe.mainMaterialId, 0);
        int remainingUpgrades = data.maxUpgrades - attemptsUsed;

        // ==========================================
        // ★追加: 表示時点でも、既に持っているメイン素材の余剰分を反映した「最終的な成功率」を見せる
        // (実行時と全く同じ計算式。100%を超えないようキャップする)
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
        // ★修正: 実行側(attemptUpgrade)と同じく、成功率の上限を90%に揃える
        int maxBonus = 90 - baseRate;
        int usableExtra = Math.min(extraMain, maxBonus / 2);
        int bonusRate = usableExtra * 2;
        int finalRate = Math.min(90, baseRate + bonusRate);

        for (int i = 0; i < STAT_SLOTS.length; i++) {
            String key = STAT_SLOT_KEYS[i];
            int currentValue = ForgeManager.getBonusStat(weaponItem, key);

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
            lore.add("§b[必要素材]（生成の2/3切り上げ）");
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(INITIAL_ALLOC_TITLE)) {
            handleInitialAllocClick(event);
            return;
        }
        if (!event.getView().getTitle().equals("§b武器強化")) return;

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        // ★ プレイヤーがどこをどうクリックしても、1Tick後に必ずボタンを更新させる
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTitle().equals("§b武器強化")) {
                updateStatButtons(player, event.getInventory(), event.getInventory().getItem(11));
            }
        }, 1L);

        // Shiftクリックによる面倒なバグを防ぐため無効化
        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        // ステータス強化ボタンを押した場合
        for (int i = 0; i < STAT_SLOTS.length; i++) {
            if (slot == STAT_SLOTS[i]) {
                event.setCancelled(true);
                attemptUpgrade(player, event.getInventory(), event.getInventory().getItem(11), STAT_SLOT_KEYS[i]);
                return;
            }
        }

        // 左スロット(11)と、自分のインベントリ(27以降)の操作は許可する
        if (slot == 11 || slot > 26) {
            return;
        }

        // それ以外のメニュースロットは操作キャンセル（ガラスなどを取れないようにする）
        event.setCancelled(true);
    }

    // ==========================================
    // ★変更: 強化の実行処理。選ばれたステータス1つを狙って1回だけ挑戦する。
    // ★変更: 成功時は素材・Col全額消費、失敗時は半額消費+最大耐久値-20のペナルティ。
    // 試行回数は成功・失敗に関わらず必ず消費する。
    // ==========================================
    private void attemptUpgrade(Player player, Inventory inv, ItemStack weaponItem, String statKey) {
        if (weaponItem == null || weaponItem.getType() == Material.AIR) return;
        if (!weaponItem.hasItemMeta() || !weaponItem.getItemMeta().hasLore()) return;

        String weaponId = org.bukkit.ChatColor.stripColor(weaponItem.getItemMeta().getLore().get(0));
        WeaponUpgradeData data = getUpgradeData(weaponId);
        ForgeRecipe recipe = getRecipeForWeapon(weaponId);
        if (data == null || recipe == null) return;

        // ★武器の攻撃属性に対応する鍛造スキルがセットされているか確認
        SAOWeapon upgradeWeapon = plugin.getSaoItemManager().getWeapon(weaponId);
        String forgingSkill = upgradeWeapon != null
                ? ForgeManager.getForgingSkillForType(upgradeWeapon.type)
                : null;

        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
        if (upgradeWeapon != null) {
            ForgeManager.migrateLegacyWeaponIfNeeded(weaponItem, upgradeWeapon);
            // ★追加: STR/DUR/DEX/ACC/WGT等の「1点あたりの上がり幅」を開発側で調整した場合に、
            // 既存アイテムにもその変更を反映させるため、実際の強化操作のたびに現在の値で
            // Attack/Durability/Weight/Bonus Stats表示を再計算する(差分0での再適用)。
            ForgeManager.applyBonusStatDeltas(weaponItem, upgradeWeapon, new int[5]);
        }

        if (forgingSkill == null) {
            player.sendMessage("§b[System] §r§c未対応の攻撃属性のため強化できません。");
            return;
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        boolean hasSkillEquipped = false;
        for (String s : stats.equippedSkills) {
            if (forgingSkill.equals(s)) { hasSkillEquipped = true; break; }
        }
        if (!hasSkillEquipped) {
            player.sendMessage("§b[System] §r§cスキルスロットに「" + forgingSkill + "」をセットしていないため強化できません！");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // ==========================================
        // ★追加: 無料の未割り振りポイント(鍛造の初期強化・旧仕様からの引継ぎ)があれば、
        // Col・素材・試行回数を一切消費せずそちらを優先して消費する
        // ==========================================
        int pendingPoints = ForgeManager.getPendingPoints(weaponItem);
        if (pendingPoints > 0) {
            int[] freeDeltas = new int[5];
            for (int i = 0; i < ForgeManager.STAT_KEYS.length; i++) {
                if (ForgeManager.STAT_KEYS[i].equals(statKey)) freeDeltas[i] = 1;
            }
            ForgeManager.applyBonusStatDeltas(weaponItem, upgradeWeapon, freeDeltas);
            ForgeManager.setPendingPoints(weaponItem, pendingPoints - 1);

            player.sendMessage("§d" + statKey + " §aに無料ポイントを1振りました！ §7(残り" + (pendingPoints - 1) + "回)");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

            updateStatButtons(player, inv, weaponItem);
            return;
        }

        int attemptsUsed = getUpgradeAttempts(weaponItem);
        if (attemptsUsed >= data.maxUpgrades) return;

        // ★変更: 有料強化の成功回数(旧仕様引継ぎ分含む)だけを基準にする。
        // 生産・無料割り振り分は難易度に一切影響しない。colは生産コストの1.2倍固定。
        int upgradeDisplayCount = ForgeManager.getUpgradeDisplayCount(weaponItem);
        long cost = (long) Math.ceil(recipe.cost * 1.2);

        if (stats.col < cost) {
            player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        java.util.Map<String, Integer> upgradeBase = plugin.getForgeManager().getUpgradeMaterials(recipe);

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
        // ★変更: メイン素材の上乗せによる上限を100%→90%に変更
        int maxBonus = 90 - baseRate;
        int usableExtra = Math.min(extraMain, maxBonus / 2);
        int bonusRate = usableExtra * 2;
        int finalRate = Math.min(90, baseRate + bonusRate);

        java.util.Map<String, Integer> consumeMap = new java.util.HashMap<>(subCheck);
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            consumeMap.put(recipe.mainMaterialId, requiredMain + usableExtra);
        }

        boolean success = random.nextInt(100) < finalRate;

        // ★試行回数は成功・失敗に関わらず必ず+1
        incrementUpgradeAttempts(weaponItem);

        // ==========================================
        // ★追加: 強化ミニゲーム(金床を叩く)を挟む。成功/失敗は上ですでに決まっており、
        // 目標回数は「生産時に叩いた回数」を基準に、成功なら+0〜2回、失敗ならそれ未満になる。
        // 叩き終わったら、従来通りの処理(消費・ステ反映・チャット表示)を行う。
        // ==========================================
        int originalHitCount = ForgeManager.getCraftHitCount(weaponItem);
        int hitTarget = sac.plugin.info.SACplugin.Smithing.ForgeMinigameListener
                .computeUpgradeHitTarget(success, originalHitCount);

        player.closeInventory();

        plugin.getForgeMinigameListener().start(player, hitTarget, () -> {
            // ==========================================
            // ★変更: 消費量が成功/失敗で異なる。
            // 成功時: 素材・Colを全額消費。
            // 失敗時: 素材・Colを"半分"だけ失い(端数切り上げ)、さらに武器の最大耐久値が20減少する。
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
                ForgeManager.reduceMaxDurability(weaponItem, 20);
            }
            plugin.getPlayerManager().savePlayerData(player, stats);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            if (success) {
                int[] deltas = new int[5];
                for (int i = 0; i < ForgeManager.STAT_KEYS.length; i++) {
                    if (ForgeManager.STAT_KEYS[i].equals(statKey)) deltas[i] = 1;
                }
                ForgeManager.incrementUpgradeDisplayCount(weaponItem);
                ForgeManager.applyBonusStatDeltas(weaponItem, upgradeWeapon, deltas);

                player.sendMessage("§e=================================");
                player.sendMessage("§b[System] §r§a       !! UPGRADE SUCCESS !!");
                player.sendMessage("§f       " + statKey + " の強化に成功しました！ (+1)");
                if (bonusRate > 0) player.sendMessage("§b[System] §r§7       (メイン余剰" + usableExtra + "個消費で確率+" + bonusRate + "%)");
                player.sendMessage("§e=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

                // 鍛造スキルEXP +8
                int currentProf = stats.proficiency.getOrDefault(forgingSkill, 1);
                int currentExp = stats.proficiencyExp.getOrDefault(forgingSkill, 0);
                int limit = plugin.getLimitManager().getSkillLimit(forgingSkill);
                // ★変更: 強化成功時の経験値を2に変更
                int expGain = (currentProf >= limit) ? 0 : 2;

                if (expGain > 0) {
                    currentExp += expGain;
                    player.sendMessage("§7[" + forgingSkill + "] 経験値獲得: +" + expGain);

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
                            player.sendMessage("§b[" + forgingSkill + "] 熟練度が上がりました！ Lv." + (currentProf - 1) + " -> Lv." + currentProf);
                            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        } else {
                            break;
                        }
                    }

                    stats.proficiency.put(forgingSkill, currentProf);
                    stats.proficiencyExp.put(forgingSkill, currentExp);
                    plugin.getPlayerManager().savePlayerData(player, stats);

                    if (plugin.getSaoScoreboard() != null) {
                        plugin.getSaoScoreboard().updateScoreboard(player, stats);
                    }
                }

            } else {
                player.sendMessage("§8=================================");
                player.sendMessage("§b[System] §r§c       ... UPGRADE FAILED ...");
                player.sendMessage("§7   " + statKey + " の強化に失敗しました。素材とColの§c半分§7が失われ、");
                player.sendMessage("§b[System] §r§7   武器の最大耐久値が§c20§7減少しました");
                if (bonusRate > 0) player.sendMessage("§b[System] §r§7   (メイン余剰" + usableExtra + "個消費で確率+" + bonusRate + "% でも失敗...)");
                player.sendMessage("§8=================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
        });
    }

    // ★追加: アイテムを「なぞって置いた（ドラッグした）」時もボタンを更新させる
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTitle().equals(INITIAL_ALLOC_TITLE)) {
            event.setCancelled(true);
            return;
        }
        if (!event.getView().getTitle().equals("§b武器強化")) return;

        // 11番（左スロット）以外のメニュースロットをなぞろうとしたらキャンセル
        for (int slot : event.getRawSlots()) {
            if (slot < 27 && slot != 11) {
                event.setCancelled(true);
                break;
            }
        }

        // 1Tick後にボタンを更新
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getWhoClicked().getOpenInventory().getTitle().equals("§b武器強化")) {
                updateStatButtons((Player) event.getWhoClicked(), event.getInventory(), event.getInventory().getItem(11));
            }
        }, 1L);
    }

    // メニューを閉じた時に武器を返す
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(INITIAL_ALLOC_TITLE)) {
            Player player = (Player) event.getPlayer();
            ItemStack item = event.getInventory().getItem(11);

            if (item != null && item.getType() != Material.AIR) {
                int pending = ForgeManager.getPendingPoints(item);
                if (pending > 0 && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                    // ★安全策: 割り振り途中で閉じた場合、残りポイントはランダムに割り振る
                    String weaponId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
                    SAOWeapon base = plugin.getSaoItemManager().getWeapon(weaponId);
                    if (base != null) {
                        int[] deltas = new int[5];
                        for (int i = 0; i < pending; i++) deltas[random.nextInt(5)]++;
                        ForgeManager.applyBonusStatDeltas(item, base, deltas);
                    }
                    ForgeManager.setPendingPoints(item, 0);
                    player.sendMessage("§b[System] §r§7(残りの初期強化ポイントはランダムに割り振られました)");
                }

                if (!player.getInventory().addItem(item).isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), item);
                }
            }
            return;
        }

        if (!title.equals("§b武器強化")) return;
        Player player = (Player) event.getPlayer();
        ItemStack weaponItem = event.getInventory().getItem(11);

        if (weaponItem != null && weaponItem.getType() != Material.AIR) {
            if (!player.getInventory().addItem(weaponItem).isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), weaponItem);
            }
        }
    }
}