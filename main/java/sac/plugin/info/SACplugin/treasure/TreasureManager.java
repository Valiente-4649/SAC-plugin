package sac.plugin.info.SACplugin.treasure;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * 宝箱データを管理する。
 * ★軽量化方針:
 *   1宝箱 = 1ファイル(treasures/<座標ID>.yml) として保存する。
 *   起動時に全ファイルを一括ロードしてメモリ上に保持し、
 *   変更があった宝箱だけをその都度ファイルへ書き戻すことで、
 *   宝箱が大量に増えても「1ファイルへの全件書き込み」のような重い処理を避ける。
 */
public class TreasureManager {
    private final SACplugin plugin;
    private final Map<String, TreasureData> treasures = new HashMap<>();
    // 宝箱ID → 中身のインベントリ(1行=9スロット)
    private final Map<String, Inventory> treasureInventories = new HashMap<>();
    private final File treasureFolder;

    public TreasureManager(SACplugin plugin) {
        this.plugin = plugin;
        this.treasureFolder = new File(plugin.getDataFolder(), "treasures");
        if (!treasureFolder.exists()) treasureFolder.mkdirs();
        loadAll();
    }

    /** 座標からID文字列を生成する（農業システムと同じ方式） */
    public static String toTreasureId(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private File fileFor(String id) {
        // ファイル名にコロンは使えないため置換
        String safeName = id.replace(":", "_") + ".yml";
        return new File(treasureFolder, safeName);
    }

    /** 起動時に全宝箱ファイルを読み込む */
    private void loadAll() {
        File[] files = treasureFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                TreasureData data = new TreasureData();
                data.id = config.getString("id", "");
                data.name = config.getString("name", "宝箱");
                data.rewardCol = config.getLong("rewardCol", 0);
                data.rewardExp = config.getInt("rewardExp", 0);
                data.world = config.getString("world", "world");
                data.x = config.getDouble("x", 0);
                data.y = config.getDouble("y", 0);
                data.z = config.getDouble("z", 0);
                data.yaw = (float) config.getDouble("yaw", 0);
                data.openedBy = config.getStringList("openedBy");

                if (data.id == null || data.id.isEmpty()) continue;

                treasures.put(data.id, data);

                // 中身（1行9スロットのインベントリ）を復元
                Inventory inv = Bukkit.createInventory(null, 9, "treasure_storage");
                java.util.List<?> items = config.getList("items");
                if (items != null) {
                    for (int i = 0; i < Math.min(items.size(), 9); i++) {
                        Object obj = items.get(i);
                        if (obj instanceof ItemStack) {
                            inv.setItem(i, (ItemStack) obj);
                        }
                    }
                }
                treasureInventories.put(data.id, inv);

            } catch (Exception e) {
                plugin.getLogger().warning("[Treasure] " + file.getName() + " の読み込みに失敗しました: " + e.getMessage());
            }
        }
        plugin.getLogger().info("[Treasure] 宝箱データを " + treasures.size() + " 件読み込みました。");
    }

    /** 指定した宝箱だけをファイルに保存する（軽量・差分書き込み） */
    public void save(String id) {
        TreasureData data = treasures.get(id);
        if (data == null) return;

        FileConfiguration config = new YamlConfiguration();
        config.set("id", data.id);
        config.set("name", data.name);
        config.set("rewardCol", data.rewardCol);
        config.set("rewardExp", data.rewardExp);
        config.set("world", data.world);
        config.set("x", data.x);
        config.set("y", data.y);
        config.set("z", data.z);
        config.set("yaw", data.yaw);
        config.set("openedBy", data.openedBy);

        Inventory inv = treasureInventories.get(id);
        if (inv != null) {
            config.set("items", java.util.Arrays.asList(inv.getContents()));
        }

        try {
            config.save(fileFor(id));
        } catch (IOException e) {
            plugin.getLogger().severe("[Treasure] " + id + " の保存に失敗しました: " + e.getMessage());
        }
    }

    /** 新しい宝箱を登録する */
    public void createTreasure(String id, String name, long rewardCol, int rewardExp, Location loc) {
        TreasureData data = new TreasureData();
        data.id = id;
        data.name = name;
        data.rewardCol = rewardCol;
        data.rewardExp = rewardExp;
        data.world = loc.getWorld().getName();
        data.x = loc.getX();
        data.y = loc.getY();
        data.z = loc.getZ();
        data.yaw = loc.getYaw();

        treasures.put(id, data);
        treasureInventories.put(id, Bukkit.createInventory(null, 9, "treasure_storage"));
        save(id);
    }

    public void removeTreasure(String id) {
        treasures.remove(id);
        treasureInventories.remove(id);
        File f = fileFor(id);
        if (f.exists()) f.delete();
    }

    public TreasureData getTreasure(String id) {
        return treasures.get(id);
    }

    public Inventory getInventory(String id) {
        return treasureInventories.get(id);
    }

    public Map<String, TreasureData> getAllTreasures() {
        return treasures;
    }

    /** 指定プレイヤーが既に開封済みかどうか */
    public boolean hasOpened(String treasureId, UUID playerUuid) {
        TreasureData data = treasures.get(treasureId);
        if (data == null) return false;
        return data.openedBy.contains(playerUuid.toString());
    }

    /** 開封済みとして記録し保存する */
    public void markOpened(String treasureId, UUID playerUuid) {
        TreasureData data = treasures.get(treasureId);
        if (data == null) return;
        if (!data.openedBy.contains(playerUuid.toString())) {
            data.openedBy.add(playerUuid.toString());
            save(treasureId);
        }
    }

    /**
     * ★追加: 全宝箱の開封履歴を一括リセットする（/treasureresetall で使用）。
     * 変更があった宝箱だけをファイルに書き直す。
     * @return リセットした宝箱の件数
     */
    public int resetAllOpened() {
        int count = 0;
        for (TreasureData data : treasures.values()) {
            if (!data.openedBy.isEmpty()) {
                data.openedBy.clear();
                save(data.id);
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info("[Treasure] 全宝箱(" + count + "件)の開封履歴をリセットしました。");
        }
        return count;
    }

    /**
     * ★追加: 指定プレイヤーの全宝箱開封履歴をリセットする（死亡時に呼ぶ）。
     * 変更があった宝箱だけをファイルに書き直す。
     */
    public void resetOpenedByPlayer(UUID playerUuid) {
        String uuidStr = playerUuid.toString();
        int count = 0;
        for (TreasureData data : treasures.values()) {
            if (data.openedBy.remove(uuidStr)) {
                save(data.id);
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info("[Treasure] " + uuidStr + " の開封履歴を " + count + " 件リセットしました。");
        }
    }
}