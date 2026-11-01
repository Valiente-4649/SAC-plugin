package sac.plugin.info.SACplugin.Smithing;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ForgeListener implements Listener {
    private final SACplugin plugin;

    public ForgeListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // メニューを開くメソッド (コマンドなどで呼び出す)
    public void openForgeMainMenu(Player player) {
        // ★変更: 鍛造スキルが「斬撃武器鍛造」「刺突武器鍛造」等に分かれたため、
        // 単一の熟練度をタイトルに出す従来表示はやめ、シンプルなタイトルにする
        String title = "§b鍛造Menu";
        Inventory inv = Bukkit.createInventory(null, 27, title);

        // 1. 武器生成 (メイス)
        ItemStack create = new ItemStack(Material.IRON_SWORD); // メイスがないバージョンならIRON_SHOVEL等で代用
        ItemMeta cm = create.getItemMeta();
        cm.setDisplayName("§a武器生成");
        cm.setLore(Arrays.asList("§7素材を消費して新しい武器を作ります。"));
        cm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        create.setItemMeta(cm);
        inv.setItem(11, create);

        // 2. 武器強化 (金床)
        ItemStack upgrade = new ItemStack(Material.ANVIL);
        ItemMeta um = upgrade.getItemMeta();
        um.setDisplayName("§6武器強化");
        // ★修正: 「未実装」の文字を消し、説明文を変更しました
        um.setLore(Arrays.asList("§7武器と素材を消費して性能を強化します。"));
        upgrade.setItemMeta(um);
        inv.setItem(13, upgrade);

        // 3. 武器修理 (砥石)
        ItemStack repair = new ItemStack(Material.GRINDSTONE);
        ItemMeta rm = repair.getItemMeta();
        rm.setDisplayName("§b武器修理");
        rm.setLore(Arrays.asList("§7耐久値を回復します。"));
        repair.setItemMeta(rm);
        inv.setItem(15, repair);

        // ==========================================
        // ★追加: 4. 防具生成 / 5. 防具強化 (金属防具鍛造)
        // ==========================================
        ItemStack armorCreate = new ItemStack(Material.IRON_CHESTPLATE);
        ItemMeta acm = armorCreate.getItemMeta();
        acm.setDisplayName("§a防具生成");
        acm.setLore(Arrays.asList("§7素材を消費して金属防具を作ります。"));
        acm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        armorCreate.setItemMeta(acm);
        inv.setItem(19, armorCreate);

        ItemStack armorUpgrade = new ItemStack(Material.SHIELD);
        ItemMeta aum = armorUpgrade.getItemMeta();
        aum.setDisplayName("§6防具強化");
        aum.setLore(Arrays.asList("§7防具と素材を消費して性能を強化します。"));
        armorUpgrade.setItemMeta(aum);
        inv.setItem(21, armorUpgrade);

        // ★追加: 6. 防具修理 (金属防具鍛造)
        ItemStack armorRepair = new ItemStack(Material.CAULDRON);
        ItemMeta arm = armorRepair.getItemMeta();
        arm.setDisplayName("§b防具修理");
        arm.setLore(Arrays.asList("§7耐久値を回復します。"));
        armorRepair.setItemMeta(arm);
        inv.setItem(23, armorRepair);

        // 装飾 (鉄格子)
        ItemStack glass = new ItemStack(Material.IRON_BARS);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int i : new int[]{0,9,18, 8,17,26}) inv.setItem(i, glass);

        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    // 生成レシピ一覧を開く
    public void openCraftingMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b武器生成リスト");

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        for (ForgeRecipe recipe : plugin.getForgeManager().getRecipes()) {

            // ★追加: craftable=falseの武器は生産リストに出さない(強化専用にする)
            if (!recipe.craftable) continue;

            SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(recipe.resultWeaponId);
            if (weapon == null) continue;

            // ★変更: 武器の攻撃属性(斬撃/刺突/打撃)に応じた鍛造スキルの熟練度で判定する
            String forgingSkill = sac.plugin.info.SACplugin.Smithing.ForgeManager.getForgingSkillForType(weapon.type);
            int prof = (forgingSkill != null) ? stats.proficiency.getOrDefault(forgingSkill, 1) : 1;

            if (recipe.requiredProficiency > prof + 1) {
                continue;
            }

            ItemStack icon = plugin.getSaoItemManager().createWeaponItem(weapon);
            ItemMeta meta = icon.getItemMeta();

            // レシピ情報をLoreに追加
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add(" ");
            // ★追加: どの鍛造スキルが必要かを明示する
            lore.add("§d[必要スキル] §f" + (forgingSkill != null ? forgingSkill : "(未対応の攻撃属性)"));
            lore.add(" ");
            lore.add("§e[必要Col]");
            lore.add("§7- " + recipe.cost + " Col");
            lore.add(" ");

            lore.add("§b[必要素材]");
            // ★修正: メイン素材を先頭に、サブ素材をその後に表示
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
            // 後方互換: 旧形式のmaterialsフィールド
            if ((recipe.mainMaterialId == null || recipe.mainMaterialId.isEmpty()) && recipe.materials != null) {
                recipe.materials.forEach((matId, amount) -> {
                    sac.plugin.info.SACplugin.item.SAOGeneralItemData data = plugin.getSaoItemManager().getGeneralItem(matId);
                    String name = (data != null && data.name != null) ? data.name : matId;
                    lore.add("§7- " + name + " x" + amount);
                });
            }
            lore.add(" ");
            lore.add("§d[生産結果]");
            lore.add("§7生産成功 §f70% §8(初期強化+1 / EXP+10)");
            lore.add("§6大成功 §f25% §8(初期強化+3 / EXP+15)");
            lore.add("§b奇跡的成功 §f5% §8(初期強化+5 / EXP+20)");
            lore.add("§8(STR/DUR/DEX/ACC/WGTのいずれかがランダムに付与されます)");
            lore.add(" ");

            if (prof >= recipe.requiredProficiency) {
                lore.add("§a[熟練度達成] §7(必要熟練度: " + recipe.requiredProficiency + ")");
            } else {
                lore.add("§c[熟練度不足] §7(必要: " + recipe.requiredProficiency + ")");
            }

            // 隠しデータとしてレシピIDをLoreに入れておく（簡易実装）
            lore.add("§0id:" + recipe.recipeId);

            lore.add(" "); // 見やすくするための空行

            // ==========================================
            // ★変更: 経験値獲得可否の表示。段階的な%減少は廃止し、
            // 必要熟練度より30以上高い場合のみ「獲得不可」にする単純な判定にする。
            // ==========================================
            int diff = prof - recipe.requiredProficiency;
            boolean canGainExp = diff < 30;

            if (canGainExp) {
                lore.add("§a[経験値獲得可能]");
            } else {
                lore.add("§c[経験値獲得不可] §7(必要熟練度より30以上高いため)");
            }

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.startsWith("§b鍛造Menu") && !title.equals("§b武器生成リスト")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;

        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack current = event.getCurrentItem();

        if (current == null || !current.hasItemMeta()) return;

        // メインメニュー判定
        // ★変更: ここも startsWith で判定する
        if (title.startsWith("§b鍛造Menu")) {
            if (current.getType() == Material.IRON_SWORD) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openCraftingMenu(player);
            }
            // 金床をクリックしたら武器強化メニューを開くように連携
            else if (current.getType() == Material.ANVIL) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                new UpgradeMenuListener(plugin).openUpgradeMenu(player);
            }
            else if (current.getType() == Material.GRINDSTONE) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                new RepairMenuListener(plugin).openRepairMenu(player);
            }
            // ★追加: 防具生成・防具強化への連携
            else if (current.getType() == Material.IRON_CHESTPLATE) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                new ArmorForgeListener(plugin).openArmorCraftingMenu(player);
            }
            else if (current.getType() == Material.SHIELD) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                new ArmorForgeListener(plugin).openArmorUpgradeMenu(player);
            }
            else if (current.getType() == Material.CAULDRON) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                new ArmorForgeListener(plugin).openArmorRepairMenu(player);
            }
            return;
        }

        // 生成メニュー判定
        if (event.getView().getTitle().equals("§b武器生成リスト")) {
            ItemMeta meta = current.getItemMeta();
            if (!meta.hasLore()) return;

            // LoreからレシピIDを探す
            String recipeId = null;
            for (String line : meta.getLore()) {
                if (line.startsWith("§0id:")) {
                    recipeId = line.replace("§0id:", "");
                    break;
                }
            }

            if (recipeId != null) {
                ForgeRecipe recipe = plugin.getForgeManager().getRecipe(recipeId);
                if (recipe != null) {
                    // ★追加: craftable=falseなら生産不可(強化専用武器)
                    if (!recipe.craftable) {
                        player.sendMessage("§b[System] §r§cこの武器は生産できません(強化専用です)。");
                        return;
                    }

                    SAOWeapon resultWeapon = plugin.getSaoItemManager().getWeapon(recipe.resultWeaponId);
                    String forgingSkill = resultWeapon != null
                            ? sac.plugin.info.SACplugin.Smithing.ForgeManager.getForgingSkillForType(resultWeapon.type)
                            : null;

                    if (forgingSkill == null) {
                        player.sendMessage("§b[System] §r§c未対応の攻撃属性のため生成できません。");
                        return;
                    }

                    PlayerData stats = plugin.getPlayerManager().getStats(player);

                    // ★追加: 対応する鍛造スキルがスロットにセットされているか確認
                    boolean hasSkillEquipped = false;
                    for (String s : stats.equippedSkills) {
                        if (forgingSkill.equals(s)) { hasSkillEquipped = true; break; }
                    }
                    if (!hasSkillEquipped) {
                        player.sendMessage("§b[System] §r§cスキルスロットに「" + forgingSkill + "」をセットしていないため生成できません！");
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        return;
                    }

                    // 1. 熟練度チェック
                    int prof = stats.proficiency.getOrDefault(forgingSkill, 1);

                    if (prof < recipe.requiredProficiency) {
                        player.sendMessage("§b[System] §r§c熟練度が足りません！");
                        return;
                    }

                    // 2.Colチェック
                    if (stats.col < recipe.cost) {
                        player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        return;
                    }

                    // 3. 素材チェック
                    if (plugin.getForgeManager().hasMaterials(player, plugin.getForgeManager().getAllMaterials(recipe))) {
                        // 消費 & 生成
                        stats.col -= recipe.cost; // Colを引く
                        plugin.getPlayerManager().savePlayerData(player, stats);

                        // スコアボードのCol表示を即座に更新
                        if (plugin.getSaoScoreboard() != null) {
                            plugin.getSaoScoreboard().updateScoreboard(player, stats);
                        }

                        plugin.getForgeManager().consumeMaterials(player, plugin.getForgeManager().getAllMaterials(recipe));

                        // ==========================================
                        // ★変更: 生産の成功段階(生産成功/大成功/奇跡的成功)を先に抽選し、
                        // その結果に応じた回数だけ鍛造ミニゲーム(金床を叩く)を挟んでから、
                        // 従来通りアイテムを生成する。ステータス自体はミニゲームの影響を受けない。
                        // ==========================================
                        sac.plugin.info.SACplugin.Smithing.ForgeManager.CraftRoll craftRoll =
                                plugin.getForgeManager().rollCraftTier();
                        int hitTarget = sac.plugin.info.SACplugin.Smithing.ForgeMinigameListener
                                .computeCraftHitTarget(craftRoll.tierName, recipe.baseHitCount);

                        player.closeInventory();

                        plugin.getForgeMinigameListener().start(player, hitTarget, () -> {
                            ItemStack craftedItem = plugin.getForgeManager().craftWeapon(player, recipe, craftRoll);
                            if (craftedItem != null) {
                                sac.plugin.info.SACplugin.Smithing.ForgeManager.setCraftHitCount(craftedItem, hitTarget);
                            }

                            if ("鍛造".equals(sac.plugin.info.SACplugin.Tutorial.TutorialManager.activeTutorial.get(player.getUniqueId()))) {
                                // ★ スモールソードを作った時だけクリアになる正しい判定！
                                if ("small_sword".equals(recipe.resultWeaponId)) {
                                    sac.plugin.info.SACplugin.Tutorial.TutorialManager.tutorialProgress.put(player.getUniqueId(), 2);

                                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        player.sendMessage("§a[System] 見事スモールソードを作り上げましたね！");
                                        player.sendMessage("§e[System] これで鍛冶の基本はバッチリです。Tutorial NPCのところへ戻って報告しましょう！");
                                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);

                                        sac.plugin.info.SACplugin.Tutorial.TutorialManager.startGuidance(player, sac.plugin.info.SACplugin.Tutorial.TutorialManager.TUTORIAL_NPC_LOC, plugin);
                                    }, 10L);
                                }
                            }

                            // ★変更: 生成直後にその場でSTR/DUR/DEX/ACC/WGTを選ばせる
                            if (craftedItem != null) {
                                ItemStack finalCraftedItem = craftedItem;
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                        plugin.getUpgradeMenuListener().openInitialAllocationMenu(player, finalCraftedItem));
                            }
                        });
                    } else {
                        player.sendMessage("§b[System] §r§cINSUFFICIENT MATERIALS");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof org.bukkit.entity.Interaction) {
            if (event.getRightClicked().getScoreboardTags().contains("sao_forge_station")) {
                event.setCancelled(true);

                // ==========================================
                // ★変更: 「鍛冶」職業スキルは廃止し、5つの鍛造スキルのいずれかが
                // セットされていれば鍛造台を使用可能にする(個別の生産/強化/修理は
                // それぞれ対応する鍛造スキルのセットが別途必要)
                // ==========================================
                Player player = event.getPlayer();
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                boolean hasSkill = false;
                if (stats != null) {
                    java.util.Set<String> forgingSkills = java.util.Set.of(
                            "斬撃武器鍛造", "刺突武器鍛造", "打撃武器鍛造", "金属防具鍛造", "金属工芸"
                    );
                    for (String s : stats.equippedSkills) {
                        if (forgingSkills.contains(s)) { hasSkill = true; break; }
                    }
                }
                if (!hasSkill) {
                    player.sendMessage("§b[System] §r§cスキルスロットに鍛造系スキル(斬撃武器鍛造・刺突武器鍛造など)をセットしていないため、鍛冶台を使用できません！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }

                openForgeMainMenu(player);
            }
        }
    }
}