package sac.plugin.info.SACplugin.Tutorial;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tutorial.jsonを読み込む。
 * 構造: { "tutorialId": [ {step}, {step}, ... ], "別のtutorialId": [...] }
 * 各チュートリアルIDの中身は配列なので、順番が保証される。
 */
public class TutorialJsonManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, List<TutorialStepData>> tutorials = new HashMap<>();

    public TutorialJsonManager(SACplugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "tutorial.json");
        if (!file.exists()) plugin.saveResource("tutorial.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, List<TutorialStepData>>>() {}.getType();
            Map<String, List<TutorialStepData>> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                tutorials.clear();
                tutorials.putAll(loaded);
            }
            plugin.getLogger().info("[Tutorial] tutorial.jsonを " + tutorials.size() + " 件読み込みました。");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reload() {
        load();
    }

    public List<TutorialStepData> getSteps(String tutorialId) {
        return tutorials.get(tutorialId);
    }

    public boolean exists(String tutorialId) {
        return tutorials.containsKey(tutorialId) && !tutorials.get(tutorialId).isEmpty();
    }
}
