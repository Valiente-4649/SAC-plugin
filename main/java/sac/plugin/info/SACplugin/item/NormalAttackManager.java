package sac.plugin.info.SACplugin.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.skill.SwordSkillExecutor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * weapon_types.json の各武器種にある "normalAttack" を読み込み、通常攻撃の段階設定として保持する。
 * WeaponTypeManager とは独立して同じJSONを読むだけなので、WeaponTypeManager 側の変更は不要。
 *
 * JSON例:
 * "normalAttack": {
 *   "comboResetTicks": 30,
 *   "stages": [
 *     { "depth": 3.5, "width": 3.0, "position": "HORIZONTAL_MIDDLE_RIGHT" },
 *     { "depth": 4.0, "width": 1.0, "position": "THRUST_", "height": 2.0, "visualScale": 1.0 }
 *   ]
 * }
 */
public class NormalAttackManager {

    /** 通常攻撃1段分 */
    public static final class Stage {
        /** 剣筋の向き or 突き or NONE（見た目なし）。日本語名・英語名どちらで書いても内部では英語名にそろえて保持する */
        public final String position;
        /** 奥行きリーチ（前方への届く距離） */
        public final double depth;
        /** 横方向リーチ（左右合計の幅） */
        public final double width;
        /** 判定の高さ（省略時 2.0） */
        public final double height;
        /** 見た目だけの倍率（省略時 1.0） */
        public final double visualScale;

        Stage(String position, double depth, double width, double height, double visualScale) {
            this.position = position;
            this.depth = depth;
            this.width = width;
            this.height = height;
            this.visualScale = visualScale;
        }
    }

    /** 武器種ごとの通常攻撃設定 */
    public static final class Config {
        /** 最後の攻撃からこのtick数が空くと1段目に戻る */
        public final int comboResetTicks;
        public final List<Stage> stages;
        /** これより短い間隔では通常攻撃できない(ms)。maxSpeedBpm から 60000/BPM で算出。0なら制限なし */
        public final long minIntervalMs;

        Config(int comboResetTicks, List<Stage> stages, long minIntervalMs) {
            this.comboResetTicks = comboResetTicks;
            this.stages = Collections.unmodifiableList(stages);
            this.minIntervalMs = minIntervalMs;
        }
    }

    private static final String FILE_NAME = "weapon_types.json";

    private final SACplugin plugin;
    private final Map<String, Config> configs = new HashMap<>();

    public NormalAttackManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** 武器種の通常攻撃設定。未設定の武器種は null（＝通常攻撃なし） */
    public Config get(String category) {
        return category == null ? null : configs.get(category);
    }

    /** JSONを読み込み直す（起動時・リロード時に呼ぶ） */
    public void load() {
        configs.clear();
        // 読み込み元を探す: ①データフォルダ直下 ②データフォルダ内のサブフォルダ ③jar内のリソース
        InputStream in = null;
        String source = null;
        try {
            File file = new File(plugin.getDataFolder(), FILE_NAME);
            if (!file.exists()) file = findInDataFolder();
            if (file != null && file.exists()) {
                in = new FileInputStream(file);
                source = file.getPath();
            } else {
                in = plugin.getResource(FILE_NAME);
                if (in != null) source = "jar内リソース(" + FILE_NAME + ")";
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[NormalAttack] " + FILE_NAME + " を開けませんでした: " + ex.getMessage());
        }
        if (in == null) {
            plugin.getLogger().warning("[NormalAttack] " + FILE_NAME + " が見つかりません（データフォルダにもjar内にもありません）");
            return;
        }
        plugin.getLogger().info("[NormalAttack] 読み込み元: " + source);

        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                plugin.getLogger().warning("[NormalAttack] " + FILE_NAME + " のルートが配列ではありません");
                return;
            }
            JsonArray array = root.getAsJsonArray();
            for (JsonElement el : array) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("category") || !obj.has("normalAttack") || !obj.get("normalAttack").isJsonObject()) continue;

                String category = obj.get("category").getAsString();
                double bpm = num(obj, "maxSpeedBpm", 0.0);
                Config config = parseConfig(category, obj.getAsJsonObject("normalAttack"), bpm);
                if (config != null) configs.put(category, config);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[NormalAttack] " + FILE_NAME + " の読み込みに失敗しました: " + ex.getMessage());
        }
        if (configs.isEmpty()) {
            plugin.getLogger().warning("[NormalAttack] normalAttack が1つも読み込めませんでした。"
                    + source + " に \"normalAttack\" が入っているか確認してください（通常攻撃が発動しません）");
        } else {
            plugin.getLogger().info("[NormalAttack] " + configs.size() + " 武器種の通常攻撃設定を読み込みました");
        }
    }

    /** データフォルダ配下（3階層まで）から weapon_types.json を探す */
    private File findInDataFolder() {
        Path root = plugin.getDataFolder().toPath();
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(FILE_NAME))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private Config parseConfig(String category, JsonObject na, double maxSpeedBpm) {
        if (!na.has("stages") || !na.get("stages").isJsonArray()) {
            plugin.getLogger().warning("[NormalAttack] " + category + ": stages がありません");
            return null;
        }

        List<Stage> stages = new ArrayList<>();
        int index = 0;
        for (JsonElement el : na.getAsJsonArray("stages")) {
            index++;
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();

            String position = SwordSkillExecutor.normalizePosition(str(s, "position", "NONE"));
            if (position.isEmpty()) position = "NONE";
            double depth = num(s, "depth", 3.0);
            double width = num(s, "width", 2.0);
            double height = num(s, "height", 2.0);
            double visualScale = num(s, "visualScale", 1.0);

            if (depth <= 0 || width <= 0 || height <= 0) {
                plugin.getLogger().warning("[NormalAttack] " + category + " の" + index + "段目: depth/width/height は0より大きくしてください。この段をスキップします");
                continue;
            }
            if (!SwordSkillExecutor.isValidEffectPosition(position)) {
                plugin.getLogger().warning("[NormalAttack] " + category + " の" + index + "段目: 未知の position \"" + position + "\"。見た目なしで動作します");
                position = "NONE";
            }
            stages.add(new Stage(position, depth, width, height, visualScale));
        }

        if (stages.isEmpty()) {
            plugin.getLogger().warning("[NormalAttack] " + category + ": 有効な段が1つもありません");
            return null;
        }
        int resetTicks = (int) Math.max(1, num(na, "comboResetTicks", 30));
        // 最短間隔: normalAttack.minIntervalTicks があればそれ、なければ maxSpeedBpm（1分あたりの最大攻撃回数）から算出
        long minIntervalMs = na.has("minIntervalTicks")
                ? (long) (num(na, "minIntervalTicks", 0.0) * 50L)
                : (maxSpeedBpm > 0 ? (long) (60000.0 / maxSpeedBpm) : 0L);
        return new Config(resetTicks, stages, Math.max(0L, minIntervalMs));
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static double num(JsonObject o, String key, double def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : def;
        } catch (Exception ex) {
            return def;
        }
    }
}