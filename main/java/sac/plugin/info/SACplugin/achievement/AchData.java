package sac.plugin.info.SACplugin.achievement;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 1人分の実績データ(進捗カウンタ + 獲得済み実績 + 装備中の称号)。
 * 進捗カウンタは「実績の定義」とは独立して保存しているので、実績の条件が変わって
 * 取り消されても、カウンタはそのまま新しい条件の判定に引き継がれる。
 */
public class AchData {

    /** 獲得済み実績1件。sigは獲得時点の「条件の署名」。現在の定義の署名と違えば取り消し対象。 */
    public static class Grant {
        public final String sig;
        public final long at;
        public Grant(String sig, long at) { this.sig = sig; this.at = at; }
    }

    public final UUID uuid;
    public String lastName = "";

    // ---- 進捗カウンタ ----
    public final Map<String, Integer> kills = new HashMap<>();        // mobId -> 討伐数
    public final Map<String, Long> bossKills = new HashMap<>();      // bossId -> 実績システム導入後の初討伐時刻(ms)
    public final Set<String> soloBosses = new HashSet<>();            // 一人で討伐したbossId
    public final Set<String> treasures = new HashSet<>();             // 開封した宝箱ID(宝箱側の履歴リセットの影響を受けない)
    public final Set<String> cooked = new HashSet<>();                // 調理に成功したrecipeId
    public final Map<String, Integer> bigCatch = new HashMap<>();     // ランク(B/A/S..) -> 大物を釣った数
    public long commerceSales = 0L;                                   // プレイヤーショップの累計売上(Col)
    public final Set<String> bestCrops = new HashSet<>();             // 最高品質で収穫した作物(種ID)
    public final Set<String> miracles = new HashSet<>();              // 奇跡的成功を出した生産物("w:"+武器ID / "a:"+防具ID)
    public final Map<String, Long> firstUse = new HashMap<>();        // スキル名 -> 初めてそのスキルの使用が確認された時刻(ms)

    // ---- 獲得状況 ----
    public final Map<String, Grant> granted = new LinkedHashMap<>();  // NORMAL / EXTRA のみ(UNIQUEは保持者ファイル側が正)
    public String equipped = null;                                    // 装備中の実績ID

    /** 未保存の変更があるか */
    public boolean dirty = false;

    public AchData(UUID uuid) { this.uuid = uuid; }

    // ==========================================
    // 読み込み / 保存
    // ==========================================
    public static AchData load(File file, UUID uuid) {
        AchData d = new AchData(uuid);
        if (!file.exists()) return d;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        d.lastName = c.getString("name", "");
        readIntMap(c.getConfigurationSection("kills"), d.kills);
        readIntMap(c.getConfigurationSection("bigCatch"), d.bigCatch);
        ConfigurationSection bk = c.getConfigurationSection("bossKills");
        if (bk != null) for (String k : bk.getKeys(false)) d.bossKills.put(k, bk.getLong(k));
        ConfigurationSection fu = c.getConfigurationSection("firstUse");
        if (fu != null) for (String k : fu.getKeys(false)) d.firstUse.put(k, fu.getLong(k));
        d.soloBosses.addAll(c.getStringList("soloBosses"));
        d.treasures.addAll(c.getStringList("treasures"));
        d.cooked.addAll(c.getStringList("cooked"));
        d.bestCrops.addAll(c.getStringList("bestCrops"));
        d.miracles.addAll(c.getStringList("miracles"));
        d.commerceSales = c.getLong("commerceSales", 0L);
        ConfigurationSection g = c.getConfigurationSection("granted");
        if (g != null) {
            for (String id : g.getKeys(false)) {
                d.granted.put(id, new Grant(g.getString(id + ".sig", ""), g.getLong(id + ".at", 0L)));
            }
        }
        d.equipped = c.getString("equipped", null);
        if (d.equipped != null && d.equipped.isEmpty()) d.equipped = null;
        return d;
    }

    private static void readIntMap(ConfigurationSection s, Map<String, Integer> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getInt(k));
    }

    public void save(File file) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("name", lastName);
        for (Map.Entry<String, Integer> e : kills.entrySet()) c.set("kills." + e.getKey(), e.getValue());
        for (Map.Entry<String, Integer> e : bigCatch.entrySet()) c.set("bigCatch." + e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : bossKills.entrySet()) c.set("bossKills." + e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : firstUse.entrySet()) c.set("firstUse." + e.getKey(), e.getValue());
        c.set("soloBosses", new ArrayList<>(soloBosses));
        c.set("treasures", new ArrayList<>(treasures));
        c.set("cooked", new ArrayList<>(cooked));
        c.set("bestCrops", new ArrayList<>(bestCrops));
        c.set("miracles", new ArrayList<>(miracles));
        c.set("commerceSales", commerceSales);
        for (Map.Entry<String, Grant> e : granted.entrySet()) {
            c.set("granted." + e.getKey() + ".sig", e.getValue().sig);
            c.set("granted." + e.getKey() + ".at", e.getValue().at);
        }
        c.set("equipped", equipped == null ? "" : equipped);
        writeAtomically(c, file);
        dirty = false;
    }

    /** 途中で落ちてもファイルが壊れないよう、一時ファイルに書いてから置き換える */
    static void writeAtomically(YamlConfiguration c, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            c.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
