package sac.plugin.info.SACplugin.skill;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class SwordSkillManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, SwordSkillData> skillMap = new HashMap<>();

    public SwordSkillManager(SACplugin plugin) {
        this.plugin = plugin;
        loadSkills();
    }

    public void loadSkills() {
        File file = new File(plugin.getDataFolder(), "sword_skills.json");
        if (!file.exists()) {
            plugin.saveResource("sword_skills.json", false);
        }

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SwordSkillData>>(){}.getType();
            Map<String, SwordSkillData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                skillMap.clear();
                skillMap.putAll(loaded);
                plugin.getLogger().info("SAOソードスキルを " + skillMap.size() + " 個読み込みました。");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public SwordSkillData getSkill(String id) {
        return skillMap.get(id);
    }

    public Map<String, SwordSkillData> getAllSkills() {
        return skillMap;
    }

    @Override
    public void reload() {
        loadSkills();
    }

    public void resetCooldown(org.bukkit.entity.Player player) {
        plugin.getSkillListener().resetCooldown(player);
    }
}
