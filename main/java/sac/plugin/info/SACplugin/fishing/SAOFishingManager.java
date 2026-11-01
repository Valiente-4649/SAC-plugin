package sac.plugin.info.SACplugin.fishing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.*;

public class SAOFishingManager  implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, FishingRodData> rods = new HashMap<>();
    private final Map<String, FishData> fishes = new HashMap<>();
    // ★追加: リール・ルアー
    private final Map<String, FishingReelData> reels = new HashMap<>();
    private final Map<String, FishingLureData> lures = new HashMap<>();
    public java.util.Map<String, FishingRodData> getAllRods() { return rods; }
    public java.util.Map<String, FishingReelData> getAllReels() { return reels; }
    public java.util.Map<String, FishingLureData> getAllLures() { return lures; }

    // ==========================================
    // ★追加: 竿の状態(耐久値・装備中のリール/ルアーID)を保持するPDCキー。
    // 竿は1本1本が個別の状態を持つ道具(=修理無し・耐久値管理)なので、
    // 表示用のLoreとは別に、実際の判定に使う値はここに保存する。
    // ==========================================
    public final org.bukkit.NamespacedKey rodDurabilityKey;
    public final org.bukkit.NamespacedKey rodReelIdKey;
    public final org.bukkit.NamespacedKey rodLureIdKey;

    public SAOFishingManager(SACplugin plugin) {
        this.plugin = plugin;
        this.rodDurabilityKey = new org.bukkit.NamespacedKey(plugin, "fishing_rod_durability");
        this.rodReelIdKey = new org.bukkit.NamespacedKey(plugin, "fishing_rod_reel_id");
        this.rodLureIdKey = new org.bukkit.NamespacedKey(plugin, "fishing_rod_lure_id");
        loadData();
    }

    @Override
    public void reload() {
        loadData();
    }

    public void loadData() {
        // 竿データの読み込み
        File rodFile = new File(plugin.getDataFolder(), "fishing_rods.json");
        if (!rodFile.exists()) plugin.saveResource("fishing_rods.json", false);
        try (FileReader r = new FileReader(rodFile)) {
            Type t = new TypeToken<Map<String, FishingRodData>>(){}.getType();
            rods.putAll(new Gson().fromJson(r, t));
        } catch (Exception e) { e.printStackTrace(); }

        // 魚データの読み込み
        File fishFile = new File(plugin.getDataFolder(), "fishes.json");
        if (!fishFile.exists()) plugin.saveResource("fishes.json", false);
        try (FileReader r = new FileReader(fishFile)) {
            Type t = new TypeToken<Map<String, FishData>>(){}.getType();
            fishes.putAll(new Gson().fromJson(r, t));
        } catch (Exception e) { e.printStackTrace(); }

        // ★追加: リールデータの読み込み
        File reelFile = new File(plugin.getDataFolder(), "fishing_reels.json");
        if (!reelFile.exists()) plugin.saveResource("fishing_reels.json", false);
        try (FileReader r = new FileReader(reelFile)) {
            Type t = new TypeToken<Map<String, FishingReelData>>(){}.getType();
            Map<String, FishingReelData> loaded = new Gson().fromJson(r, t);
            if (loaded != null) reels.putAll(loaded);
        } catch (Exception e) { e.printStackTrace(); }

        // ★追加: ルアーデータの読み込み
        File lureFile = new File(plugin.getDataFolder(), "fishing_lures.json");
        if (!lureFile.exists()) plugin.saveResource("fishing_lures.json", false);
        try (FileReader r = new FileReader(lureFile)) {
            Type t = new TypeToken<Map<String, FishingLureData>>(){}.getType();
            Map<String, FishingLureData> loaded = new Gson().fromJson(r, t);
            if (loaded != null) lures.putAll(loaded);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public FishingRodData getRod(String id) { return rods.get(id); }
    public Map<String, FishData> getAllFishes() { return fishes; }
    public FishingReelData getReel(String id) { return reels.get(id); }
    public FishingLureData getLure(String id) { return lures.get(id); }

    public ItemStack createFishItem(FishData data) {
        ItemStack item = new ItemStack(Material.valueOf(data.material));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + data.name);
        meta.setCustomModelData(data.customModelData);

        // ==========================================
        // ★追加: サイズをランダムに決定する(cm)。sizeMaxの90%以上なら「大物」として
        // 価格ボーナス(+30%)をつける。
        // ==========================================
        double size = data.sizeMin + Math.random() * (data.sizeMax - data.sizeMin);
        boolean isBig = size >= data.sizeMin + (data.sizeMax - data.sizeMin) * 0.9;
        long finalPrice = isBig ? Math.round(data.price * 1.3) : data.price;

        List<String> lore = new ArrayList<>();
        lore.add("§8" + data.id);
        lore.add("§7Rank: §f" + data.rank);
        lore.add("§7Category: §f" + data.category);
        lore.add("§7Size: §f" + String.format("%.1f", size) + "cm" + (isBig ? " §6[大物]" : ""));
        lore.add("");
        lore.add("§f" + data.description);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "fish_sell_price"),
                org.bukkit.persistence.PersistentDataType.LONG, finalPrice);
        item.setItemMeta(meta);
        return item;
    }
    // 魚データの取得メソッド
    public FishData getFish(String id) { return fishes.get(id); }

    // ==========================================
    // ★追加: リールをアイテム化する
    // ==========================================
    public ItemStack createReelItem(FishingReelData data) {
        ItemStack item = new ItemStack(org.bukkit.Material.matchMaterial(data.material != null ? data.material : "STRING"));
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + data.name);
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData);

        List<String> lore = new ArrayList<>();
        lore.add("§8" + data.id);
        lore.add("§7Rank: §f" + data.rank);
        lore.add("§7Category: §f" + data.category);
        lore.add("§7Requires: §eLv." + data.requiredProficiency);
        lore.add("§7暴れ抑制: §b" + (int) data.rageSuppressPercent + "%");
        lore.add("");
        lore.add("§f" + data.description);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ==========================================
    // ★追加: ルアーをアイテム化する
    // ==========================================
    public ItemStack createLureItem(FishingLureData data) {
        ItemStack item = new ItemStack(org.bukkit.Material.matchMaterial(data.material != null ? data.material : "FEATHER"));
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + data.name);
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData);

        List<String> lore = new ArrayList<>();
        lore.add("§8" + data.id);
        lore.add("§7Rank: §f" + data.rank);
        lore.add("§7Category: §f" + data.category);
        lore.add("§7Requires: §eLv." + data.requiredProficiency);
        lore.add("§7高ランク確率: §b+" + (data.rareRankBonusPercent % 1 == 0 ? String.valueOf((int) data.rareRankBonusPercent) : String.valueOf(data.rareRankBonusPercent)) + "%");
        lore.add("");
        lore.add("§f" + data.description);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // 釣竿をゲーム内のアイテム(ItemStack)に変換するメソッド
    public ItemStack createRodItem(FishingRodData data) {
        ItemStack item = new ItemStack(org.bukkit.Material.valueOf(data.material != null ? data.material : "FISHING_ROD"));
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (data.customModelData > 0) meta.setCustomModelData(data.customModelData);

        // ★追加: 新品の耐久値・未装備状態をPDCに書き込む
        meta.getPersistentDataContainer().set(rodDurabilityKey, org.bukkit.persistence.PersistentDataType.INTEGER, data.maxDurability);
        item.setItemMeta(meta);

        refreshRodLore(item, data);
        return item;
    }

    /**
     * ★追加: 竿のLoreを、現在のPDC状態(耐久値・装備中のリール/ルアー)から再構築する。
     * 耐久値が減った時・リール/ルアーを付け替えた時に呼ぶ。
     */
    public void refreshRodLore(ItemStack rodItem, FishingRodData data) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName("§b" + data.name);

        int durability = meta.getPersistentDataContainer().getOrDefault(rodDurabilityKey, org.bukkit.persistence.PersistentDataType.INTEGER, data.maxDurability);
        String reelId = meta.getPersistentDataContainer().get(rodReelIdKey, org.bukkit.persistence.PersistentDataType.STRING);
        String lureId = meta.getPersistentDataContainer().get(rodLureIdKey, org.bukkit.persistence.PersistentDataType.STRING);

        FishingReelData reel = reelId != null ? getReel(reelId) : null;
        FishingLureData lure = lureId != null ? getLure(lureId) : null;

        List<String> lore = new ArrayList<>();
        lore.add("§8" + data.id);
        lore.add("§7Rank: §f" + data.rank);
        lore.add("§7Category: §f" + data.category);
        lore.add("§7Requires: §eLv." + data.requiredProficiency);
        lore.add("§7Durability: §a" + durability + " / " + data.maxDurability + " §8(修理不可・0で破損)");
        lore.add("");
        lore.add("§7[Reel] §f" + (reel != null ? reel.name : "§8(未装備)"));
        lore.add("§7[Lure] §f" + (lure != null ? lure.name : "§8(未装備)"));
        lore.add("");
        lore.add("§f" + data.description);
        meta.setLore(lore);
        rodItem.setItemMeta(meta);
    }

    // ==========================================
    // ★追加: 竿の耐久値の取得・消費
    // ==========================================
    public int getRodDurability(ItemStack rodItem, FishingRodData data) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return 0;
        return meta.getPersistentDataContainer().getOrDefault(rodDurabilityKey, org.bukkit.persistence.PersistentDataType.INTEGER, data.maxDurability);
    }

    /** 釣りを試みるたびに(成功・失敗問わず)1消費する。戻り値は消費後の残り耐久値。 */
    public int consumeRodDurability(ItemStack rodItem, FishingRodData data) {
        int current = getRodDurability(rodItem, data);
        int next = Math.max(0, current - 1);
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        meta.getPersistentDataContainer().set(rodDurabilityKey, org.bukkit.persistence.PersistentDataType.INTEGER, next);
        rodItem.setItemMeta(meta);
        refreshRodLore(rodItem, data);
        return next;
    }

    // ==========================================
    // ★追加: 竿へのリール・ルアーの取り付け
    // ==========================================
    public void attachReel(ItemStack rodItem, FishingRodData data, String reelId) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        meta.getPersistentDataContainer().set(rodReelIdKey, org.bukkit.persistence.PersistentDataType.STRING, reelId);
        rodItem.setItemMeta(meta);
        refreshRodLore(rodItem, data);
    }

    public void attachLure(ItemStack rodItem, FishingRodData data, String lureId) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        meta.getPersistentDataContainer().set(rodLureIdKey, org.bukkit.persistence.PersistentDataType.STRING, lureId);
        rodItem.setItemMeta(meta);
        refreshRodLore(rodItem, data);
    }

    /** 竿に現在取り付けられているリールを取得(無ければnull) */
    public FishingReelData getAttachedReel(ItemStack rodItem) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(rodReelIdKey, org.bukkit.persistence.PersistentDataType.STRING);
        return id != null ? getReel(id) : null;
    }

    /** 竿に現在取り付けられているルアーを取得(無ければnull) */
    public FishingLureData getAttachedLure(ItemStack rodItem) {
        org.bukkit.inventory.meta.ItemMeta meta = rodItem.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(rodLureIdKey, org.bukkit.persistence.PersistentDataType.STRING);
        return id != null ? getLure(id) : null;
    }
}