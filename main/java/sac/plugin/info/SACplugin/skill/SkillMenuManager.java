package sac.plugin.info.SACplugin.skill;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class SkillMenuManager {
    // ==========================================
    // ★追加: レベル制の可変スキルスロット関連ヘルパー。
    // 初期2スロット、レベル10ごとに1追加、level_limits.jsonのmax_skill_slotsが上限。
    // ==========================================
    public static int getAvailableSlotCount(PlayerData stats, SACplugin plugin) {
        int maxSlots = plugin.getLimitManager().getMaxSkillSlots();
        int computed = 2 + (stats.level / 10);
        return Math.min(maxSlots, computed);
    }

    /**
     * そのスロット(0始まり)が解放されるのに必要なレベルを返す。スロット0,1は常に解放。
     * ★修正: getAvailableSlotCount()の実際の計算式(2 + level/10)と一致するように変更。
     * スロット2(3枠目)=Lv10、スロット3(4枠目)=Lv20、スロット4(5枠目)=Lv30 ... という
     * 「Lv10から10レベルごとに1枠解放」の実際の仕様に、表示側がズレていた(以前は+10した値だった)。
     */
    public static int getRequiredLevelForSlot(int slotIndex) {
        if (slotIndex < 2) return 1;
        return (slotIndex - 1) * 10;
    }

    // ==========================================
    // 1. メインメニュー（スロット表示）
    // ==========================================
    public static void openMainMenu(Player player, SACplugin plugin) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Skill Menu 》");
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        int maxSlots = plugin.getLimitManager().getMaxSkillSlots();
        int available = getAvailableSlotCount(stats, plugin);

        for (int i = 0; i < maxSlots; i++) {
            if (i >= available) {
                inv.setItem(10 + i, createItem(Material.GRAY_STAINED_GLASS_PANE, "§8[ スロット " + (i + 1) + " ] §7🔒",
                        Arrays.asList("§7Lv." + getRequiredLevelForSlot(i) + " で解放されます", "§8(現在Lv." + stats.level + ")")));
                continue;
            }
            String skill = stats.equippedSkills[i];
            if (skill == null || skill.isEmpty()) {
                inv.setItem(10 + i, createItem(Material.WHITE_STAINED_GLASS_PANE, "§7[ スロット " + (i + 1) + " ]", Arrays.asList("§8未設定", "", "§e設定モードからスキルをセットできます")));
            } else {
                inv.setItem(10 + i, getSkillIcon(skill, stats, false, plugin));
            }
        }

        // ★追加: 入れ替えモードのボタン
        inv.setItem(25, createItem(Material.HOPPER, "§6スロット入れ替え", Arrays.asList("§7スキルの配置場所を入れ替えます", "§a※熟練度はリセットされません")));
        // スキル設定モードへのボタン
        inv.setItem(26, createItem(Material.COMMAND_BLOCK, "§eスキル設定モード", Arrays.asList("§7クリックで新しいスキルをセットします", "§c※既存スキルを外すと熟練度がリセットされます")));

        player.openInventory(inv);
    }

    // ==========================================
    // 2. スキル設定モード（スロット選択）
    // ==========================================
    public static void openSlotEditMenu(Player player, SACplugin plugin) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Skill Edit - スロット選択 》");
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        int maxSlots = plugin.getLimitManager().getMaxSkillSlots();
        int available = getAvailableSlotCount(stats, plugin);

        for (int i = 0; i < maxSlots; i++) {
            if (i >= available) {
                inv.setItem(10 + i, createItem(Material.GRAY_STAINED_GLASS_PANE, "§8[ スロット " + (i + 1) + " ] §7🔒",
                        Arrays.asList("§7Lv." + getRequiredLevelForSlot(i) + " で解放されます", "§8(現在Lv." + stats.level + ")")));
                continue;
            }
            String skill = stats.equippedSkills[i];
            if (skill == null || skill.isEmpty()) {
                inv.setItem(10 + i, createItem(Material.LIME_STAINED_GLASS_PANE, "§a[ スロット " + (i + 1) + " ]", Arrays.asList("§a空き枠", "", "§eクリックでこの枠にスキルをセットします")));
            } else {
                ItemStack icon = getSkillIcon(skill, stats, true, plugin);
                inv.setItem(10 + i, icon);
            }
        }
        player.openInventory(inv);
    }

    // ==========================================
    // ★新規追加: 3. スロット入れ替えモード
    // ==========================================
    public static void openSlotSwapMenu(Player player, SACplugin plugin, int selectedSlot) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Skill Swap - 入れ替え 》");
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        int maxSlots = plugin.getLimitManager().getMaxSkillSlots();
        int available = getAvailableSlotCount(stats, plugin);

        for (int i = 0; i < maxSlots; i++) {
            if (i >= available) {
                inv.setItem(10 + i, createItem(Material.GRAY_STAINED_GLASS_PANE, "§8[ スロット " + (i + 1) + " ] §7🔒",
                        Arrays.asList("§7Lv." + getRequiredLevelForSlot(i) + " で解放されます", "§8(現在Lv." + stats.level + ")")));
                continue;
            }
            String skill = stats.equippedSkills[i];
            boolean isSelected = (i == selectedSlot); // 現在クリックして選択中か

            if (skill == null || skill.isEmpty()) {
                Material bgMat = isSelected ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
                inv.setItem(10 + i, createItem(bgMat, (isSelected ? "§e" : "§7") + "[ スロット " + (i + 1) + " ]",
                        Arrays.asList("§8空き枠", "", isSelected ? "§e▶ 入れ替え先を選んでください" : "§7クリックで選択")));
            } else {
                ItemStack icon = getSkillIcon(skill, stats, false, plugin);
                ItemMeta meta = icon.getItemMeta();
                // 選択中の場合は名前とLoreを強調する
                if (isSelected) {
                    meta.setDisplayName("§e[選択中] " + meta.getDisplayName());
                }
                List<String> lore = meta.getLore();
                lore.set(lore.size() - 1, isSelected ? "§e▶ 入れ替え先を選んでください" : "§7クリックで選択");
                meta.setLore(lore);
                icon.setItemMeta(meta);
                inv.setItem(10 + i, icon);
            }
        }

        // 戻るボタン (隠しデータとして現在選択中のスロット番号を保持しておく)
        inv.setItem(26, createItem(Material.BARRIER, "§c戻る", Arrays.asList("§7メインメニューに戻ります", "§0selected:" + selectedSlot)));
        player.openInventory(inv);
    }

    // ==========================================
    // 4. スキルカタログ（どのスキルを入れるか）
    // ==========================================
    // ★変更: 武器種の並びをハードコードせず、weapon_types.jsonのslotIndexを使って動的に配置する。
    // これにより、weapon_types.jsonに新しい武器種を追記するだけでこのメニューにも自動的に反映される。
    // slotIndex 0〜(WEAPON_SLOT_RESERVED-1) を武器種専用に予約し、職業スキルはその後ろに続けて配置する。
    private static final int WEAPON_SLOT_RESERVED = 9;

    public static void openSkillCatalogMenu(Player player, PlayerData stats, int slotIndex, SACplugin plugin) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Skill Catalog - スロット " + (slotIndex + 1) + " 》");

        for (sac.plugin.info.SACplugin.item.WeaponTypeData wt : plugin.getWeaponTypeManager().getAllTypes().values()) {
            // ★追加: 解放制限のある武器種(体術など)は、プレイヤーが解放済みでなければメニューに出さない
            if (wt.requiresUnlock) {
                boolean unlocked = stats != null && stats.unlockedWeaponTypes != null
                        && stats.unlockedWeaponTypes.contains(wt.category);
                if (!unlocked) continue;
            }

            int slot = wt.slotIndex;
            if (slot < 0 || slot >= WEAPON_SLOT_RESERVED) {
                plugin.getLogger().warning("[SkillMenu] 武器種「" + wt.category + "」のslotIndex(" + slot + ")が範囲外のため表示をスキップしました。");
                continue;
            }
            inv.setItem(slot, getSkillIcon(wt.category, stats, false, plugin));
        }

        int i = WEAPON_SLOT_RESERVED;
        // ★追加: 疾走スキル(初めから選択可能)
        inv.setItem(i++, getSkillIcon("疾走", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("斬撃武器鍛造", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("刺突武器鍛造", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("打撃武器鍛造", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("金属防具鍛造", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("金属工芸", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("料理", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("加工", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("釣り", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("農業", stats, false, plugin));
        inv.setItem(i++, getSkillIcon("商業", stats, false, plugin));

        player.openInventory(inv);
    }

    // ==========================================
    // ★新規追加: 疾走スキル実行メニュー。
    // 戦闘中の暴発を防ぐため、ソードスキルのようにしゃがみ+右クリックで即発動させず、
    // 必ずこのメニューの「実行」ボタンを押すことでselectedSkillIdに"dash"をセットする。
    // ==========================================
    public static void openDashExecuteMenu(Player player, SACplugin plugin) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 疾走スキル 》");
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        boolean isActive = stats != null && "dash".equals(stats.selectedSkillId);

        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7移動速度がマイクラのデフォルトの§b2倍§7になります。");
        infoLore.add("§7発動中は他のソードスキルを実行できません。");
        infoLore.add("");
        infoLore.add(isActive ? "§a現在発動中です" : "§8現在発動していません");
        inv.setItem(13, createItem(Material.FEATHER, "§b疾走スキル", infoLore));

        if (isActive) {
            inv.setItem(22, createItem(Material.RED_STAINED_GLASS_PANE, "§c[ 解除する ]",
                    Arrays.asList("§7疾走を解除します")));
        } else {
            inv.setItem(22, createItem(Material.LIME_STAINED_GLASS_PANE, "§a[ 実行する ]",
                    Arrays.asList("§7疾走をソードスキルの代わりにセットします")));
        }

        player.openInventory(inv);
    }

    // ==========================================
    // 5. 入れ替えの「二重確認」画面
    // ==========================================
    public static void openConfirmReplaceMenu(Player player, int slotIndex, String oldSkill, String newSkill) {
        Inventory inv = Bukkit.createInventory(null, 9, "§c《 スキル上書き確認 》");

        ItemStack info = createItem(Material.PAPER, "§c本当にスキルを上書きしますか？", Arrays.asList(
                "§7現在のスキル: §c" + oldSkill,
                "§7新しいスキル: §a" + newSkill,
                "",
                "§4※警告: §c" + oldSkill + " §4をスロットから外すと、",
                "§4そのスキルの熟練度が【初期化(Lv.1)】されます！",
                "",
                "§0slot:" + slotIndex, "§0old:" + oldSkill, "§0new:" + newSkill
        ));
        inv.setItem(4, info);

        inv.setItem(2, createItem(Material.LIME_STAINED_GLASS_PANE, "§a[ はい ] 上書きする", Arrays.asList("§c熟練度をリセットして§a新しいスキルをセットします")));
        inv.setItem(6, createItem(Material.RED_STAINED_GLASS_PANE, "§c[ いいえ ] やめる", Arrays.asList("§7元の画面に戻ります")));

        player.openInventory(inv);
    }

    // ==========================================
    // 既存のソードスキル一覧メニュー
    // ==========================================
    public static void openSubMenu(Player player, String category, SACplugin plugin) {
        Inventory inv = Bukkit.createInventory(null, 27, "スキル選択: " + category);
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        int currentProf = (stats != null) ? stats.proficiency.getOrDefault(category, 1) : 1;

        plugin.getSwordSkillManager().getAllSkills().values().stream()
                .filter(s -> s.category.equals(category))
                .sorted(java.util.Comparator.comparingInt(s -> s.order))
                .forEach(skill -> {
                    int slot = inv.firstEmpty();
                    if (slot < 0 || slot > 26) return;

                    boolean isUnlocked = currentProf >= skill.requiredProficiency;
                    boolean isSelected = (stats != null && skill.id.equals(stats.selectedSkillId));

                    // ★ 未解放スキルは鉄格子でロック表示
                    if (!isUnlocked) {
                        List<String> lockedLores = new ArrayList<>();
                        lockedLores.add("§c【解放条件】");
                        lockedLores.add("§7必要熟練度: §e" + skill.requiredProficiency);
                        lockedLores.add("§7現在の熟練度: §c" + currentProf);
                        lockedLores.add("");
                        lockedLores.add("§8まだ解放されていません");
                        inv.setItem(slot, createItem(Material.IRON_BARS, "§8🔒 " + skill.name, lockedLores));
                        return;
                    }

                    List<String> lores = new ArrayList<>();
                    lores.add(skill.description != null ? skill.description : "§7説明はありません");
                    lores.add("");
                    lores.add("§f必要熟練度: §e" + skill.requiredProficiency);
                    lores.add("§f現在の熟練度: §b" + currentProf);

                    // ★ 短剣カテゴリのスキルには連撃発生率を追加
                    if ("短剣".equals(category) && stats != null) {
                        // ★変更: 防具のAGIボーナス(金属防具鍛造)も合算する
                        double triggerRate = (stats.agi + stats.armorAgiBonus) * 0.5;
                        lores.add("");
                        lores.add("§e【短剣特性】 高速5連撃");
                        lores.add("§7発動率: §b" + String.format("%.1f", triggerRate) + "% §7(AGI×0.5%)");
                        lores.add("§7発動時: §f最低攻撃力×5連撃");
                    }
                    // ★ 刀カテゴリのスキルには抜刀カウンター説明を追加
                    if ("刀".equals(category)) {
                        lores.add("");
                        lores.add("§6【刀特性】 抜刀カウンター");
                        lores.add("§7チャージ中に被弾 → §c攻撃無効化");
                        lores.add("§7スキル倍率 §c×7 §7で即発動！");
                    }

                    lores.add("");
                    lores.add(isSelected ? "§a現在セット中" : "§eクリックでセット");
                    // ★修正: IDを隠しデータとしてLoreに埋め込む（日本語名スキルでも確実にIDを取得するため）
                    lores.add("§0id:" + skill.id);

                    Material m = isSelected ? Material.END_CRYSTAL : Material.GLASS_PANE;
                    inv.setItem(slot, createItem(m, (isSelected ? "§b" : "§f") + skill.name, lores));
                });
        player.openInventory(inv);
    }

    // --- 補助メソッド群 ---
    public static ItemStack getSkillIcon(String skill, PlayerData stats, boolean isEditMode, sac.plugin.info.SACplugin.SACplugin plugin) {
        ItemStack item = null;
        List<String> lore = new ArrayList<>();

        // ★変更: ハードコードされた武器種名リストの代わりに、weapon_types.jsonに
        // 登録されている武器種かどうかで判定する（武器種追加時に自動対応するため）
        sac.plugin.info.SACplugin.item.WeaponTypeData weaponType =
                plugin != null ? plugin.getWeaponTypeManager().getType(skill) : null;

        if (weaponType != null) {
            // ★ 刀のみ持つ特殊機構(抜刀カウンター)の説明。武器種固有ロジックなのでここは維持する。
            if (skill.equals("刀")) {
                lore.add("");
                lore.add("§6【刀特性】 抜刀カウンター");
                lore.add("§7チャージ中に被弾 → §c攻撃無効化");
                lore.add("§7スキル倍率 §c×7 §7で即発動！");
            }

            if (weaponType.description != null && !weaponType.description.isEmpty()) {
                lore.add("");
                lore.add("§7" + weaponType.description);
            }

            // ==========================================
            // ★変更: ここでも「次のレベルに必要な経験値」を計算して表示に反映する
            // ==========================================
            int currentLevel = stats.proficiency.getOrDefault(skill, 1);
            // ★変更: 武器の熟練度必要経験値を 300 + 10×(レベル-1) に変更
            int requiredExp = 300 + (currentLevel - 1) * 10;

            lore.add("");
            lore.add("§7熟練度: §eLv." + currentLevel);
            lore.add("§7進捗: §f" + stats.usedDurability.getOrDefault(skill, 0) + " / " + requiredExp);

            // ★ 短剣のみ: 連撃発生率を表示
            if (skill.equals("短剣")) {
                // ★変更: 防具のAGIボーナス(金属防具鍛造)も合算する
                double triggerRate = (stats.agi + stats.armorAgiBonus) * 0.5;
                lore.add("");
                lore.add("§e【短剣特性】 高速5連撃");
                lore.add("§7発動率: §b" + String.format("%.1f", triggerRate) + "% §7(AGI×0.5%)");
                lore.add("§7発動時: §f最低攻撃力×5連撃");
            }

            lore.add("");
            lore.add(isEditMode ? "§cクリックでこのスロットを上書きする" : "§eクリックでソードスキル選択");
            if(isEditMode) lore.add("§c※外すと熟練度がリセットされます");

            // ==========================================
            // ★変更: アイコンの見た目は weapon_types.json の iconCustomModelData を使用する
            // （0の場合はCMD無しの素のIRON_SWORDになる）
            // ==========================================
            Material m = Material.IRON_SWORD;
            int iconCmd = weaponType.iconCustomModelData;

            item = createItem(m, "§b" + skill, lore);
            if (iconCmd > 0) {
                ItemMeta iconMeta = item.getItemMeta();
                if (iconMeta != null) {
                    iconMeta.setCustomModelData(iconCmd);
                    item.setItemMeta(iconMeta);
                }
            }

        } else if (skill.equals("疾走")) {
            boolean isActive = stats != null && "dash".equals(stats.selectedSkillId);
            lore.add("§7移動速度がマイクラのデフォルトの§b2倍§7になる。");
            lore.add("§c※戦闘中(ソードスキルの代わり)は使用できません");
            lore.add("");
            lore.add(isActive ? "§a現在発動中" : "§8現在未発動");
            lore.add("");
            lore.add(isEditMode ? "§cクリックでこのスロットを上書きする" : "§eクリックで実行メニューを開く");
            if (isEditMode) lore.add("§c※外しても熟練度には影響しません");
            item = createItem(isActive ? Material.SUGAR : Material.FEATHER, (isActive ? "§b" : "§f") + "疾走", lore);

        } else if (skill.equals("斬撃武器鍛造")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7斬撃属性の武器の生成・強化・修理を可能にする鍛造スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.ANVIL, "§6斬撃武器鍛造", lore);

        } else if (skill.equals("刺突武器鍛造")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7刺突属性の武器の生成・強化・修理を可能にする鍛造スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.ANVIL, "§6刺突武器鍛造", lore);

        } else if (skill.equals("打撃武器鍛造")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7打撃属性の武器の生成・強化・修理を可能にする鍛造スキル");
            lore.add("§8(対応武器は現在準備中です)");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.ANVIL, "§6打撃武器鍛造", lore);

        } else if (skill.equals("金属防具鍛造")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7金属製の防具の生成・強化を可能にする鍛造スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.IRON_CHESTPLATE, "§6金属防具鍛造", lore);

        } else if (skill.equals("金属工芸")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7アクセサリー等の金属工芸品の生成を可能にする鍛造スキル");
            lore.add("§8(システムは現在準備中です)");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.GOLD_NUGGET, "§6金属工芸", lore);

        } else if (skill.equals("料理")) {
            lore.add("§7焼く: §eLv." + stats.proficiency.getOrDefault("Cooking_焼く", 1));
            lore.add("§7煮込む: §eLv." + stats.proficiency.getOrDefault("Cooking_煮込む", 1));
            lore.add("§7茹でる: §eLv." + stats.proficiency.getOrDefault("Cooking_茹でる", 1));
            lore.add("§7揚げる: §eLv." + stats.proficiency.getOrDefault("Cooking_揚げる", 1));
            lore.add("");
            lore.add("§7調理台を使用可能にする職業スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.CAMPFIRE, "§c料理", lore);

        } else if (skill.equals("加工")) {
            int prof = stats.proficiency.getOrDefault(skill, 1);
            int exp = stats.proficiencyExp.getOrDefault(skill, 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp);
            lore.add("");
            lore.add("§7鑑定素材をアクセサリーに加工できるようにする職業スキル");
            lore.add("§8(熟練度が上がるほど、より高い階層のボスドロップも加工可能に)");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.AMETHYST_SHARD, "§d加工", lore);

        } else if (skill.equals("釣り")) { // 釣りのアイコン処理
            int prof = stats.proficiency.getOrDefault("Fishing", 1);
            int exp = stats.proficiencyExp.getOrDefault("Fishing", 0);
            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7進捗: §f" + exp + " / " + (prof * 50));
            lore.add("");
            lore.add("§7釣竿を使用可能にする職業スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.FISHING_ROD, "§b釣り", lore);

        } else if (skill.equals("農業")) {
            lore.add("§7熟練度: §eLv." + stats.proficiency.getOrDefault("Farming", 1));
            lore.add("§7進捗: §f" + stats.proficiencyExp.getOrDefault("Farming", 0));
            lore.add("");
            lore.add("§7ジョウロの使用と種まきを可能にする職業スキル");
            if(isEditMode) { lore.add(""); lore.add("§cクリックでこのスロットを上書きする"); lore.add("§c※外すと熟練度がリセットされます"); }
            item = createItem(Material.WHEAT, "§a農業", lore);
        } else if (skill.equals("商業")) {
            int prof = stats.proficiency.getOrDefault("Commerce", 1);
            int exp = stats.proficiencyExp.getOrDefault("Commerce", 0);
            int required = prof * 5000; // 次のレベルに必要な売上額
            int maxSlots = prof + 2;    // 初期3枠 + レベルアップ分

            lore.add("§7熟練度: §eLv." + prof);
            lore.add("§7累計売上進捗: §f" + exp + " / " + required);
            lore.add("");
            lore.add("§f現在の出品可能枠: §e" + maxSlots + " 枠");
            lore.add("§7自分のショップを開店できるスキル");

            if(isEditMode) {
                lore.add("");
                lore.add("§cクリックでこのスロットを上書きする");
                lore.add("§c※外すと熟練度がリセットされます");
            }
            item = createItem(Material.EMERALD, "§a商業", lore);
        }
        return item != null ? item : new ItemStack(Material.STONE);
    }

    public static void resetSkillProficiency(PlayerData stats, String skill) {
        if (skill.equals("料理")) {
            stats.proficiency.put("Cooking_焼く", 1); stats.proficiency.put("Cooking_煮込む", 1);
            stats.proficiency.put("Cooking_茹でる", 1); stats.proficiency.put("Cooking_揚げる", 1);
            stats.proficiencyExp.put("Cooking_焼く", 0); stats.proficiencyExp.put("Cooking_煮込む", 0);
            stats.proficiencyExp.put("Cooking_茹でる", 0); stats.proficiencyExp.put("Cooking_揚げる", 0);
        } else if (skill.equals("釣り")) {
            stats.proficiency.put("Fishing", 1);
            stats.proficiencyExp.put("Fishing", 0);
        } else if (skill.equals("農業")) {
            stats.proficiency.put("Farming", 1);
            stats.proficiencyExp.put("Farming", 0);
        } else if (skill.equals("商業")) {
            stats.proficiency.put("Commerce", 1);
            stats.proficiencyExp.put("Commerce", 0);
        } else {
            stats.proficiency.put(skill, 1);
            stats.usedDurability.put(skill, 0);
            if (stats.selectedSkillId != null && !stats.selectedSkillId.isEmpty()) {
                stats.selectedSkillId = ""; // デフォルトに戻す
            }
        }
    }

    private static ItemStack createItem(Material m, String name, List<String> lores) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lores);
            item.setItemMeta(meta);
        }
        return item;
    }
}