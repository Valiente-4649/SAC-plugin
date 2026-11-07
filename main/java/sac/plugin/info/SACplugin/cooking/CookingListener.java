package sac.plugin.info.SACplugin.cooking;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;

import java.util.*;

public class CookingListener implements Listener {
    private final SACplugin plugin;
    private final Map<UUID, Long> activeCookingSessions = new HashMap<>();
    private final Map<UUID, CookingRecipe> activeRecipes = new HashMap<>();
    // ★追加: プレイヤーが現在何食分を同時に調理しているかを記録するマップ
    private final Map<UUID, Integer> activeBatches = new HashMap<>();

    // ★追加: 食材を入れられる6つのスロット番号 (左側3x2エリア)
    private final int[] INGREDIENT_SLOTS = {9, 10, 11, 18, 19, 20};

    public CookingListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    private boolean isIngredientSlot(int slot) {
        for (int s : INGREDIENT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    // スロット内にある全食材を集計してMapにするメソッド
    private Map<String, Integer> getInputMaterials(Inventory inv) {
        Map<String, Integer> inputs = new HashMap<>();
        for (int slot : INGREDIENT_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                String id = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
                inputs.put(id, inputs.getOrDefault(id, 0) + item.getAmount());
            }
        }
        return inputs;
    }

    // 1. 調理方法を選ぶメインメニュー
    public void openCookingMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b調理 - 方法選択");

        String[] methods = {"焼く", "煮込む", "茹でる", "揚げる"};
        Material[] icons = {Material.CAMPFIRE, Material.CAULDRON, Material.WATER_BUCKET, Material.LAVA_BUCKET};
        int[] slots = {10, 12, 14, 16};

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        for (int i = 0; i < 4; i++) {
            String method = methods[i];
            ItemStack item = new ItemStack(icons[i]);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§6" + method);

            String profKey = "Cooking_" + method;
            int prof = stats != null ? stats.proficiency.getOrDefault(profKey, 1) : 1;
            int currentExp = stats != null ? stats.proficiencyExp.getOrDefault(profKey, 0) : 0;
            int requiredExp = prof * 15;

            List<String> lore = new ArrayList<>();
            lore.add("§7クリックして食材を選びます");
            lore.add(" ");
            lore.add("§e[現在の熟練度]");
            lore.add("§aLv." + prof + " §7(Exp: " + currentExp + " / " + requiredExp + ")");

            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(slots[i], item);
        }

        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    // 2. 食材を入れるメニュー (6スロット対応)
    public void openIngredientMenu(Player player, String method) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b調理 - [" + method + "]");

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);

        for (int i = 0; i < 27; i++) {
            // 食材スロット(6つ)とボタン(15)以外はガラスで埋める
            if (!isIngredientSlot(i) && i != 15) {
                inv.setItem(i, glass);
            }
        }

        updateRightButton(player, inv, method);
        player.openInventory(inv);
    }

    // 3. 右のボタンの更新
    private void updateRightButton(Player player, Inventory inv, String method) {
        Map<String, Integer> inputs = getInputMaterials(inv);

        if (inputs.isEmpty()) {
            ItemStack btn = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c左の枠に食材をセットしてください");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        CookingRecipe recipe = plugin.getCookingManager().getRecipeByIngredients(inputs, method);

        if (recipe == null) {
            ItemStack btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§cこの組み合わせで作れる料理はありません");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        // 個数が足りているかチェック
        boolean hasEnough = true;
        for (Map.Entry<String, CookingRecipe.MaterialEntry> req : recipe.materials.entrySet()) {
            if (inputs.getOrDefault(req.getKey(), 0) < req.getValue().amount) {
                hasEnough = false;
                break;
            }
        }

        if (!hasEnough) {
            ItemStack btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c素材の個数が足りません！");

            List<String> reqLore = new ArrayList<>();
            reqLore.add("§e[必要な素材]");
            for (Map.Entry<String, CookingRecipe.MaterialEntry> req : recipe.materials.entrySet()) {
                // ★変更: 食材データがレシピに直接埋め込まれているので、まずそれを使う。
                // 魚食材(埋め込みデータが無い)の場合だけ、これまで通りfishes.jsonから名前を探す。
                String name = req.getKey();
                if (req.getValue().name != null) {
                    name = req.getValue().name;
                } else {
                    sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(req.getKey());
                    if (fishData != null) name = fishData.name;
                }

                reqLore.add("§7- " + name + " x" + req.getValue().amount);
            }
            meta.setLore(reqLore);
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        String profKey = "Cooking_" + method;
        int prof = stats != null ? stats.proficiency.getOrDefault(profKey, 1) : 1;

        if (prof < recipe.requiredProficiency) {
            ItemStack btn = new ItemStack(Material.BARRIER);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c料理の熟練度が足りません！ (必要Lv: " + recipe.requiredProficiency + ")");
            btn.setItemMeta(meta);
            inv.setItem(15, btn);
            return;
        }

        String successName = recipe.successItem != null ? recipe.successItem.name : "???";

        ItemStack btn = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = btn.getItemMeta();
        meta.setDisplayName("§a調理開始");

        List<String> lore = new ArrayList<>();
        lore.add("§7完成予定: §b" + successName);
        lore.add("§7難易度: §e" + recipe.difficulty);
        lore.add("§7目標調理時間: §f" + recipe.targetTimeSeconds + " 秒");

        int tolerance = recipe.baseToleranceTicks + (prof * 2);
        double secTolerance = tolerance / 20.0;
        lore.add("§7許容誤差: §a±" + String.format("%.2f", secTolerance) + " 秒");
        lore.add("");
        lore.add("§eクリックすると見えないタイマーが動きます！");
        lore.add("§e目標時間ピッタリにもう一度押してください！");

        meta.setLore(lore);
        btn.setItemMeta(meta);
        inv.setItem(15, btn);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§b調理")) return;

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        // メインメニューの場合
        if (title.equals("§b調理 - 方法選択")) {
            event.setCancelled(true); // どこをクリックしてもアイテムは動かせない

            // ★追加: クリックした場所が上のメニューじゃない（自分のインベントリ等）なら無視して終わる！
            if (event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }

            ItemStack current = event.getCurrentItem();
            if (current != null && current.hasItemMeta()) {
                String method = org.bukkit.ChatColor.stripColor(current.getItemMeta().getDisplayName());
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openIngredientMenu(player, method);
            }
            return;
        }

        String method = title.replace("§b調理 - [", "").replace("]", "");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTitle().startsWith("§b調理 - [")) {
                if (!activeCookingSessions.containsKey(player.getUniqueId())) {
                    updateRightButton(player, event.getInventory(), method);
                }
            }
        }, 1L);

        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (slot == 15) {
            event.setCancelled(true);

            // ② 調理終了判定
            if (activeCookingSessions.containsKey(player.getUniqueId())) {
                CookingRecipe recipe = activeRecipes.get(player.getUniqueId());
                if (recipe == null) return;

                int batchSize = activeBatches.getOrDefault(player.getUniqueId(), 1); // ★追加

                long startTime = activeCookingSessions.get(player.getUniqueId());
                long elapsedMillis = System.currentTimeMillis() - startTime;
                long targetMillis = recipe.targetTimeSeconds * 1000L;
                long diffMillis = Math.abs(targetMillis - elapsedMillis);

                PlayerData stats = plugin.getPlayerManager().getStats(player);
                String profKey = "Cooking_" + method;
                int prof = stats != null ? stats.proficiency.getOrDefault(profKey, 1) : 1;

                long toleranceMillis = (recipe.baseToleranceTicks + (prof * 2)) * 50L;

                player.sendMessage("§7[System] 経過時間: " + String.format("%.2f", elapsedMillis / 1000.0) + "秒 (ズレ: " + String.format("%.2f", diffMillis / 1000.0) + " 秒)");

                if (diffMillis <= toleranceMillis) {
                    player.sendMessage("§b[System] §r§a見事な腕前だ！料理は大成功！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

                    // ==========================================
                    // ★ 修正: チュートリアル「料理」の進行判定
                    // ==========================================
                    if ("料理".equals(sac.plugin.info.SACplugin.Tutorial.TutorialManager.activeTutorial.get(player.getUniqueId()))) {

                        // ★追加: 「ボアのステーキ (boar_steak)」が完成した時のみクリアとする！
                        // ※もし実際のボアステーキのIDが違う場合は "boar_steak" の部分を書き換えてください。
                        if (recipe.successItem != null && "boar_steak".equals(recipe.successItem.id)) {

                            sac.plugin.info.SACplugin.Tutorial.TutorialManager.tutorialProgress.put(player.getUniqueId(), 2);

                            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                player.sendMessage("§a[System] ボアのステーキの調理に成功しましたね！");
                                player.sendMessage("§e[System] Tutorial NPCのところへ戻って報告しましょう！");
                                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

                                sac.plugin.info.SACplugin.Tutorial.TutorialManager.startGuidance(player, sac.plugin.info.SACplugin.Tutorial.TutorialManager.TUTORIAL_NPC_LOC, plugin);
                            }, 20L);
                        }
                    }

                    // ★追加(実績): 調理成功を記録する
                    if (plugin.getAchievementManager() != null) plugin.getAchievementManager().onCookSuccess(player, recipe.recipeId);

                    // ★変更: バッチサイズの数だけ成功アイテムを渡す
                    for (int i = 0; i < batchSize; i++) {
                        giveItem(player, recipe.successItem.id);
                    }

                    // 熟練度計算
                    if (stats != null) {
                        int limit = plugin.getLimitManager().getSkillLimit(profKey); // ★上限の取得だけ追加

                        // ★追加: 上限未満のときだけ経験値処理を行う
                        if (prof < limit) {
                            int currentExp = stats.proficiencyExp.getOrDefault(profKey, 0);

                            int earnedExp = recipe.exp;
                            if (batchSize == 10) earnedExp = (int) (recipe.exp * 10 * 0.8);
                            currentExp += earnedExp;

                            int requiredExp = prof * 15;
                            while (currentExp >= requiredExp) {
                                if (prof >= limit) { // ★ループ内での上限到達チェック
                                    currentExp = 0;
                                    break;
                                }
                                currentExp -= requiredExp;
                                prof++;
                                player.sendMessage("§b[SKILL UP] " + method + "の熟練度が Lv." + prof + " に上がりました！");
                                requiredExp = prof * 15;
                            }
                            stats.proficiency.put(profKey, prof);
                            stats.proficiencyExp.put(profKey, currentExp);
                            plugin.getPlayerManager().savePlayerData(player, stats);
                        }
                    }
                } else {
                    if (elapsedMillis < targetMillis) {
                        player.sendMessage("§b[System] §r§cまだ火が通っていない...！タイミングが早すぎたようだ...");
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        for (int i = 0; i < batchSize; i++) {
                            giveItem(player, recipe.failureEarlyItem.id); // 生焼けアイテムを渡す
                        }
                    } else {
                        player.sendMessage("§b[System] §r§c焦げてしまった...！タイミングが遅すぎたようだ...");
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        for (int i = 0; i < batchSize; i++) {
                            giveItem(player, recipe.failureLateItem.id); // 焦げたアイテムを渡す
                        }
                    }
                }

                activeCookingSessions.remove(player.getUniqueId());
                activeRecipes.remove(player.getUniqueId());
                activeBatches.remove(player.getUniqueId()); // ★追加
                updateRightButton(player, event.getInventory(), method);
                return;
            }

            org.bukkit.inventory.ItemStack clickedBtn = event.getCurrentItem();
            if (clickedBtn == null || clickedBtn.getType() != org.bukkit.Material.LIME_STAINED_GLASS_PANE) {
                // 緑のガラス(調理開始ボタン)以外だった場合は、エラー音を鳴らして弾く
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            // ① 調理開始
            Map<String, Integer> inputs = getInputMaterials(event.getInventory());
            CookingRecipe recipe = plugin.getCookingManager().getRecipeByIngredients(inputs, method);
            if (recipe == null) return;

            // ==========================================
            // ★追加: 作成可能数(バッチサイズ)の計算と熟練度判定
            // ==========================================
            int maxCraftable = 999;
            for (Map.Entry<String, CookingRecipe.MaterialEntry> req : recipe.materials.entrySet()) {
                int possible = inputs.getOrDefault(req.getKey(), 0) / req.getValue().amount;
                if (possible < maxCraftable) {
                    maxCraftable = possible;
                }
            }

            int batchSize = 1; // 基本は1食分
            PlayerData statsForBatch = plugin.getPlayerManager().getStats(player);
            int profForBatch = statsForBatch != null ? statsForBatch.proficiency.getOrDefault("Cooking_" + method, 1) : 1;

            // 10食分以上の素材があり、かつ熟練度が「必要レベル+5」以上ある場合
            if (maxCraftable >= 10 && profForBatch >= (recipe.requiredProficiency + 3)) {
                batchSize = 10;
                player.sendMessage("§b[System] §r§a熟練の技 1度で10食分の調理を行います");
            }

            // 要求数だけ素材を消費する
            for (Map.Entry<String, CookingRecipe.MaterialEntry> req : recipe.materials.entrySet()) {
                int amountToRemove = req.getValue().amount * batchSize; // ★バッチサイズを掛ける
                for (int s : INGREDIENT_SLOTS) {
                    ItemStack item = event.getInventory().getItem(s);
                    if (item != null && item.getType() != Material.AIR && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                        String id = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
                        if (id.equals(req.getKey())) {
                            if (item.getAmount() <= amountToRemove) {
                                amountToRemove -= item.getAmount();
                                event.getInventory().setItem(s, null);
                            } else {
                                item.setAmount(item.getAmount() - amountToRemove);
                                amountToRemove = 0;
                            }
                        }
                    }
                    if (amountToRemove <= 0) break;
                }
            }

            activeBatches.put(player.getUniqueId(), batchSize); // ★追加: 食数を記録
            activeCookingSessions.put(player.getUniqueId(), System.currentTimeMillis());
            activeRecipes.put(player.getUniqueId(), recipe);

            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

            ItemStack btn = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta meta = btn.getItemMeta();
            meta.setDisplayName("§c調理終了！");
            meta.setLore(Arrays.asList("§e目標時間: " + recipe.targetTimeSeconds + "秒", "§7ピッタリだと思った瞬間に押せ！"));
            btn.setItemMeta(meta);
            event.getInventory().setItem(15, btn);
            return;
        }

        if (activeCookingSessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (isIngredientSlot(slot) || slot > 26) return;
        event.setCancelled(true);
    }

    private void giveItem(Player player, String itemId) {
        SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(itemId);
        if (itemData != null) {
            ItemStack item = plugin.getSaoItemManager().createGeneralItem(itemData);
            if (!player.getInventory().addItem(item).isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), item);
            }
        } else {
            // 一般アイテムになければ、魚として付与を試みる
            sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(itemId);
            if (fishData != null) {
                ItemStack item = plugin.getFishingManager().createFishItem(fishData);
                if (!player.getInventory().addItem(item).isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), item);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§b調理 - [")) return;

        if (activeCookingSessions.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot < 27 && !isIngredientSlot(slot)) {
                event.setCancelled(true);
                break;
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getWhoClicked().getOpenInventory().getTitle().startsWith("§b調理 - [")) {
                Player p = (Player) event.getWhoClicked();
                String method = title.replace("§b調理 - [", "").replace("]", "");
                updateRightButton(p, event.getInventory(), method);
            }
        }, 1L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().startsWith("§b調理 - [")) return;
        Player player = (Player) event.getPlayer();

        if (activeCookingSessions.containsKey(player.getUniqueId())) {
            CookingRecipe recipe = activeRecipes.get(player.getUniqueId());
            int batchSize = activeBatches.getOrDefault(player.getUniqueId(), 1); // ★追加

            if (recipe != null) {
                player.sendMessage("§b[System] §r§c調理を途中で放棄したため、食材はダメになってしまった");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                // ★変更: 途中で閉じた場合は時間が足りていないので Early をバッチサイズ分渡す
                for (int i = 0; i < batchSize; i++) {
                    giveItem(player, recipe.failureEarlyItem.id);
                }
            }
            activeCookingSessions.remove(player.getUniqueId());
            activeRecipes.remove(player.getUniqueId());
            activeBatches.remove(player.getUniqueId()); // ★追加
        }

        // 6スロットに入っているアイテムをすべて返す
        for (int s : INGREDIENT_SLOTS) {
            ItemStack ingredient = event.getInventory().getItem(s);
            if (ingredient != null && ingredient.getType() != Material.AIR) {
                if (!player.getInventory().addItem(ingredient).isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), ingredient);
                }
            }
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof org.bukkit.entity.Interaction) {
            if (event.getRightClicked().getScoreboardTags().contains("sao_cooking_station")) {
                event.setCancelled(true);

                // ==========================================
                // ★追加: 料理スキルをセットしているかチェック！
                // ==========================================
                Player player = event.getPlayer();
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                boolean hasSkill = false;
                if (stats != null) {
                    for (String s : stats.equippedSkills) {
                        if ("料理".equals(s)) { hasSkill = true; break; }
                    }
                }
                if (!hasSkill) {
                    player.sendMessage("§b[System] §r§cスキルスロットに「料理」をセットしていないため、調理台を使用できません");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }

                openCookingMainMenu(player);
            }
        }
    }
}