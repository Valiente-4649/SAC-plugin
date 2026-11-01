package sac.plugin.info.SACplugin.shop;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerShopManager {
    private final SACplugin plugin;

    private final Map<UUID, PlayerShop> activeShops = new HashMap<>();
    public final Map<UUID, Long> pendingSales = new HashMap<>();
    public final Map<UUID, Long> pendingExp = new HashMap<>();

    public PlayerShopManager(SACplugin plugin) {
        this.plugin = plugin;
        loadShops(); // ★リロード時にファイルを読み込む
    }

    public static class PlayerShop {
        public UUID shopId;
        public UUID ownerUuid;
        public String ownerName;
        public String shopName;
        // ★追加: ショップの設置座標（エンティティ再生成に必要）
        public String world;
        public double x, y, z;
        public float yaw; // 本棚の向き再現用
        public Map<Integer, ShopItem> items = new HashMap<>();
    }

    public static class ShopItem {
        public ItemStack item;
        public long price;
        public ShopItem(ItemStack item, long price) { this.item = item; this.price = price; }
    }

    // --- 保存と読み込みのロジック ---

    public void saveShops() {
        File file = new File(plugin.getDataFolder(), "shops.yml");
        FileConfiguration config = new YamlConfiguration();

        // 1. ショップ本体の保存
        for (Map.Entry<UUID, PlayerShop> entry : activeShops.entrySet()) {
            String path = "shops." + entry.getKey().toString() + ".";
            PlayerShop shop = entry.getValue();
            config.set(path + "ownerUuid", shop.ownerUuid.toString());
            config.set(path + "ownerName", shop.ownerName);
            config.set(path + "shopName", shop.shopName);
            // ★追加: 座標を保存
            config.set(path + "world", shop.world);
            config.set(path + "x", shop.x);
            config.set(path + "y", shop.y);
            config.set(path + "z", shop.z);
            config.set(path + "yaw", shop.yaw);

            for (Map.Entry<Integer, ShopItem> itemEntry : shop.items.entrySet()) {
                config.set(path + "items." + itemEntry.getKey() + ".item", itemEntry.getValue().item);
                config.set(path + "items." + itemEntry.getKey() + ".price", itemEntry.getValue().price);
            }
        }

        // 2. 売上金と経験値の保存
        for (UUID uuid : pendingSales.keySet()) {
            config.set("pendingSales." + uuid.toString(), pendingSales.get(uuid));
        }
        for (UUID uuid : pendingExp.keySet()) {
            config.set("pendingExp." + uuid.toString(), pendingExp.get(uuid));
        }

        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadShops() {
        File file = new File(plugin.getDataFolder(), "shops.yml");
        if (!file.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        activeShops.clear();
        pendingSales.clear();
        pendingExp.clear();

        // 1. ショップ本体の読み込み
        if (config.getConfigurationSection("shops") != null) {
            for (String key : config.getConfigurationSection("shops").getKeys(false)) {
                UUID shopId = UUID.fromString(key);
                PlayerShop shop = new PlayerShop();
                shop.shopId = shopId;
                shop.ownerUuid = UUID.fromString(config.getString("shops." + key + ".ownerUuid"));
                shop.ownerName = config.getString("shops." + key + ".ownerName");
                shop.shopName = config.getString("shops." + key + ".shopName");
                // ★追加: 座標を読み込み
                shop.world = config.getString("shops." + key + ".world", "world");
                shop.x = config.getDouble("shops." + key + ".x", 0);
                shop.y = config.getDouble("shops." + key + ".y", 0);
                shop.z = config.getDouble("shops." + key + ".z", 0);
                shop.yaw = (float) config.getDouble("shops." + key + ".yaw", 0);

                if (config.getConfigurationSection("shops." + key + ".items") != null) {
                    for (String slotKey : config.getConfigurationSection("shops." + key + ".items").getKeys(false)) {
                        int slot = Integer.parseInt(slotKey);
                        ItemStack item = config.getItemStack("shops." + key + ".items." + slotKey + ".item");
                        long price = config.getLong("shops." + key + ".items." + slotKey + ".price");
                        shop.items.put(slot, new ShopItem(item, price));
                    }
                }
                activeShops.put(shopId, shop);
            }
        }

        // 2. 売上金と経験値の読み込み
        if (config.getConfigurationSection("pendingSales") != null) {
            for (String key : config.getConfigurationSection("pendingSales").getKeys(false)) {
                pendingSales.put(UUID.fromString(key), config.getLong("pendingSales." + key));
            }
        }
        if (config.getConfigurationSection("pendingExp") != null) {
            for (String key : config.getConfigurationSection("pendingExp").getKeys(false)) {
                pendingExp.put(UUID.fromString(key), config.getLong("pendingExp." + key));
            }
        }
    }

    public void addShop(UUID shopId, PlayerShop shop) {
        activeShops.put(shopId, shop);
        saveShops(); // ★変更時に保存
    }

    public PlayerShop getShop(UUID shopId) {
        return activeShops.get(shopId);
    }

    public void removeShop(UUID shopId) {
        activeShops.remove(shopId);
        saveShops(); // ★削除時に保存
    }


    /**
     * ★修正: サーバー起動時にYMLを正としてワールドエンティティを同期する。
     * Bukkit.getEntity()はアンロードチャンクを返さないため、
     * 全ワールドのエンティティをタグで総当たり検索する方式に変更。
     * UUIDが変わっている場合はショップ名で照合してYMLキーを修正する。
     */
    public void syncWithWorld() {
        int regenerated = 0;
        int removed = 0;
        int uuidFixed = 0;

        // ステップ0: ワールド上の全sao_player_shop Interactionを収集
        java.util.Map<java.util.UUID, org.bukkit.entity.Interaction> worldInteractions = new java.util.HashMap<>();
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (e instanceof org.bukkit.entity.Interaction
                        && e.getScoreboardTags().contains("sao_player_shop")) {
                    worldInteractions.put(e.getUniqueId(), (org.bukkit.entity.Interaction) e);
                }
            }
        }

        // ステップ1: YMLにあるがワールドにUUID一致エンティティがないショップを処理
        java.util.List<java.util.Map.Entry<java.util.UUID, PlayerShop>> entries =
                new java.util.ArrayList<>(activeShops.entrySet());

        for (java.util.Map.Entry<java.util.UUID, PlayerShop> entry : entries) {
            java.util.UUID shopId = entry.getKey();
            PlayerShop shop = entry.getValue();

            if (worldInteractions.containsKey(shopId)) continue; // UUID一致 → 正常

            // UUIDが一致しない → ショップ名でワールド側を照合
            org.bukkit.entity.Interaction matched = null;
            for (org.bukkit.entity.Interaction interaction : worldInteractions.values()) {
                if (activeShops.containsKey(interaction.getUniqueId())) continue;
                for (org.bukkit.entity.Entity nearby : interaction.getNearbyEntities(3, 3, 3)) {
                    if (!(nearby instanceof org.bukkit.entity.TextDisplay)) continue;
                    String displayText = org.bukkit.ChatColor.stripColor(
                            ((org.bukkit.entity.TextDisplay) nearby).getText());
                    String ymlName = org.bukkit.ChatColor.stripColor(
                            shop.shopName != null ? shop.shopName : "");
                    if (displayText.equals(ymlName)) { matched = interaction; break; }
                }
                if (matched != null) break;
            }

            if (matched != null) {
                // ショップ名一致 → YMLのキーをワールドのUUIDに修正
                java.util.UUID newId = matched.getUniqueId();
                activeShops.remove(shopId);
                shop.shopId = newId;
                activeShops.put(newId, shop);
                worldInteractions.put(newId, matched);
                plugin.getLogger().info("[PlayerShop] UUID修正: " + shop.shopName + " " + shopId + " -> " + newId);
                uuidFixed++;
                continue;
            }

            // 座標が未設定(0,0,0) → 再生成不可
            if (shop.world == null || shop.world.isEmpty()
                    || (shop.x == 0.0 && shop.y == 0.0 && shop.z == 0.0)) {
                plugin.getLogger().warning("[PlayerShop] 座標未設定のためスキップ: " + shop.shopName);
                continue;
            }

            // 座標あり → エンティティを再生成
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(shop.world);
            if (w == null) { plugin.getLogger().warning("[PlayerShop] ワールド不明: " + shop.shopName); continue; }
            org.bukkit.Location loc = new org.bukkit.Location(w, shop.x, shop.y, shop.z);

            org.bukkit.entity.BlockDisplay bd = (org.bukkit.entity.BlockDisplay)
                    w.spawnEntity(loc, org.bukkit.entity.EntityType.BLOCK_DISPLAY);
            bd.setBlock(org.bukkit.Bukkit.createBlockData(org.bukkit.Material.CHISELED_BOOKSHELF));
            bd.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(-0.5f, 0, -0.5f), new org.joml.AxisAngle4f(0, 0, 0, 0),
                    new org.joml.Vector3f(1, 1, 1), new org.joml.AxisAngle4f(0, 0, 0, 0)));
            bd.addScoreboardTag("sao_shop_furniture");

            org.bukkit.entity.TextDisplay td = (org.bukkit.entity.TextDisplay)
                    w.spawnEntity(loc.clone().add(0, 1.3, 0), org.bukkit.entity.EntityType.TEXT_DISPLAY);
            td.setText("§e" + shop.shopName);
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            td.addScoreboardTag("sao_shop_furniture");

            org.bukkit.entity.Interaction newInteraction = (org.bukkit.entity.Interaction)
                    w.spawnEntity(loc, org.bukkit.entity.EntityType.INTERACTION);
            newInteraction.setInteractionHeight(1.0f);
            newInteraction.setInteractionWidth(1.0f);
            newInteraction.setResponsive(true);
            newInteraction.addScoreboardTag("sao_player_shop");

            java.util.UUID newId = newInteraction.getUniqueId();
            activeShops.remove(shopId);
            shop.shopId = newId;
            activeShops.put(newId, shop);
            worldInteractions.put(newId, newInteraction);
            plugin.getLogger().info("[PlayerShop] 再生成: " + shop.shopName + " " + shopId + " -> " + newId);
            regenerated++;
        }

        // ステップ2: ワールド上にあるがYMLに対応レコードがない孤立エンティティを削除
        for (java.util.Map.Entry<java.util.UUID, org.bukkit.entity.Interaction> e : worldInteractions.entrySet()) {
            if (activeShops.containsKey(e.getKey())) continue;
            for (org.bukkit.entity.Entity nearby : e.getValue().getNearbyEntities(2, 2, 2)) {
                if (nearby.getScoreboardTags().contains("sao_shop_furniture")) nearby.remove();
            }
            e.getValue().remove();
            plugin.getLogger().info("[PlayerShop] 孤立エンティティ削除: " + e.getKey());
            removed++;
        }

        if (regenerated > 0 || removed > 0 || uuidFixed > 0) saveShops();
        plugin.getLogger().info("[PlayerShop] 同期完了: UUID修正=" + uuidFixed + " 再生成=" + regenerated + " 孤立削除=" + removed);
    }

    public void addPendingSale(UUID ownerUuid, long amount) {
        long currentCol = pendingSales.getOrDefault(ownerUuid, 0L);
        pendingSales.put(ownerUuid, currentCol + amount);

        long currentExp = pendingExp.getOrDefault(ownerUuid, 0L);
        pendingExp.put(ownerUuid, currentExp + amount);
        saveShops(); // ★売上発生時に保存
    }
}
