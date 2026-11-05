package sac.plugin.info.SACplugin.crystal;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CrystalManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<Integer, CrystalTpData> tpDataMap = new HashMap<>();

    // βテスト制限
    private int maxAccessibleFloor = 1;
    private String betaMessage = "§c未解放です";

    // ★ オート緊急離脱 ON/OFF (プレイヤーUUID → 有効かどうか)
    private final java.util.Set<java.util.UUID> autoEscapeEnabled = new java.util.HashSet<>();
    // ★ 緊急離脱の発動クールダウン (連続発動防止)
    private final java.util.Set<java.util.UUID> autoEscapeCooldown = new java.util.HashSet<>();

    public boolean isAutoEscapeEnabled(java.util.UUID uuid) {
        return autoEscapeEnabled.contains(uuid);
    }

    public void setAutoEscape(java.util.UUID uuid, boolean enabled) {
        if (enabled) autoEscapeEnabled.add(uuid);
        else autoEscapeEnabled.remove(uuid);
    }

    public boolean isAutoEscapeCooldown(java.util.UUID uuid) {
        return autoEscapeCooldown.contains(uuid);
    }

    public void startAutoEscapeCooldown(java.util.UUID uuid) {
        autoEscapeCooldown.add(uuid);
        // 5秒のクールダウン後に解除
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> autoEscapeCooldown.remove(uuid), 100L);
    }

    // ==========================================
    // ★追加: 転移結晶を1個使ったら5秒間、次の使用ができないようにするクールタイム。
    // (自動緊急離脱のクールダウンとは別に管理する)
    // ==========================================
    private final java.util.Set<java.util.UUID> tpCrystalCooldown = new java.util.HashSet<>();

    public boolean isTpCrystalCooldown(java.util.UUID uuid) {
        return tpCrystalCooldown.contains(uuid);
    }

    public void startTpCrystalCooldown(java.util.UUID uuid) {
        tpCrystalCooldown.add(uuid);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> tpCrystalCooldown.remove(uuid), 100L); // 5秒
    }

    public CrystalManager(SACplugin plugin) {
        this.plugin = plugin;
        loadTps();
        loadBetaConfig();
    }

    // ==========================================
    // β設定
    // ==========================================
    public void loadBetaConfig() {
        File file = new File(plugin.getDataFolder(), "beta_config.json");
        if (!file.exists()) plugin.saveResource("beta_config.json", false);
        try (FileReader reader = new FileReader(file)) {
            com.google.gson.JsonObject json = new Gson().fromJson(reader, com.google.gson.JsonObject.class);
            if (json.has("max_accessible_floor")) maxAccessibleFloor = json.get("max_accessible_floor").getAsInt();
            if (json.has("beta_message")) betaMessage = json.get("beta_message").getAsString();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public int getMaxAccessibleFloor() { return maxAccessibleFloor; }
    public String getBetaMessage() { return betaMessage; }

    @Override
    public void reload() {
        loadBetaConfig();
        loadTps();
    }

    // ==========================================
    // TP先データ
    // ==========================================
    public void loadTps() {
        File file = new File(plugin.getDataFolder(), "tps.json");
        if (!file.exists()) plugin.saveResource("tps.json", false);
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, CrystalTpData>>(){}.getType();
            Map<String, CrystalTpData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                tpDataMap.clear();
                for (Map.Entry<String, CrystalTpData> entry : loaded.entrySet()) {
                    try {
                        int floor = Integer.parseInt(entry.getKey());
                        entry.getValue().floor = floor;
                        tpDataMap.put(floor, entry.getValue());
                    } catch (NumberFormatException ignored) {}
                }
                plugin.getLogger().info("転移先(TP)データを " + tpDataMap.size() + " 件読み込みました。");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public Location getTpLocation(int floor) {
        CrystalTpData data = tpDataMap.get(floor);
        if (data == null) return null;
        org.bukkit.World w = Bukkit.getWorld(data.world);
        if (w == null) return null;
        return new Location(w, data.x, data.y, data.z, data.yaw, data.pitch);
    }

    /**
     * ★追加: 全ての転移門(街)の座標一覧を返す。
     * SurvivalManager(満腹度システム)の「転移門周辺でHP回復」判定に使用する。
     */
    public java.util.Collection<Location> getAllTpLocations() {
        java.util.List<Location> list = new java.util.ArrayList<>();
        for (CrystalTpData data : tpDataMap.values()) {
            org.bukkit.World w = Bukkit.getWorld(data.world);
            if (w == null) continue;
            list.add(new Location(w, data.x, data.y, data.z));
        }
        return list;
    }

    
    public String getFloorName(int floor) {
        CrystalTpData data = tpDataMap.get(floor);
        return data != null ? data.name : "未解放";
    }

    // ==========================================
    // ★ プレイヤー個別の討伐チェック
    // ==========================================

    /**
     * プレイヤーがその層に転移できるか判定。
     * - OPはボス討伐条件をスキップ
     * - 一般プレイヤーは自分の defeatedBosses リストで判定
     */
    public boolean canTeleport(Player player, int floor) {
        CrystalTpData data = tpDataMap.get(floor);
        if (data == null) return false;
        if (floor > maxAccessibleFloor) return false;

        // bossId が null なら討伐条件なし（第1層など）
        if (data.bossId == null) return true;

        // OPはスキップ
        if (player.isOp()) return true;

        // プレイヤー個別データで確認
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return false;
        return stats.defeatedBosses.contains(data.bossId);
    }

    /**
     * テレポート不可の理由メッセージ
     */
    public String getTeleportBlockReason(Player player, int floor) {
        CrystalTpData data = tpDataMap.get(floor);
        if (data == null) return "§c転移先のデータが存在しません。";
        if (floor > maxAccessibleFloor) return betaMessage;
        if (data.bossId != null) {
            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats == null || !stats.defeatedBosses.contains(data.bossId)) {
                return "§cフロアボス §e[" + data.bossId + "] §cを自分で討伐しないとこの層には転移できません";
            }
        }
        return "§c転移できません。";
    }

    /**
     * 層が「解放済み」かどうか（GUI表示用 / プレイヤー個別）
     */
    public boolean isFloorUnlocked(Player player, int floor) {
        return canTeleport(player, floor);
    }

    /**
     * ★ プレイヤー個別にボス討伐を記録する
     * SAOMobListener から呼び出す
     */
    public void recordBossDefeat(Player player, String bossId) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;
        if (!stats.defeatedBosses.contains(bossId)) {
            stats.defeatedBosses.add(bossId);
            plugin.getPlayerManager().savePlayerData(player, stats);
            player.sendMessage("§b[FLOOR CLEAR] §fボス §e" + bossId + " §fを討伐記録に登録しました");
        }
    }
}