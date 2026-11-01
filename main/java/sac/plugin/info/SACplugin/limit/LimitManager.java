package sac.plugin.info.SACplugin.limit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;

public class LimitManager implements Reloadable {
    private final SACplugin plugin;
    private int maxPlayerLevel = 30;
    // ==========================================
    // ★追加: スキルスロットの上限数。初期2スロット+レベル10ごとに1追加だが、
    // 実装済みコンテンツの都合でここを上回っては増えないようにする(現在は3)。
    // ==========================================
    private int maxSkillSlots = 6;
    private JsonObject skillLimits = new JsonObject();

    public LimitManager(SACplugin plugin) {
        this.plugin = plugin;
        loadLimits();
    }

    public void loadLimits() {
        File file = new File(plugin.getDataFolder(), "level_limits.json");
        // ファイルがない場合は作成（内容は空のままになるので、事前に手動配置するかデフォルトを用意します）
        if (!file.exists()) {
            plugin.saveResource("level_limits.json", false);
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("max_player_level")) {
                maxPlayerLevel = json.get("max_player_level").getAsInt();
            }
            if (json.has("max_skill_slots")) {
                maxSkillSlots = json.get("max_skill_slots").getAsInt();
            }
            if (json.has("skill_limits")) {
                skillLimits = json.getAsJsonObject("skill_limits");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // プレイヤーの最大レベルを取得
    public int getMaxPlayerLevel() {
        return maxPlayerLevel;
    }

    // スキルスロットの上限数を取得
    public int getMaxSkillSlots() {
        return maxSkillSlots;
    }

    @Override
    public void reload() {
        loadLimits();
    }

    // 指定したスキルの上限を取得（JSONに無ければ999＝実質上限なし）
    public int getSkillLimit(String skillName) {
        if (skillLimits.has(skillName)) {
            return skillLimits.get(skillName).getAsInt();
        }
        return 999;
    }
}
