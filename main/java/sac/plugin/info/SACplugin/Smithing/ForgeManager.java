package sac.plugin.info.SACplugin.Smithing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ForgeManager implements Reloadable {
    private final SACplugin plugin;
    private final List<ForgeRecipe> recipes = new ArrayList<>();
    private final Random random = new Random();

    public ForgeManager(SACplugin plugin) {
        this.plugin = plugin;
        loadRecipes();
    }

    public void loadRecipes() {
        File file = new File(plugin.getDataFolder(), "weapon_forge.json");
        if (!file.exists()) plugin.saveResource("weapon_forge.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<ForgeRecipe>>(){}.getType();
            List<ForgeRecipe> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                recipes.clear();
                recipes.addAll(loaded);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public List<ForgeRecipe> getRecipes() {
        return recipes;
    }

    @Override
    public void reload() {
        loadRecipes();
    }

    // ==========================================
    // ★追加: 武器の攻撃属性(type)から、対応する鍛造スキル名を返す。
    // 未対応の属性(将来武器種追加時に対応漏れがあった場合)はnullを返す。
    // ==========================================
    public static String getForgingSkillForType(String weaponType) {
        if (weaponType == null) return null;
        switch (weaponType) {
            case "Slash": return "斬撃武器鍛造";
            case "Thrust": return "刺突武器鍛造";
            case "Strike": return "打撃武器鍛造";
            default: return null;
        }
    }

    public ForgeRecipe getRecipe(String recipeId) {
        for (ForgeRecipe r : recipes) {
            if (r.recipeId.equals(recipeId)) return r;
        }
        return null;
    }

    /**
     * ★追加: メイン素材＋サブ素材を1つのMapにまとめて返す。
     * hasMaterials / consumeMaterials の呼び出しに使用する。
     */
    public java.util.Map<String, Integer> getAllMaterials(ForgeRecipe recipe) {
        java.util.Map<String, Integer> all = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            all.put(recipe.mainMaterialId, recipe.mainMaterialAmount);
        }
        if (recipe.subMaterials != null) {
            for (java.util.Map.Entry<String, Integer> e : recipe.subMaterials.entrySet()) {
                all.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        // 後方互換: 旧形式のmaterialsフィールドがあれば使用
        if (all.isEmpty() && recipe.materials != null) {
            all.putAll(recipe.materials);
        }
        return all;
    }

    /**
     * ★追加: 修理用素材マップを返す（生成と同じ素材数・colは呼び出し側で計算）
     */
    public java.util.Map<String, Integer> getRepairMaterials(ForgeRecipe recipe) {
        // 素材数は生成と同じ（仕様: 修理の素材数は変わらず）
        return getAllMaterials(recipe);
    }

    /**
     * ★追加: 強化用素材マップを返す（メイン・サブ共に2/3切り上げ）
     */
    public java.util.Map<String, Integer> getUpgradeMaterials(ForgeRecipe recipe) {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        if (recipe.mainMaterialId != null && !recipe.mainMaterialId.isEmpty()) {
            result.put(recipe.mainMaterialId, (int) Math.ceil(recipe.mainMaterialAmount * 2.0 / 3.0));
        }
        if (recipe.subMaterials != null) {
            for (java.util.Map.Entry<String, Integer> e : recipe.subMaterials.entrySet()) {
                result.merge(e.getKey(), (int) Math.ceil(e.getValue() * 2.0 / 3.0), Integer::sum);
            }
        }
        if (result.isEmpty() && recipe.materials != null) {
            for (java.util.Map.Entry<String, Integer> e : recipe.materials.entrySet()) {
                result.put(e.getKey(), (int) Math.ceil(e.getValue() * 2.0 / 3.0));
            }
        }
        return result;
    }

    // ==========================================
    // ★修正: 隠しデータ(Bonus Stats・未割り振りポイント)は、色コードで隠すLore行ではなく
    // PersistentDataContainer(PDC)で保持する。§0(黒)によるLore非表示は使用中のリソースパックの
    // ツールチップ背景によっては非表示にならず、文字が見えてしまう不具合があったため。
    // ==========================================
    // ==========================================
    // ★変更: Bonus Statsを新体系に刷新。
    // SHARP=Sharpness(鋭さ), DURA=Durability(丈夫さ), QUICK=Quickness(速さ),
    // ACCU=Accuracy(正確さ), HEAVY=Heaviness(重さ)。
    // 旧キー(STR/DUR/DEX/ACC/WGT)とは完全に別名にしてあるため、
    // 旧武器は自動的に「未割り振り(0)」として読み直され、旧ポイント数は
    // migrateStatSchemeV3IfNeeded()で未割り振りポイントに変換して引き継がれる。
    // ==========================================
    public static final String[] STAT_KEYS = {"SHARP", "DURA", "QUICK", "ACCU", "HEAVY"};

    private static SACplugin getMainInstance() {
        return SACplugin.getPlugin(SACplugin.class);
    }

    private static org.bukkit.NamespacedKey statPdcKey(String key) {
        return new org.bukkit.NamespacedKey(getMainInstance(), "bonus_" + key.toLowerCase());
    }

    private static org.bukkit.NamespacedKey pendingPointsPdcKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "pending_points");
    }

    public static int getBonusStat(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Integer stored = meta.getPersistentDataContainer().get(statPdcKey(key), org.bukkit.persistence.PersistentDataType.INTEGER);
        if (stored != null) return stored;
        // 後方互換: 過去にLoreへ書き込まれてしまった旧タグが残っていれば読む
        if (meta.hasLore()) return getBonusStatFromLore(meta.getLore(), key);
        return 0;
    }

    private static int getBonusStatFromLore(List<String> lore, String key) {
        String prefix = "§0Bonus" + key + ":";
        for (String line : lore) {
            if (line.startsWith(prefix)) {
                try {
                    return Integer.parseInt(line.replace(prefix, ""));
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static void setBonusStat(ItemStack item, String key, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(statPdcKey(key), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    private static org.bukkit.NamespacedKey upgradeDisplayCountKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "upgrade_display_count");
    }

    /**
     * 名前の「+N」表記専用のカウンタ。生産(初期強化ポイントの割り振り)では変化せず、
     * 有料強化の成功時にのみ増える。旧仕様武器の引継ぎ時は、旧UpgradeLevelの値を
     * そのままここにセットする(「今まで何回強化してきたか」を引き継ぐため)。
     */
    public static int getUpgradeDisplayCount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(upgradeDisplayCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    public static void setUpgradeDisplayCount(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeDisplayCountKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    /** 有料強化が成功した時にだけ呼び出すこと(生産・無料割り振りでは呼ばない) */
    public static void incrementUpgradeDisplayCount(ItemStack item) {
        setUpgradeDisplayCount(item, getUpgradeDisplayCount(item) + 1);
    }

    public static int getTotalBonusStats(ItemStack item) {
        int total = 0;
        for (String key : STAT_KEYS) total += getBonusStat(item, key);
        return total;
    }

    // ==========================================
    // ★追加: 「強化試行回数」の公開アクセサ。従来はUpgradeMenuListener内のprivateメソッドで
    // しか読み書きされていなかったため、他クラス(SAOCommandなど)からも扱えるようにする。
    // キー("upgrade_attempts")は完全に同一なので、UpgradeMenuListener側と完全互換。
    // ==========================================
    private static org.bukkit.NamespacedKey upgradeAttemptsKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "upgrade_attempts");
    }

    public static int getUpgradeAttempts(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        return stored != null ? stored : 0;
    }

    public static void setUpgradeAttempts(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(upgradeAttemptsKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
    }

    // ==========================================
    // 「初期強化ポイント(生産成功時に付与される、まだ振り分けていないポイント数)」を管理する
    // ==========================================
    public static int getPendingPoints(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Integer stored = meta.getPersistentDataContainer().get(pendingPointsPdcKey(), org.bukkit.persistence.PersistentDataType.INTEGER);
        if (stored != null) return stored;
        // 後方互換: 過去にLoreへ書き込まれてしまった旧タグが残っていれば読む
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line.startsWith("§0PendingPoints:")) {
                    try {
                        return Integer.parseInt(line.replace("§0PendingPoints:", ""));
                    } catch (Exception ignored) {}
                }
            }
        }
        return 0;
    }

    public static void setPendingPoints(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(pendingPointsPdcKey(), org.bukkit.persistence.PersistentDataType.INTEGER, value);
        item.setItemMeta(meta);
        stripLegacyHiddenLoreTags(item);
    }

    // ==========================================
    // ★追加: 生産ミニゲームで叩いた回数(演出用)。強化ミニゲームの目標回数を
    // 決める時の「基準」として参照する。ステータス計算には一切使わない。
    // ==========================================
    private static org.bukkit.NamespacedKey craftHitCountKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "craft_hit_count");
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

    // ==========================================
    // ★追加: 過去にLoreへ紛れ込んでしまった隠しタグ(§0Bonus***:、§0PendingPoints:)を除去する。
    // ツールチップに文字化けのように表示されてしまっていた不具合の後始末。
    // ==========================================
    private static void stripLegacyHiddenLoreTags(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<String> lore = meta.getLore();
        List<String> cleaned = new ArrayList<>();
        for (String line : lore) {
            if (line.startsWith("§0Bonus") || line.startsWith("§0PendingPoints:")) continue;
            cleaned.add(line);
        }
        if (cleaned.size() != lore.size()) {
            meta.setLore(cleaned);
            item.setItemMeta(meta);
        }
    }

    // ==========================================
    // ★追加: 旧仕様(単一の品質ボーナス+UpgradeLevel)で強化されていた武器を、
    // 新仕様(STR/DUR/DEX/ACC/WGT)に一度だけ変換する。既に新仕様のタグ(PDC)を
    // 持っている武器や、旧タグを一切持たない(素材のままの)武器には何もしない。
    // ==========================================
    public static void migrateLegacyWeaponIfNeeded(ItemStack item, SAOWeapon base) {
        // ★修正: 内部に複数の早期returnがあるため、旧品質移行(migrateLegacyQualityIfNeeded)の
        // 結果に関わらず、新スキーム移行(migrateStatSchemeV3IfNeeded)は必ず個別に試みる。
        migrateLegacyQualityIfNeeded(item, base);
        migrateStatSchemeV3IfNeeded(item, base);
    }

    private static org.bukkit.NamespacedKey legacyQualityMigratedKey() {
        return new org.bukkit.NamespacedKey(getMainInstance(), "legacy_quality_migrated");
    }

    private static void migrateLegacyQualityIfNeeded(ItemStack item, SAOWeapon base) {
        if (item == null || base == null || !item.hasItemMeta()) return;
        ItemMeta metaCheck = item.getItemMeta();

        // ==========================================
        // ★修正: 「もう移行済みか」の判定を、STR/DUR等やSHARP/DURA等のような
        // スキーム名に依存する判定から、この移行専用の恒久フラグに変更する。
        // スキーム名は将来また変わりうるが、このフラグは一度立てたら消さないため、
        // 同じ武器に対して何度もこの処理が誤って再発火することがなくなる。
        // ==========================================
        if (metaCheck.getPersistentDataContainer().has(legacyQualityMigratedKey(), org.bukkit.persistence.PersistentDataType.INTEGER)) {
            return;
        }
        if (!metaCheck.hasLore()) return;
        List<String> lore = metaCheck.getLore();

        int oldLevel = 0;
        boolean hasLegacyTag = false;
        for (String line : lore) {
            if (line.startsWith("§0UpgradeLevel:")) {
                hasLegacyTag = true;
                try {
                    oldLevel = Integer.parseInt(line.replace("§0UpgradeLevel:", ""));
                } catch (Exception ignored) {}
                break;
            }
        }

        // ★旧品質ボーナス(Attackの"(+N)"表記)を取得。UpgradeLevelが無くても品質ボーナスだけの
        // 武器(未強化の旧生産品)を移行対象にするため、こちらもチェックする。
        double legacyAttackBonus = sac.plugin.info.SACplugin.Event.SAODamageListener.getWeaponBonus(item);
        if (!hasLegacyTag && legacyAttackBonus <= 0) {
            // 移行対象データが無くても、Lore側に紛れ込んだ旧隠しタグの掃除だけは行う
            stripLegacyHiddenLoreTags(item);
            markLegacyQualityMigrated(item);
            return;
        }

        // ==========================================
        // 旧ボーナスをSTR/DURに直接変換せず、「自由に振り直せるポイント」として引き継ぐ。
        // 生産攻撃力ボーナス: +5につき1回(四捨五入。8→2回、13→3回)
        // 強化値: 1につき1回
        // ==========================================
        int pointsFromProduction = (int) Math.round(legacyAttackBonus / 5.0);
        int pointsFromUpgrade = oldLevel;
        int totalPoints = pointsFromProduction + pointsFromUpgrade;

        // ★追加: 「+N」表記用のカウンタに、旧UpgradeLevel(今まで何回強化してきたか)をそのまま引き継ぐ。
        // 生産攻撃力ボーナス由来の分はここに含めない(名前には出さない)。
        setUpgradeDisplayCount(item, getUpgradeDisplayCount(item) + oldLevel);

        // 旧ボーナスをいったん0にリセットする(PDCに0を書き込み、
        // Attack/Durability/Weightもベース値に再計算される。これで移行済みマークにもなる)
        applyBonusStatDeltas(item, base, new int[5]);

        // 引き継いだポイントを、既存の未割り振りポイントに加算する
        setPendingPoints(item, getPendingPoints(item) + totalPoints);

        // ★試行回数(UpgradeAttempts)が未設定なら、旧UpgradeLevel分は消費済みとして引き継ぐ
        ItemMeta meta = item.getItemMeta();
        boolean hasAttempts = meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(getMainInstance(), "upgrade_attempts"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        if (!hasAttempts) {
            for (String line : meta.hasLore() ? meta.getLore() : new ArrayList<String>()) {
                if (line.startsWith("§0UpgradeAttempts:")) { hasAttempts = true; break; }
            }
        }
        if (!hasAttempts && oldLevel > 0) {
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(getMainInstance(), "upgrade_attempts"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, oldLevel);
            item.setItemMeta(meta);
        }

        stripLegacyHiddenLoreTags(item);
        markLegacyQualityMigrated(item);
    }

    /** ★追加: 太古の品質ボーナス移行が完了(または対象外と判定)したことを恒久的に記録する */
    private static void markLegacyQualityMigrated(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(legacyQualityMigratedKey(), org.bukkit.persistence.PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
    }

    // ★旧スキーム(STR/DUR/DEX/ACC/WGT)のPDCキー名。新スキームと完全に別名なので判別に使える。
    private static final String[] OLD_STAT_KEYS_V2 = {"STR", "DUR", "DEX", "ACC", "WGT"};

    private static org.bukkit.NamespacedKey oldStatPdcKeyV2(String key) {
        return new org.bukkit.NamespacedKey(getMainInstance(), "bonus_" + key.toLowerCase());
    }

    /**
     * ★追加: 旧仕様(STR/DUR/DEX/ACC/WGTのBonus Stats)で強化されていた武器を、
     * 新仕様(SHARP/DURA/QUICK/ACCU/HEAVY)に一度だけ変換する。
     * 「敵を一度攻撃する」等、migrateLegacyWeaponIfNeeded()が呼ばれるタイミングで
     * 自動的に一緒に実行される。旧ポイントの内訳は保持せず、合計数を
     * そのまま「自由に振り直せる未割り振りポイント」として引き継ぐ
     * (ステータス自体は新スキームで0からになる＝実質リセット)。
     */
    private static void migrateStatSchemeV3IfNeeded(ItemStack item, SAOWeapon base) {
        if (item == null || base == null || !item.hasItemMeta()) return;

        ItemMeta metaCheck = item.getItemMeta();
        // 既に新スキームのPDCを持っていれば対象外(=既に移行済み、または新規生産品)
        for (String key : STAT_KEYS) {
            if (metaCheck.getPersistentDataContainer().has(statPdcKey(key), org.bukkit.persistence.PersistentDataType.INTEGER)) {
                return;
            }
        }

        int totalOldPoints = 0;
        boolean hasOldTag = false;
        for (String oldKey : OLD_STAT_KEYS_V2) {
            if (metaCheck.getPersistentDataContainer().has(oldStatPdcKeyV2(oldKey), org.bukkit.persistence.PersistentDataType.INTEGER)) {
                hasOldTag = true; // ★修正: 値が0でも「旧キーが存在する」だけで移行対象とみなす
                Integer v = metaCheck.getPersistentDataContainer().get(oldStatPdcKeyV2(oldKey), org.bukkit.persistence.PersistentDataType.INTEGER);
                if (v != null && v > 0) totalOldPoints += v;
            }
        }
        if (!hasOldTag) return;

        // 旧キーのPDCを削除
        ItemMeta meta = item.getItemMeta();
        for (String oldKey : OLD_STAT_KEYS_V2) {
            meta.getPersistentDataContainer().remove(oldStatPdcKeyV2(oldKey));
        }
        item.setItemMeta(meta);

        // 新スキームを全部0で初期化(Attack/Weight/Durability表示もベース値に再計算される)
        applyBonusStatDeltas(item, base, new int[5]);

        // 旧スキームで振っていたポイント数の合計を、未割り振りポイントとしてそのまま引き継ぐ
        setPendingPoints(item, getPendingPoints(item) + totalOldPoints);

        stripLegacyHiddenLoreTags(item);
    }

    /**
     * 武器アイテムのBonus Stats(SHARP/DURA/QUICK/ACCU/HEAVY)にdeltasを加算し、
     * Attack/Weight/Durability/Bonus Stats表示をすべて再計算して反映する。
     * deltasはSTAT_KEYSの順(SHARP,DURA,QUICK,ACCU,HEAVY)。
     *
     * 新体系の効果:
     * - Sharpness(SHARP): 強化値1につきAttack+3
     * - Durability(DURA): 強化値1につき耐久値+50
     * - Quickness(QUICK): 強化値1につきWeight-1
     * - Accuracy(ACCU):   強化値1につき命中率+1%(miss率-1%)。Attack/Weightには影響しない
     * - Heaviness(HEAVY): 強化値1につきWeight+1。さらに1以上あれば(固定効果、点数に比例しない)、
     *                     パリィ可能な敵レベルが自分Lv+1になり、鋭さによるAttack増加分の10%
     *                     (最低+1)がAttackに上乗せされる
     */
    public static void applyBonusStatDeltas(ItemStack item, SAOWeapon base, int[] deltas) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<String> lore = new ArrayList<>(meta.getLore());

        int[] values = new int[5];
        for (int i = 0; i < 5; i++) {
            values[i] = getBonusStat(item, STAT_KEYS[i]) + deltas[i];
            if (values[i] < 0) values[i] = 0;
        }

        for (int i = 0; i < 5; i++) {
            setBonusStat(item, STAT_KEYS[i], values[i]);
        }
        // setBonusStatはsetItemMeta済みなので、以降のLore編集用にmetaを取り直す
        meta = item.getItemMeta();
        lore = new ArrayList<>(meta.getLore());

        int sharp = values[0], dura = values[1], quick = values[2], accu = values[3], heavy = values[4];

        int sharpAtkBonus = sharp * 3;
        // ★Heavinessが1以上あれば固定効果として、鋭さAttack増加分の10%(最低+1)を上乗せ
        int heavyAtkBonus = heavy > 0 ? Math.max(1, (int) Math.round(sharpAtkBonus * 0.10)) : 0;
        int totalAtkBonus = sharpAtkBonus + heavyAtkBonus;

        int durBonus = dura * 50;

        // Quicknessは軽量化(-1/pt)、Heavinessは増量(+1/pt)。両方の差分をWeightに反映する
        int weightDelta = heavy - quick;

        int minAtk = (int) base.minAttack + totalAtkBonus;
        int maxAtk = (int) base.maxAttack + totalAtkBonus;
        int newMaxDur = base.maxDurability + durBonus;
        int newWeight = Math.max(0, (int) base.weight + weightDelta);

        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Attack:")) {
                lore.set(i, "§7Attack: §b" + minAtk + " - " + maxAtk + (totalAtkBonus > 0 ? " §a(+" + totalAtkBonus + ")" : ""));
                break;
            }
        }
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).contains("Weight:")) {
                String diffText = weightDelta == 0 ? "" : (weightDelta < 0 ? " §a(" + weightDelta + ")" : " §c(+" + weightDelta + ")");
                lore.set(i, "§7Weight: §4" + newWeight + diffText);
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

        // Bonus Stats表示ブロック(末尾)。既存があれば作り直す
        int bonusHeaderIdx = lore.indexOf("§6[ Bonus Stats ]");
        if (bonusHeaderIdx != -1) {
            while (lore.size() > bonusHeaderIdx) lore.remove(lore.size() - 1);
        }
        double quickSkillReduction = quick * 0.1; // 上限は呼び出し側(スキル実行時)で0.5秒下限を適用
        lore.add("§6[ Bonus Stats ]");
        lore.add("§7Sharpness +" + sharp + " §8(Attack +" + sharpAtkBonus + ")");
        lore.add("§7Durability +" + dura + " §8(耐久値 +" + durBonus + ")");
        lore.add("§7Quickness +" + quick + " §8(重量 -" + quick + ", スキル発動 -" + String.format("%.1f", quickSkillReduction) + "秒 ※下限0.5秒)");
        lore.add("§7Accuracy +" + accu + " §8(命中率 +" + accu + "%)");
        lore.add("§7Heaviness +" + heavy + " §8(重量 +" + heavy
                + (heavy > 0 ? ", パリィ可能Lv+1, Attack+" + heavyAtkBonus : "") + ")");

        meta.setLore(lore);

        // ★変更: 「+N」は生産(初期強化ポイント)には反映せず、有料強化の成功回数
        // (旧仕様引継ぎの場合は旧UpgradeLevel)だけを表示する
        int displayCount = getUpgradeDisplayCount(item);
        String baseName = base.name.replace("《", "").replace("》", "");
        meta.setDisplayName("§b《" + baseName + (displayCount > 0 ? " +" + displayCount : "") + "》");

        item.setItemMeta(meta);
        stripLegacyHiddenLoreTags(item);
    }

    // ==========================================
    // ★追加: 新体系(SHARP/DURA/QUICK/ACCU/HEAVY)の各ボーナス値を外部から参照するための
    // アクセサ。命中率計算(SAODamageListener)・スキル発動時間短縮(SwordSkillExecutor)・
    // パリィ判定レベル拡張(各種パリィ判定箇所)から使用する。
    // ==========================================
    public static int getAccuracyBonus(ItemStack item) {
        return getBonusStat(item, "ACCU");
    }

    public static int getQuicknessBonus(ItemStack item) {
        return getBonusStat(item, "QUICK");
    }

    public static int getHeavinessBonus(ItemStack item) {
        return getBonusStat(item, "HEAVY");
    }

    /**
     * ★追加: 強化失敗時のペナルティとして、武器の最大耐久値をamountだけ減少させる。
     * 現在耐久値が新しい最大値を超えていれば、そちらも合わせて減らす。
     * 最大耐久値は最低1を保証する(0や負数にはしない)。
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

    public boolean hasMaterials(Player player, Map<String, Integer> materials) {
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            String requiredId = entry.getKey();
            int requiredAmount = entry.getValue();
            int currentAmount = 0;

            // ★修正: getContents()ではなくgetStorageContents()を使う。
            // これによりオフハンド・防具スロットのアイテムが素材として誤検出されなくなる
            // (オフハンドに入れて生成/強化しても消費されずに通ってしまうバグの原因だった)。
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                    List<String> lore = item.getItemMeta().getLore();
                    if (!lore.isEmpty()) {
                        // Loreの1行目(ID)を確認
                        String id = org.bukkit.ChatColor.stripColor(lore.get(0));
                        if (id.equals(requiredId)) {
                            currentAmount += item.getAmount();
                        }
                    }
                }
            }
            if (currentAmount < requiredAmount) return false;
        }
        return true;
    }

    public void consumeMaterials(Player player, Map<String, Integer> materials) {
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            String requiredId = entry.getKey();
            int amountToRemove = entry.getValue();

            // ★修正: getStorageContents()を使い、オフハンド・防具スロットは対象外にする
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item == null || amountToRemove <= 0) continue;
                if (item.hasItemMeta() && item.getItemMeta().hasLore()) {
                    String id = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
                    if (id.equals(requiredId)) {
                        int amount = item.getAmount();
                        if (amount <= amountToRemove) {
                            player.getInventory().removeItem(item);
                            amountToRemove -= amount;
                        } else {
                            item.setAmount(amount - amountToRemove);
                            amountToRemove = 0;
                        }
                    }
                }
            }
        }
    }

    /**
     * 武器を実際に生成してプレイヤーに渡す
     */
    // ==========================================
    // ★追加: 生産の成功段階(生産成功/大成功/奇跡的成功)だけを先に抽選する。
    // 鍛造ミニゲーム(金床を叩く回数)の目標回数を、実際にアイテムを生成する前に
    // 決めるためのもの。抽選結果はcraftWeapon(player, recipe, roll)にそのまま渡す。
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

    public ItemStack craftWeapon(Player player, ForgeRecipe recipe) {
        return craftWeapon(player, recipe, rollCraftTier());
    }

    /** ★変更: 抽選済みのCraftRollを受け取れるようにしたオーバーロード(鍛造ミニゲーム対応) */
    public ItemStack craftWeapon(Player player, ForgeRecipe recipe, CraftRoll craftRoll) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return null;

        // ベースとなる武器データの取得
        SAOWeapon baseWeapon = plugin.getSaoItemManager().getWeapon(recipe.resultWeaponId);
        if (baseWeapon == null) return null;

        // ★変更: 武器の攻撃属性(斬撃/刺突/打撃)に応じた鍛造スキルで熟練度を管理する
        String forgingSkill = getForgingSkillForType(baseWeapon.type);
        if (forgingSkill == null) {
            player.sendMessage("§b[System] §r§c未対応の攻撃属性(" + baseWeapon.type + ")のため生成できません。");
            return null;
        }

        // 現在の鍛造熟練度 (デフォルトLv1)
        int currentProf = stats.proficiency.getOrDefault(forgingSkill, 1);
        // 現在の鍛造経験値 (デフォルト0)
        int currentExp = stats.proficiencyExp.getOrDefault(forgingSkill, 0);

        // ==========================================
        // ★変更: 生産成功(70%/初期強化+1) 大成功(25%/+3) 奇跡的成功(5%/+5) の3段階は
        // rollCraftTier()で既に決まっているので、ここでは受け取った結果を使うだけにする。
        // ★変更: 初期強化ポイントはその場でランダム付与せず、専用メニューでプレイヤーが
        // STR/DUR/DEX/ACC/WGTから1つずつ選んで割り振れるようにする(呼び出し元が
        // 返却されたItemStackを使って割り振りメニューを開く)。
        // ==========================================
        String tierName = craftRoll.tierName;
        int points = craftRoll.points;

        // アイテム生成 (SAOItemManagerを使って基本形を作る)
        ItemStack result = plugin.getSaoItemManager().createWeaponItem(baseWeapon);
        if (result == null) return null;

        // ★重要: ランダム付与せず、未割り振りポイントとして記録するだけ
        setPendingPoints(result, points);

        // ==========================================
        // ★追加: 新規生成した武器は太古の武器ではないので、生成した時点で
        // 太古の移行処理(migrateLegacyQualityIfNeeded)の対象外としてマークしておく。
        // これが無いと、初期強化ポイントをAttack系(Sharpness)に割り振った直後に攻撃した際、
        // そのAttackボーナス表記を「太古の品質ボーナス」と誤解釈し、余分な無料ポイントが
        // 追加で付与されてしまう不具合が起きる。
        // ==========================================
        markLegacyQualityMigrated(result);

        player.sendMessage("§b[System] §r§a鍛造§b【" + tierName + "】§a！ §e" + baseWeapon.name + " §aの生成に成功しました！");
        player.sendMessage("§b[System] §r§d初期強化ポイントを" + points + "回分獲得しました。続けてSTR/DUR/DEX/ACC/WGTに割り振ってください。");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);

        // --- 経験値獲得ロジック ---
        // ★変更: 生産経験値は「初期強化ポイント数×3」ではなく、成功段階ごとの固定値にする。
        // 生産成功=10, 大成功=15, 奇跡的成功=20
        int expGain;
        if ("生産成功".equals(tierName)) {
            expGain = 10;
        } else if ("大成功".equals(tierName)) {
            expGain = 15;
        } else {
            expGain = 20;
        }

        // ★変更: 段階的な減少(5レベル差ごとに-30%)は廃止。
        // 対象武器の必要熟練度よりプレイヤーの熟練度が30以上高い場合のみ、経験値0になる。
        int diff = currentProf - recipe.requiredProficiency;
        if (diff >= 30) {
            expGain = 0;
        }

        // ==========================================
        // ★変更: 対応する鍛造スキルの上限チェック
        // ==========================================
        int limit = plugin.getLimitManager().getSkillLimit(forgingSkill);
        if (currentProf >= limit) {
            expGain = 0; // すでに上限に達している場合は経験値をもらえない
        }

        // 経験値が1以上もらえる場合のみ処理
        if (expGain > 0) {
            currentExp += expGain;
            player.sendMessage("§7[" + forgingSkill + "] 経験値獲得: +" + expGain);

            // レベルアップ判定ループ
            while (true) {
                // ★追加: 複数上がって上限にぶつかったらストップ
                if (currentProf >= limit) {
                    currentExp = 0;
                    break;
                }

                // ★変更: 熟練度必要expを「20+L×(L-1)」の式に変更
                // (Lv1→2:20, Lv2→3:22, Lv3→4:26, Lv4→5:32 ... "直前+2,4,6,8..."と同じ)
                int requiredExp = 20 + currentProf * (currentProf - 1);

                if (currentExp >= requiredExp) {
                    currentExp -= requiredExp;
                    currentProf++;

                    player.sendMessage("§b[" + forgingSkill + "] 熟練度が上がりました！ Lv." + (currentProf - 1) + " -> Lv." + currentProf);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                } else {
                    break;
                }
            }

            // データの保存
            stats.proficiency.put(forgingSkill, currentProf);
            stats.proficiencyExp.put(forgingSkill, currentExp);

            // 必要ならスコアボード更新
            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }
        }

        return result;
    }


    // --- 以下、鍛造ステーションの設置・削除用メソッド (前回と同じ) ---

    public void spawnForgeStation(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setY(loc.getBlockY());
        loc.setZ(loc.getBlockZ() + 0.5);
        // ★追加: 元のlocationが持つyaw/pitch(プレイヤーの向いていた方向)が
        // 意図せずエンティティの回転に影響している可能性があるため、明示的に0にリセットする
        loc.setYaw(0f);
        loc.setPitch(0f);

        // 金床の見た目
        BlockDisplay display = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setBlock(Bukkit.createBlockData(org.bukkit.Material.ANVIL));
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, -0.5f),
                new org.joml.Quaternionf(),
                new Vector3f(1, 1, 1),
                new org.joml.Quaternionf()
        ));
        display.addScoreboardTag("sao_forge_display");

        // 「鍛造」の文字
        Location textLoc = loc.clone().add(0, 1.2, 0);
        TextDisplay textDisplay = (TextDisplay) loc.getWorld().spawnEntity(textLoc, EntityType.TEXT_DISPLAY);
        textDisplay.setText("§e鍛造");
        textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        textDisplay.addScoreboardTag("sao_forge_display");

        // クリック判定
        Interaction interaction = (Interaction) loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionHeight(1.0f);
        interaction.setInteractionWidth(1.0f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag("sao_forge_station");
    }

    public void removeForgeStation(Location location) {
        for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location, 3, 3, 3)) {
            if (entity.getScoreboardTags().contains("sao_forge_display") ||
                    entity.getScoreboardTags().contains("sao_forge_station")) {
                entity.remove();
                entity.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, entity.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, 0.05);
            }
        }
    }
}