package sac.plugin.info.SACplugin.accessory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * アクセサリーシステムの中核。
 * - accessory.jsonの読み込み(素材ごとの階層・加工必要熟練度・買取価格)
 * - 鑑定(ボスドロップ→鑑定素材)の抽選ロジック
 * - 加工(鑑定素材→装備可能なアクセサリー)のアイテム生成
 *
 * ==========================================
 * 抽選の流れ: ステータスの個数 → 付くステータスの種類 → 数値(段階)
 * ==========================================
 */
public class AccessoryManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, AccessoryMaterialData> materials = new HashMap<>();
    private long sellPrice = 500L;
    private final Random random = new Random();

    // ステータス種別の内部キー
    public static final String HP_PERCENT = "HP_PERCENT";
    public static final String HP_FLAT = "HP_FLAT";
    public static final String STR_PERCENT = "STR_PERCENT";
    public static final String STR_FLAT = "STR_FLAT";
    public static final String AGI_PERCENT = "AGI_PERCENT";
    public static final String AGI_FLAT = "AGI_FLAT";
    public static final String ACC = "ACC";
    public static final String REQ = "REQ";

    // 各ステータスの6段階(ACCのみ5段階)の実数値テーブル
    private static final double[] HP_PERCENT_VALUES = {3, 6, 9, 12, 15, 18};
    private static final double[] HP_FLAT_VALUES = {20, 40, 60, 80, 100, 110};
    private static final double[] STR_PERCENT_VALUES = {2, 4, 6, 8, 10, 12};
    private static final double[] STR_FLAT_VALUES = {1, 2, 3, 4, 5, 6};
    private static final double[] AGI_PERCENT_VALUES = {2, 4, 6, 8, 10, 12};
    private static final double[] AGI_FLAT_VALUES = {1, 2, 3, 4, 5, 6};
    private static final double[] ACC_VALUES = {1, 3, 5, 7, 9}; // 5段階
    private static final double[] REQ_VALUES = {1, 2, 3, 4, 5, 6};

    // 段階抽選確率(6段階)。49層以下と50層以上で異なる。
    private static final double[] TIER_PROB_LOW = {70, 25, 5, 0, 0, 0};
    private static final double[] TIER_PROB_HIGH = {60, 20, 10, 6, 3, 1};
    // ACC用(5段階)。HIGH側は6段階目の1%を5段階目に合算する。
    private static final double[] TIER_PROB_LOW_ACC = {70, 25, 5, 0, 0};
    private static final double[] TIER_PROB_HIGH_ACC = {60, 20, 10, 6, 4};

    private static final org.bukkit.NamespacedKey materialIdKey(SACplugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "accessory_material_id");
    }
    private static org.bukkit.NamespacedKey floorKey(SACplugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "accessory_floor");
    }
    private static org.bukkit.NamespacedKey statsJsonKey(SACplugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "accessory_stats_json");
    }
    private static org.bukkit.NamespacedKey isAppraisedKey(SACplugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "accessory_is_appraised");
    }
    private static org.bukkit.NamespacedKey isAccessoryKey(SACplugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "accessory_is_final");
    }

    public AccessoryManager(SACplugin plugin) {
        this.plugin = plugin;
        loadAccessoryData();
    }

    public void loadAccessoryData() {
        File file = new File(plugin.getDataFolder(), "accessory.json");
        if (!file.exists()) plugin.saveResource("accessory.json", false);

        try (FileReader reader = new FileReader(file)) {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("sellPrice")) {
                sellPrice = root.get("sellPrice").getAsLong();
            }
            if (root.has("materials")) {
                Type type = new TypeToken<Map<String, AccessoryMaterialData>>() {}.getType();
                Map<String, AccessoryMaterialData> loaded = new Gson().fromJson(root.getAsJsonObject("materials"), type);
                materials.clear();
                if (loaded != null) materials.putAll(loaded);
            }
            plugin.getLogger().info("[Accessory] 素材データを " + materials.size() + " 件読み込みました。");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reload() {
        loadAccessoryData();
    }

    public AccessoryMaterialData getMaterial(String id) {
        return materials.get(id);
    }

    public long getSellPrice() {
        return sellPrice;
    }

    // ==========================================
    // ★抽選ロジック本体
    // ==========================================

    /** 指定した階層のボスドロップを鑑定した時のステータスをまとめて抽選する */
    public List<AccessoryStatEntry> rollAppraisal(int floor) {
        int count = rollStatCount(floor);
        List<String> categories = rollCategories(count);

        List<AccessoryStatEntry> result = new ArrayList<>();
        for (String category : categories) {
            String finalType = resolveVariant(category);
            double value = rollValue(finalType, floor);
            result.add(new AccessoryStatEntry(finalType, value));
        }
        return result;
    }

    /**
     * ステータスの個数(1〜3)を抽選する。
     * 基本: 1つ80% / 2つ18% / 3つ2%。
     * 階層が1上がるごとに、1つの確率-0.1%、2つ・3つの確率各+0.05%
     * (floor=1を基準とする。floor<=1は基本確率のまま)。
     */
    private int rollStatCount(int floor) {
        int step = Math.max(0, floor - 1);
        double pOne = 80.0 - 0.1 * step;
        double pTwo = 18.0 + 0.05 * step;
        double pThree = 2.0 + 0.05 * step;
        // 安全策: 極端な階層でも確率が壊れないようクランプ(合計は常に100のはずだが念のため)
        pOne = Math.max(0.0, pOne);

        double roll = random.nextDouble() * 100.0;
        if (roll < pOne) return 1;
        if (roll < pOne + pTwo) return 2;
        return 3;
    }

    /** HP/STR/AGI/ACC/REQの5カテゴリから、重複無しでcount個を均等な確率で選ぶ */
    private List<String> rollCategories(int count) {
        List<String> pool = new ArrayList<>(List.of("HP", "STR", "AGI", "ACC", "REQ"));
        java.util.Collections.shuffle(pool, random);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    /** HP/STR/AGIは「%か実数値か」を50/50で決める。ACC/REQはそのまま。 */
    private String resolveVariant(String category) {
        switch (category) {
            case "HP":
                return random.nextBoolean() ? HP_PERCENT : HP_FLAT;
            case "STR":
                return random.nextBoolean() ? STR_PERCENT : STR_FLAT;
            case "AGI":
                return random.nextBoolean() ? AGI_PERCENT : AGI_FLAT;
            case "ACC":
                return ACC;
            case "REQ":
                return REQ;
            default:
                return REQ;
        }
    }

    /** 指定したステータス種別・階層に応じて、6段階(ACCは5段階)の中から数値を1つ抽選する */
    private double rollValue(String type, int floor) {
        boolean isHighTier = floor >= 50;
        double[] values;
        double[] probs;

        switch (type) {
            case HP_PERCENT: values = HP_PERCENT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case HP_FLAT: values = HP_FLAT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case STR_PERCENT: values = STR_PERCENT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case STR_FLAT: values = STR_FLAT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case AGI_PERCENT: values = AGI_PERCENT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case AGI_FLAT: values = AGI_FLAT_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            case ACC: values = ACC_VALUES; probs = isHighTier ? TIER_PROB_HIGH_ACC : TIER_PROB_LOW_ACC; break;
            case REQ: values = REQ_VALUES; probs = isHighTier ? TIER_PROB_HIGH : TIER_PROB_LOW; break;
            default: return 0;
        }

        double roll = random.nextDouble() * 100.0;
        double cumulative = 0;
        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (roll < cumulative) return values[i];
        }
        return values[values.length - 1];
    }

    // ==========================================
    // ★アイテム生成
    // ==========================================

    /** 鑑定結果として渡す「鑑定素材」アイテムを作る(まだ装備不可) */
    public ItemStack createAppraisedMaterialItem(AccessoryMaterialData material, List<AccessoryStatEntry> stats) {
        ItemStack item = new ItemStack(Material.PRISMARINE_CRYSTALS);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§d《鑑定済み：" + material.name + "》");
        List<String> lore = new ArrayList<>();
        lore.add("§8appraised_" + material.id);
        lore.add("");
        lore.add("§7元素材: §f" + material.name + " §8(第" + material.floor + "層)");
        lore.add("§7必要 加工熟練度: §e" + material.requiredProficiency);
        lore.add("");
        lore.add("§6[ 付与ステータス ]");
        appendStatLore(lore, stats);
        lore.add("");
        lore.add("§7加工台でアクセサリーに加工できます");
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(isAppraisedKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER, 1);
        meta.getPersistentDataContainer().set(materialIdKey(plugin), org.bukkit.persistence.PersistentDataType.STRING, material.id);
        meta.getPersistentDataContainer().set(floorKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER, material.floor);
        meta.getPersistentDataContainer().set(statsJsonKey(plugin), org.bukkit.persistence.PersistentDataType.STRING, new Gson().toJson(stats));

        item.setItemMeta(meta);
        return item;
    }

    /** 鑑定素材から、実際に装備できるアクセサリーを作る(加工台で使用) */
    public ItemStack createAccessoryItem(ItemStack appraisedMaterial) {
        if (appraisedMaterial == null || !appraisedMaterial.hasItemMeta()) return null;
        ItemMeta srcMeta = appraisedMaterial.getItemMeta();

        String materialId = srcMeta.getPersistentDataContainer().get(materialIdKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
        String statsJson = srcMeta.getPersistentDataContainer().get(statsJsonKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
        if (materialId == null || statsJson == null) return null;

        AccessoryMaterialData material = getMaterial(materialId);
        String materialName = material != null ? material.name : materialId;

        Type listType = new TypeToken<List<AccessoryStatEntry>>() {}.getType();
        List<AccessoryStatEntry> stats = new Gson().fromJson(statsJson, listType);

        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName("§d《" + materialName + "》");
        List<String> lore = new ArrayList<>();
        lore.add("§8accessory_" + materialId);
        lore.add("");
        lore.add("§7Equip: §fACCESSORY");
        lore.add("§7Requires: §e" + Math.max(1, material.requiredLevel));
        lore.add("");
        lore.add("§6[ 付与ステータス ]");
        appendStatLore(lore, stats);
        meta.setLore(lore);

        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        meta.setUnbreakable(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        meta.setMaxStackSize(1);

        meta.getPersistentDataContainer().set(isAccessoryKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER, 1);
        meta.getPersistentDataContainer().set(materialIdKey(plugin), org.bukkit.persistence.PersistentDataType.STRING, materialId);
        meta.getPersistentDataContainer().set(statsJsonKey(plugin), org.bukkit.persistence.PersistentDataType.STRING, statsJson);

        item.setItemMeta(meta);
        return item;
    }

    private void appendStatLore(List<String> lore, List<AccessoryStatEntry> stats) {
        for (AccessoryStatEntry stat : stats) {
            lore.add("§b- " + statLabel(stat.type) + ": §f" + statValueText(stat));
        }
    }

    private String statLabel(String type) {
        switch (type) {
            case HP_PERCENT: return "HP(%)";
            case HP_FLAT: return "HP(実数値)";
            case STR_PERCENT: return "STR(%)";
            case STR_FLAT: return "STR(実数値)";
            case AGI_PERCENT: return "AGI(%)";
            case AGI_FLAT: return "AGI(実数値)";
            case ACC: return "命中率";
            case REQ: return "REQ";
            default: return type;
        }
    }

    private String statValueText(AccessoryStatEntry stat) {
        boolean isPercent = stat.type.endsWith("_PERCENT") || stat.type.equals(ACC);
        String num = (stat.value == Math.floor(stat.value)) ? String.valueOf((int) stat.value) : String.valueOf(stat.value);
        if (stat.type.equals(REQ)) return "-" + num;
        return "+" + num + (isPercent ? "%" : "");
    }

    // ==========================================
    // ★アイテムからのステータス読み取り(装備反映・鍛造/加工判定用)
    // ==========================================

    public boolean isAppraisedMaterial(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(isAppraisedKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER);
    }

    public boolean isAccessory(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(isAccessoryKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER);
    }

    public String getMaterialId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(materialIdKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
    }

    public int getFloor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(floorKey(plugin), org.bukkit.persistence.PersistentDataType.INTEGER);
        return v != null ? v : 0;
    }

    /** アクセサリー(または鑑定素材)に付与されたステータス一覧を取得する */
    public List<AccessoryStatEntry> getStats(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return new ArrayList<>();
        String json = item.getItemMeta().getPersistentDataContainer().get(statsJsonKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
        if (json == null) return new ArrayList<>();
        Type listType = new TypeToken<List<AccessoryStatEntry>>() {}.getType();
        List<AccessoryStatEntry> result = new Gson().fromJson(json, listType);
        return result != null ? result : new ArrayList<>();
    }
}