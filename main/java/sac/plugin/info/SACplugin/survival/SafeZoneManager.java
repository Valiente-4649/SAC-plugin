package sac.plugin.info.SACplugin.survival;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★新規: OPが/safezoneコマンドで自由に設置できる「セーフゾーン」を管理する。
 * 街の転移門(CrystalManager)とは別に、任意の場所にHP自動回復地点を追加できる。
 * safezones.yml に1ファイルで全件保存する(数が少なく軽量なため)。
 */
public class SafeZoneManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, SafeZoneData> zones = new HashMap<>();
    private final File file;

    public SafeZoneManager(SACplugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "safezones.yml");
        load();
    }

    public void load() {
        zones.clear();
        if (!file.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (config.getConfigurationSection("zones") == null) return;

        for (String id : config.getConfigurationSection("zones").getKeys(false)) {
            String base = "zones." + id;
            SafeZoneData data = new SafeZoneData();
            data.id = id;
            data.name = config.getString(base + ".name", id);
            data.world = config.getString(base + ".world", "world");
            data.x = config.getDouble(base + ".x", 0);
            data.y = config.getDouble(base + ".y", 0);
            data.z = config.getDouble(base + ".z", 0);
            data.radius = config.getDouble(base + ".radius", 20.0);
            zones.put(id, data);
        }
        plugin.getLogger().info("[SafeZone] セーフゾーンを " + zones.size() + " 件読み込みました。");
    }

    @Override
    public void reload() {
        load();
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        for (SafeZoneData data : zones.values()) {
            String base = "zones." + data.id;
            config.set(base + ".name", data.name);
            config.set(base + ".world", data.world);
            config.set(base + ".x", data.x);
            config.set(base + ".y", data.y);
            config.set(base + ".z", data.z);
            config.set(base + ".radius", data.radius);
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("[SafeZone] 保存に失敗しました: " + e.getMessage());
        }
    }

    /** 新しいセーフゾーンを登録し、生成したIDを返す */
    public String createZone(String name, Location loc, double radius) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        SafeZoneData data = new SafeZoneData();
        data.id = id;
        data.name = name;
        data.world = loc.getWorld().getName();
        data.x = loc.getX();
        data.y = loc.getY();
        data.z = loc.getZ();
        data.radius = radius;
        zones.put(id, data);
        save();
        return id;
    }

    public void removeZone(String id) {
        if (zones.remove(id) != null) {
            save();
        }
    }

    public Collection<SafeZoneData> getAllZones() {
        return zones.values();
    }
}
