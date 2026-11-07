package sac.plugin.info.SACplugin.Smithing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOArmor;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 金属防具鍛造の管理クラス。ForgeManager(武器)と同じ考え方・同じ仕組みで、
 * 防具(type="金属")の生産・強化のボーナスステータス(DEF/DUR/WGT/STR/AGI)を管理する。
 */
public class ArmorForgeManager implements Reloadable {
    private final SACplugin plugin;
    private final List<ArmorForgeRecipe> recipes = new ArrayList<>();
    private final Random random = new Random();

    // 金属防具の鍛造スキル名(固定。武器のように攻撃属性で分岐しない)
    public static final String ARMOR_FORGING_SKILL = "金属防具鍛造";
    public static final String[] STAT_KEYS = {"DEF", "DUR", "WGT", "STR", "AGI"};

    // ==========================================
    // ★変更: Bonus Statsは1点あたり固定の実数値にする。
    // DEF: 防御力+3 / DUR: 耐久値+20 / WGT: 重量-1
    // STR/AGI: プレイヤーへは「装備中の防具の合計値3につき+1」で反映する
    // (この変換はPlayerManager側で行うため、防具自体のPDC値は生のポイント数のまま1:1で持つ)
    // ==========================================
    private static final int DEF_PER_POINT = 3;
    private static final int DUR_PER_POINT = 20;
    private static final int WGT_PER_POINT = 1;

    public ArmorForgeManager(SACplugin plugin) {
        this.plugin = plugin;
        loadRecipes();
    }

    public void loadRecipes() {
        File file = new File(plugin.getDataFolder(), "armor_forge.json");
        if (!file.exists()) plugin.saveResource("armor_forge.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<ArmorForgeRecipe>>() {}.getType();
            List<ArmorForgeRecipe> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                recipes.clear();
                recipes.addAll(loaded);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reload() {
        loadRecipes();
    }

    public List<ArmorForgeRecipe> getRecipes() {
        return recipes;
    }

    public ArmorForgeRecipe getRecipe(String recipeId) {
        for (ArmorForgeRecipe r : recipes) {
            if (r.recipeId.equals(recipeId)) return r;
        }
        return null;
    }

    public ArmorForgeRecipe getRecipeForArmor(String armorId) {
        for (ArmorForgeRecipe r : recipes) {
            if (r.resultArmorId.equals(armorId)) return r;
        }
        return null;
    }

    public java.util.Map<String, Integer> getAllMaterials(ArmorForgeRecipe recipe) {
        java.util.Map<String, Integer> all = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            all.put(recipe.mainMaterialId, recipe.mainMaterialAmount);
        }
        if (recipe.subMaterials != null) {
            for (java.util.Map.Entry<String, Integer> e : recipe.subMaterials.entrySet()) {
                all.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return all;
    }

    /** 強化用素材マップを返す(メイン・サブ共に生産の2/3切り上げ。武器と同じ比率) */
    public java.util.Map<String, Integer> getUpgradeMaterials(ArmorForgeRecipe recipe) {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            result.put(recipe.mainMaterialId, (int) Math.ceil(recipe.mainMaterialAmount * 2.0 / 3.0));
        }
        if (recipe.subMaterials != null) {
            for (java.util.Map.Entry<String, Integer> e : recipe.subMaterials.entrySet()) {
                result.merge(e.getKey(), (int) Math.ceil(e.getValue() * 2.0 / 3.0), Integer::sum);
            }
        }
        return result;
    }

    // ==========================================
    // Bonus Stats(DEF/DUR/WGT/STR/AGI)関連。武器のForgeManagerと同じくPDCで保持する。
    // ==========================================
    private static SACplugin getMainInstance() {
        return SACplugin.getPlugin(SACplugin.class);
    }

    private static org.bukkit.NamespacedKey statPdcKey(String key) {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_bonus_" + key.toLowerCase());
    }

    private static org.bukkit.NamespacedKey pendingPointsPdcKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_pending_points");
    }

    private static org.bukkit.NamespacedKey upgradeAttemptsKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_upgrade_attempts");
    }

    private static org.bukkit.NamespacedKey upgradeDisplayCountKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_upgrade_display_count");
    }

    public static int getBonusStat(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(statPdcKey(key), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    public static int getTotalBonusStats(ItemStack item) {
        int total = 0;
        for (String key : STAT_KEYS) total += getBonusStat(item, key);
        return total;
    }

    /**
     * ★追加: 強化失敗時のペナルティとして、防具の最大耐久値をamountだけ減少させる。
     * 現在耐久値が新しい最大値を超えていれば、そちらも合わせて減らす。
     * 最大耐久値は最低1を保証する(0や負数にはしない)。武器のForgeManagerと同じロジック。
     */
    public static void reduceMaxDurability(ItemStack item, int amount) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<String> lore = new ArrayList<>(meta.getLore());

        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Durability:")) {
                try {
                    String[] parts = org.bukkit.ChatColor.stripColor(lore.get(i)).replace("Durability:", "").trim().split("/");
                    int oldCurrent = Integer.parseInt(parts[0].trim());
                    int oldMax = Integer.parseInt(parts[1].trim());
                    int newMax = Math.max(1, oldMax - amount);
                    int newCurrent = Math.min(newMax, oldCurrent);
                    lore.set(i, "§7Durability: §a" + newCurrent + " / " + newMax);
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    public static int getPendingPoints(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(pendingPointsPdcKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    public static void setPendingPoints(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(pendingPointsPdcKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    // ==========================================
    // ★追加: 生産ミニゲームで叩いた回数(演出用)。強化ミニゲームの目標回数を
    // 決める時の「基準」として参照する。ステータス計算には一切使わない。
    // ==========================================
    private static org.bukkit.NamespacedKey craftHitCountKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_craft_hit_count");
    }

    public static int getCraftHitCount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        Integer stored = item.getItemMeta().getPersistentDataContainer()
                .get(craftHitCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : -1;
    }

    public static void setCraftHitCount(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(craftHitCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    public static int getUpgradeAttempts(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    // ★追加: 強化試行回数を直接セットする(/sac giveでの初期状態付与などに使用)
    public static void setUpgradeAttempts(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    public static void incrementUpgradeAttempts(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER, getUpgradeAttempts(item) + 1);
        item.setItemMeta(meta);
    }

    public static int getUpgradeDisplayCount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(upgradeDisplayCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    // ★追加: 「+N」表記用カウンタを直接セットする(/sac giveでの初期状態付与などに使用)
    public static void setUpgradeDisplayCount(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeDisplayCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    /** 有料強化が成功した時にだけ呼び出すこと(生産・無料割り振りでは呼ばない) */
    public static void incrementUpgradeDisplayCount(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeDisplayCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER, getUpgradeDisplayCount(item) + 1);
        item.setItemMeta(meta);
    }

    /**
     * 防具アイテムのBonus Stats(DEF/DUR/WGT/STR/AGI)にdeltasを加算し、
     * Defense/Weight/Durability/Bonus Stats表示をすべて再計算して反映する。
     * deltasはSTAT_KEYSの順(DEF,DUR,WGT,STR,AGI)。
     */
    public static void applyBonusStatDeltas(ItemStack item, SAOArmor base, int[] deltas) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;

        int[] values = new int[5];
        for (int i = 0; i < 5; i++) {
            values[i] = getBonusStat(item, STAT_KEYS[i]) + deltas[i];
            if (values[i] < 0) values[i] = 0;
        }
        for (int i = 0; i < 5; i++) {
            meta.getPersistentDataContainer().set(statPdcKey(STAT_KEYS[i]), org.bukkit.persistence.PersistentDataType.INTEGER, values[i]);
        }
        item.setItemMeta(meta);

        meta = item.getItemMeta();
        List<String> lore = new ArrayList<>(meta.getLore());

        int def = values[0], dur = values[1], wgt = values[2], str = values[3], agi = values[4];

        // ==========================================
        // ★修正: 「1点=基礎値の固定%」だと、今後(100層実装など)強化上限自体が
        // 増えていった時に、全振りした場合の最終到達点が際限なく伸びてしまう。
        // そこで「全振りした時に到達する上限(基礎値の何%か)」を固定し、
        // それを強化上限(upgradeLimit)で割ることで1点あたりの価値を決める。
        // → 強化上限が2でも20でも、全振り時の最終値は常に同じになる。
        // ---- 計算式 ----
        // DEF全振り上限: 基礎Defenseの50%   → 1点 = 50% ÷ upgradeLimit
        // DUR全振り上限: 基礎Durabilityの50% → 1点 = 50% ÷ upgradeLimit
        // WGT全振り上限: 基礎Weightの30%(軽量化) → 1点 = 30% ÷ upgradeLimit
        // STR/AGI全振り上限: 合計15(プレイヤー実効+5相当。3につき1で反映) → 1点 = 15 ÷ upgradeLimit
        // ==========================================
        int defBonus = def * DEF_PER_POINT;
        int durBonus = dur * DUR_PER_POINT;
        int wgtReduction = wgt * WGT_PER_POINT;
        // ★STR/AGIは生のポイント数のまま持つ(プレイヤーへの反映時にPlayerManager側で
        //   「装備中の防具の合計値3につき+1」として変換する)

        int newDefense = base.defense + defBonus;
        int newMaxDur = base.maxDurability + durBonus;
        int newWeight = Math.max(0, base.weight - wgtReduction);

        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Defense:")) {
                lore.set(i, "§7Defense: §b" + newDefense + (defBonus > 0 ? " §a(+" + defBonus + ")" : ""));
                break;
            }
        }
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Weight:")) {
                lore.set(i, "§7Weight: §4" + newWeight + (wgtReduction > 0 ? " §a(-" + wgtReduction + ")" : ""));
                break;
            }
        }
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Durability:")) {
                try {
                    String[] parts = org.bukkit.ChatColor.stripColor(lore.get(i)).replace("Durability:", "").trim().split("/");
                    int oldCurrent = Integer.parseInt(parts[0].trim());
                    int oldMax = Integer.parseInt(parts[1].trim());
                    int newCurrent = Math.min(newMaxDur, Math.max(0, oldCurrent + (newMaxDur - oldMax)));
                    lore.set(i, "§7Durability: §a" + newCurrent + " / " + newMaxDur);
                } catch (Exception ignored) {}
                break;
            }
        }

        int bonusHeaderIdx = lore.indexOf("§6[ Bonus Stats ]");
        if (bonusHeaderIdx != -1) {
            while (lore.size() > bonusHeaderIdx) lore.remove(lore.size() - 1);
        }
        lore.add("§6[ Bonus Stats ]");
        lore.add("§7DEF +" + def + " §8(Defense +" + defBonus + ")");
        lore.add("§7DUR +" + dur + " §8(Durability +" + durBonus + ")");
        lore.add("§7WGT +" + wgt + " §8(重量 -" + wgtReduction + ")");
        lore.add("§7STR +" + str + " §8(装備合計3につきプレイヤーSTR+1)");
        lore.add("§7AGI +" + agi + " §8(装備合計3につきプレイヤーAGI+1)");

        meta.setLore(lore);

        int displayCount = getUpgradeDisplayCount(item);
        String baseName = base.name.replace("《", "").replace("》", "");
        meta.setDisplayName("§b《" + baseName + (displayCount > 0 ? " +" + displayCount : "") + "》");

        item.setItemMeta(meta);
    }

    // ==========================================
    // ★追加: armors.jsonの基礎値(Defense/Durability/Weight/強化上限)を変更した時のための
    // 一度きりの再計算移行。武器のmigrateStatSchemeV3IfNeededと全く同じ考え方。
    // 「このアイテムが最後に計算された時の基礎値」をPDCに記録しておき、armors.json側の
    // 現在の基礎値と食い違っていたら、投資済みポイントの合計を未割り振りポイントとして
    // 引き継ぎ、実際のステータス(DEF/DUR/WGT/STR/AGI)は0にリセットする。
    // 強化試行回数(upgradeAttempts)は、新しい強化上限(upgradeLimit)を超えていれば
    // その場でクランプする(例: steel系防具の上限3→2への変更に対応)。
    // ==========================================
    private static org.bukkit.NamespacedKey refDefKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_ref_def");
    }
    private static org.bukkit.NamespacedKey refDurKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_ref_dur");
    }
    private static org.bukkit.NamespacedKey refWgtKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "armor_ref_wgt");
    }

    /** @return 移行または上限クランプで何かしら変化があったらtrue(呼び出し元でメッセージ表示等に使える) */
    public static boolean migrateArmorSchemeIfNeeded(ItemStack item, SAOArmor base) {
        if (item == null || base == null || !item.hasItemMeta()) return false;
        ItemMeta metaCheck = item.getItemMeta();
        if (!metaCheck.hasLore()) return false;

        boolean changed = false;

        Integer refDef = metaCheck.getPersistentDataContainer().get(refDefKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        Integer refDur = metaCheck.getPersistentDataContainer().get(refDurKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        Integer refWgt = metaCheck.getPersistentDataContainer().get(refWgtKey(), org.bukkit.persistence.PersistentDataType.INTEGER);

        boolean needsMigration = refDef == null || refDur == null || refWgt == null
                || refDef != base.defense || refDur != base.maxDurability || refWgt != base.weight;

        if (needsMigration) {
            // ==========================================
            // ★修正: 「現在値+0delta」では現在値がそのまま維持されてしまい、リセットにならない。
            // 各ステータスについて「現在値のマイナス」をdeltaとして渡すことで、確実に0にする。
            // ==========================================
            int[] currentValues = new int[STAT_KEYS.length];
            int totalInvested = 0;
            for (int i = 0; i < STAT_KEYS.length; i++) {
                currentValues[i] = getBonusStat(item, STAT_KEYS[i]);
                totalInvested += currentValues[i];
            }

            // 既に投資済みのポイント合計(内訳は捨てて合計だけ)を未割り振りポイントに変換する
            if (totalInvested > 0) {
                setPendingPoints(item, getPendingPoints(item) + totalInvested);
                changed = true;
            }

            // ステータスを実際に0にリセットし、Lore/Defense/Weight/Durability表示を新しい基礎値で再計算する
            int[] resetDeltas = new int[STAT_KEYS.length];
            for (int i = 0; i < STAT_KEYS.length; i++) {
                resetDeltas[i] = -currentValues[i];
            }
            applyBonusStatDeltas(item, base, resetDeltas);
        }

        // ★強化試行回数を新しい上限にクランプする(steel系のupgradeLimit 3→2など)
        int attempts = getUpgradeAttempts(item);
        if (base.upgradeLimit > 0 && attempts > base.upgradeLimit) {
            setUpgradeAttempts(item, base.upgradeLimit);
            changed = true;
        }

        // 基準値を更新
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(refDefKey(), org.bukkit.persistence.PersistentDataType.INTEGER, base.defense);
        meta.getPersistentDataContainer().set(refDurKey(), org.bukkit.persistence.PersistentDataType.INTEGER, base.maxDurability);
        meta.getPersistentDataContainer().set(refWgtKey(), org.bukkit.persistence.PersistentDataType.INTEGER, base.weight);
        item.setItemMeta(meta);

        return changed;
    }
    // ==========================================
    // ==========================================
    // ★追加: 生産の成功段階(生産成功/大成功/奇跡的成功)だけを先に抽選する(武器と同じ仕様)。
    // ==========================================
    public static class CraftRoll {
        public final String tierName;
        public final int points;
        public CraftRoll(String tierName, int points) {
            this.tierName = tierName;
            this.points = points;
        }
    }

    public CraftRoll rollCraftTier() {
        int roll = random.nextInt(100);
        if (roll < 70) return new CraftRoll("生産成功", 1);
        if (roll < 95) return new CraftRoll("大成功", 3);
        return new CraftRoll("奇跡的成功", 5);
    }

    public ItemStack craftArmor(Player player, ArmorForgeRecipe recipe) {
        return craftArmor(player, recipe, rollCraftTier());
    }

    /** ★変更: 抽選済みのCraftRollを受け取れるようにしたオーバーロード(鍛造ミニゲーム対応) */
    public ItemStack craftArmor(Player player, ArmorForgeRecipe recipe, CraftRoll craftRoll) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return null;

        SAOArmor baseArmor = plugin.getSaoItemManager().getArmor(recipe.resultArmorId);
        if (baseArmor == null) return null;

        int currentProf = stats.proficiency.getOrDefault(ARMOR_FORGING_SKILL, 1);
        int currentExp = stats.proficiencyExp.getOrDefault(ARMOR_FORGING_SKILL, 0);

        String tierName = craftRoll.tierName;
        int points = craftRoll.points;

        ItemStack result = plugin.getSaoItemManager().createArmorItem(baseArmor);
        if (result == null) return null;

        setPendingPoints(result, points);

        player.sendMessage("§b[System] §r§a鍛造§b【" + tierName + "】§a！ §e" + baseArmor.name + " §aの生成に成功しました！");
        // ★追加(実績): 奇跡的成功を記録する
        if (plugin.getAchievementManager() != null) plugin.getAchievementManager().onForge(player, tierName, "a:" + recipe.resultArmorId);
        player.sendMessage("§b[System] §r§d初期強化ポイントを" + points + "回分獲得しました。続けてDEF/DUR/WGT/STR/AGIに割り振ってください。");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);

        // --- 経験値獲得ロジック(武器と同じ: 成功段階ごとの固定値) ---
        // ★変更: 生産成功=10, 大成功=15, 奇跡的成功=20
        int expGain;
        if ("生産成功".equals(tierName)) {
            expGain = 10;
        } else if ("大成功".equals(tierName)) {
            expGain = 15;
        } else {
            expGain = 20;
        }

        // ★変更: 段階的な減少は廃止。必要熟練度より30以上高い場合のみ経験値0。
        int diff = currentProf - recipe.requiredProficiency;
        if (diff >= 30) {
            expGain = 0;
        }

        int limit = plugin.getLimitManager().getSkillLimit(ARMOR_FORGING_SKILL);
        if (currentProf >= limit) {
            expGain = 0;
        }

        if (expGain > 0) {
            currentExp += expGain;
            player.sendMessage("§7[" + ARMOR_FORGING_SKILL + "] 経験値獲得: +" + expGain);

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
                    player.sendMessage("§b[" + ARMOR_FORGING_SKILL + "] 熟練度が上がりました！ Lv." + (currentProf - 1) + " -> Lv." + currentProf);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                } else {
                    break;
                }
            }

            stats.proficiency.put(ARMOR_FORGING_SKILL, currentProf);
            stats.proficiencyExp.put(ARMOR_FORGING_SKILL, currentExp);

            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }
        }

        return result;
    }
}