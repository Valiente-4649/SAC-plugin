package sac.plugin.info.SACplugin.crystal;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TpGateManager implements Reloadable {
    private final SACplugin plugin;

    // Key: アーマースタンドのUUID文字列 → TpGateData
    private final Map<String, TpGateData> gates = new HashMap<>();

    public static class TpGateData {
        public String standUuid;   // このゲートのアーマースタンドUUID
        public String entryName;   // 入り口テキスト (例: "始まりの街へ")
        public String exitName;    // 出口テキスト  (例: "第一層草原フィールドへ")
        public String worldName;
        public double x, y, z;
        public float yaw, pitch;
    }

    public TpGateManager(SACplugin plugin) {
        this.plugin = plugin;
        loadGates();
    }

    // ==========================================
    // 保存・読み込み
    // ==========================================
    public void saveGates() {
        File file = new File(plugin.getDataFolder(), "tp_gates.json");
        try (FileWriter writer = new FileWriter(file)) {
            new Gson().toJson(gates, writer);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void loadGates() {
        File file = new File(plugin.getDataFolder(), "tp_gates.json");
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, TpGateData>>(){}.getType();
            Map<String, TpGateData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) gates.putAll(loaded);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void reload() {
        gates.clear();
        loadGates();
    }

    public void addGate(TpGateData data) {
        gates.put(data.standUuid, data);
        saveGates();
    }

    public void removeGate(String standUuid) {
        gates.remove(standUuid);
        saveGates();
    }

    public Collection<TpGateData> getAllGates() {
        return gates.values();
    }

    public TpGateData getGate(String standUuid) {
        return gates.get(standUuid);
    }

    // ==========================================
    // ペアゲートを探す
    // entryName == 相手のexitName かつ exitName == 相手のentryName
    // ==========================================
    public TpGateData findPair(TpGateData gate) {
        for (TpGateData other : gates.values()) {
            if (other.standUuid.equals(gate.standUuid)) continue;
            if (other.entryName.equals(gate.exitName) && other.exitName.equals(gate.entryName)) {
                return other;
            }
        }
        return null;
    }

    // ==========================================
    // プレイヤーの位置に最も近いゲートを返す (横3ブロック以内)
    // ==========================================
    public TpGateData findNearbyGate(Location loc) {
        for (TpGateData gate : gates.values()) {
            org.bukkit.World w = Bukkit.getWorld(gate.worldName);
            if (w == null || !w.equals(loc.getWorld())) continue;
            double dx = Math.abs(gate.x - loc.getX());
            double dz = Math.abs(gate.z - loc.getZ());
            double dy = Math.abs(gate.y - loc.getY());
            // 横3ブロック以内、縦2ブロック以内
            if (dx <= 3 && dz <= 3 && dy <= 2) return gate;
        }
        return null;
    }

    // ==========================================
    // テレポート先の Location を生成
    // ゲートの正面2マスの位置
    // ==========================================
    public Location getArrivalLocation(TpGateData gate) {
        org.bukkit.World w = Bukkit.getWorld(gate.worldName);
        if (w == null) return null;

        // ゲートの向き(yaw)から正面2マス先を計算
        double rad = Math.toRadians(gate.yaw);
        // Minecraftの座標系: yaw=0→南(+Z), yaw=90→西(-X), yaw=180→北(-Z), yaw=270→東(+X)
        double dz = Math.cos(rad) * 2;
        double dx = -Math.sin(rad) * 2;

        Location arrive = new Location(w, gate.x + dx, gate.y, gate.z + dz, gate.yaw + 180, gate.pitch);
        return arrive;
    }
}
