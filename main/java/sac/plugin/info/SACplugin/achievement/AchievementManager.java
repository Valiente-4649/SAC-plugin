package sac.plugin.info.SACplugin.achievement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.cooking.CookingRecipe;
import sac.plugin.info.SACplugin.farming.SAOFarmManager;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.item.WeaponTypeData;
import sac.plugin.info.SACplugin.Smithing.ArmorForgeRecipe;
import sac.plugin.info.SACplugin.Smithing.ForgeManager;
import sac.plugin.info.SACplugin.Smithing.ForgeRecipe;
import sac.plugin.info.SACplugin.treasure.TreasureData;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 実績システム本体。
 *
 * ■ 実績の定義は「データから毎回組み立てる」
 *   階層・モブ・宝箱・クエスト・ボス・料理・作物・鍛造レシピが増えると、定義(と署名)が自動で変わる。
 *   → 署名が変わった実績は取り消され、進捗(AchDataのカウンタ)は残ったまま新しい条件で再判定される。
 *
 * ■ ユニーク実績
 *   保持者はachievements/_unique.ymlが唯一の正。判定はメインスレッドでしか行わず、
 *   「保持者がいない」ことを確認して同じ処理内で書き込むので、同時に2人へ付与されることはない。
 *   導入前の履歴は使わず(bossKills等は導入後の記録のみ)、導入後に条件を満たした最初の1人が獲得する。
 */
public class AchievementManager implements Listener, Reloadable {

    // ==========================================
    // ★調整用の定数
    // ==========================================
    /** 各モブの討伐目標数(ノーマル: 第N層モブ討伐) */
    public static final int MOB_KILL_TARGET = 30;
    /** 「○○スキルの使い手」の目標熟練度(スキルの上限がこれ未満ならその上限) */
    public static final int SKILL_USER_LEVEL = 10;
    /** 特級冒険者: 各クエストのクリア回数 */
    public static final int GRAND_QUEST_CLEARS = 3;
    /** 釣り師系: 大物の匹数 */
    public static final int BIG_FISH_TARGET = 20;
    /** 大手会社社長: 累計売上(Col) */
    public static final long SALES_TARGET = 100_000L;
    /** trueなら「解放済みの階層(CrystalManagerのmax_accessible_floor以下)」の実績だけを作る */
    public static final boolean ONLY_ACCESSIBLE_FLOORS = true;
    /** モブ討伐実績にMINION(ボスの取り巻き)を含めるか */
    public static final boolean INCLUDE_MINIONS = false;

    private final SACplugin plugin;
    private final File dir;
    private final File uniqueFile;

    private final Map<UUID, AchData> cache = new HashMap<>();
    private volatile List<AchievementDef> defs = new ArrayList<>();
    private final Map<String, AchievementDef> defById = new LinkedHashMap<>();

    // ---- ユニーク保持者 ----
    public static class Holder {
        public final UUID uuid; public final String name; public final String sig; public final long at;
        Holder(UUID uuid, String name, String sig, long at) { this.uuid = uuid; this.name = name; this.sig = sig; this.at = at; }
    }
    private final Map<String, Holder> holders = new LinkedHashMap<>();

    // ---- 静的データ(JSON由来) ----
    private final List<String> mobOrder = new ArrayList<>();
    private final Map<String, String> mobCategory = new HashMap<>();
    private final Map<String, Integer> mobFloor = new HashMap<>();
    private final Set<String> rareMobs = new HashSet<>();
    private final Map<String, String> bossWorld = new HashMap<>();
    private final TreeMap<Integer, String[]> tps = new TreeMap<>(); // floor -> {world, bossId}
    private final Map<String, Integer> worldFloor = new HashMap<>();
    private final Map<String, Integer> questFloor = new LinkedHashMap<>();

    private int tick = 0;

    public AchievementManager(SACplugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "achievements");
        this.uniqueFile = new File(dir, "_unique.yml");
        if (!dir.exists()) dir.mkdirs();
        loadHolders();
        loadContent();
        rebuildDefs();
    }

    /** 周期処理を開始する(onEnableの最後に呼ぶ) */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::periodic, 100L, 20L);
    }

    /** サーバー停止時に全員分を保存する */
    public void shutdown() {
        for (AchData d : cache.values()) saveNow(d);
    }

    @Override
    public void reload() {
        loadContent();
        rebuildDefs();
        for (Player p : Bukkit.getOnlinePlayers()) evaluate(p);
    }

    @Override
    public String getReloadName() { return "AchievementManager"; }

    // ==========================================
    // ★ 静的データの読み込み
    // ==========================================
    private JsonElement readJson(String name) throws Exception {
        File f = new File(plugin.getDataFolder(), name);
        String text;
        if (f.exists()) {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } else {
            try (InputStream is = plugin.getResource(name)) {
                if (is == null) throw new IllegalStateException(name + " が見つかりません");
                text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return JsonParser.parseString(cleanJson(text));
    }

    // 文字列の外にある行コメント・ブロックコメントと、閉じ括弧の直前の余分なカンマを取り除く(quests.json等がこの書き方をしているため)
    static String cleanJson(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false, escape = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; out.append(c); continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                if (i < n) out.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (Character.isWhitespace(d)) { j++; continue; }
                    if (d == '/' && j + 1 < n && text.charAt(j + 1) == '/') { while (j < n && text.charAt(j) != '\n') j++; continue; }
                    if (d == '/' && j + 1 < n && text.charAt(j + 1) == '*') { j += 2; while (j + 1 < n && !(text.charAt(j) == '*' && text.charAt(j + 1) == '/')) j++; j += 2; continue; }
                    break;
                }
                if (j < n && (text.charAt(j) == '}' || text.charAt(j) == ']')) continue;   // 末尾カンマは捨てる
            }
            out.append(c);
        }
        return out.toString();
    }

    private void loadContent() {
        try {
            // --- tps.json: 階層 -> {ワールド, その階層に入る条件のボス} ---
            TreeMap<Integer, String[]> newTps = new TreeMap<>();
            JsonObject tj = readJson("tps.json").getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : tj.entrySet()) {
                int floor;
                try { floor = Integer.parseInt(e.getKey()); } catch (NumberFormatException ex) { continue; }
                JsonObject o = e.getValue().getAsJsonObject();
                String world = o.has("world") && !o.get("world").isJsonNull() ? o.get("world").getAsString() : null;
                String boss = o.has("bossId") && !o.get("bossId").isJsonNull() ? o.get("bossId").getAsString() : null;
                newTps.put(floor, new String[]{world, boss});
            }

            // --- boss.json: ボスのワールド ---
            Map<String, String> newBossWorld = new HashMap<>();
            JsonObject bj = readJson("boss.json").getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : bj.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                if (o.has("world") && !o.get("world").isJsonNull()) newBossWorld.put(e.getKey(), o.get("world").getAsString());
            }

            // --- mobs.json: 定義順 / カテゴリ / レア扱いのモブ / 任意のfloor指定 ---
            List<String> newOrder = new ArrayList<>();
            Map<String, String> newCat = new HashMap<>();
            Map<String, Integer> override = new HashMap<>();
            Set<String> newRare = new HashSet<>();
            JsonObject mj = readJson("mobs.json").getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : mj.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                newOrder.add(e.getKey());
                newCat.put(e.getKey(), o.has("category") ? o.get("category").getAsString() : "NORMAL");
                if (o.has("floor") && !o.get("floor").isJsonNull()) override.put(e.getKey(), o.get("floor").getAsInt());
                if (o.has("raremob") && o.get("raremob").isJsonObject()) {
                    newRare.addAll(o.getAsJsonObject("raremob").keySet());
                }
            }

            // --- quests.json: クエストID -> 階層 (デイリー等の動的クエストは含めない) ---
            Map<String, Integer> newQuest = new LinkedHashMap<>();
            JsonObject qj = readJson("quests.json").getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : qj.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                if (o.has("floor") && !o.get("floor").isJsonNull()) newQuest.put(e.getKey(), o.get("floor").getAsInt());
            }

            // --- 反映 ---
            tps.clear(); tps.putAll(newTps);
            worldFloor.clear();
            for (Map.Entry<Integer, String[]> e : tps.entrySet()) if (e.getValue()[0] != null) worldFloor.put(e.getValue()[0], e.getKey());
            bossWorld.clear(); bossWorld.putAll(newBossWorld);
            mobOrder.clear(); mobOrder.addAll(newOrder);
            mobCategory.clear(); mobCategory.putAll(newCat);
            rareMobs.clear(); rareMobs.addAll(newRare);
            questFloor.clear(); questFloor.putAll(newQuest);

            // --- モブの階層: floor指定があればそれ、無ければ「mobs.jsonの並び順 + フロアボスのワールド」から推定 ---
            mobFloor.clear();
            int cur = 1;
            for (String id : mobOrder) {
                Integer ov = override.get(id);
                if ("FLOOR_BOSS".equals(mobCategory.get(id))) {
                    Integer w = worldFloor.get(bossWorld.get(id));
                    int f = ov != null ? ov : (w != null ? w : cur);
                    mobFloor.put(id, f);
                    cur = Math.max(cur, f + 1);
                } else {
                    mobFloor.put(id, ov != null ? ov : cur);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Achievement] データの読み込みに失敗しました(前回の内容を使い続けます): " + ex.getMessage());
        }
    }

    // ==========================================
    // ★ 階層まわりのヘルパー
    // ==========================================
    private int maxFloor() {
        if (!ONLY_ACCESSIBLE_FLOORS) return Integer.MAX_VALUE;
        return plugin.getCrystalManager() != null ? plugin.getCrystalManager().getMaxAccessibleFloor() : 1;
    }

    private int worldToFloor(String world) {
        Integer f = worldFloor.get(world);
        return f == null ? -1 : f;
    }

    private int bossFloor(String bossId) {
        Integer w = worldFloor.get(bossWorld.get(bossId));
        if (w != null) return w;
        Integer m = mobFloor.get(bossId);
        return m == null ? -1 : m;
    }

    /** 解放済みの階層にいる全フロアボス(mobs.jsonの並び順) */
    private List<String> allBosses() {
        List<String> out = new ArrayList<>();
        int max = maxFloor();
        for (String id : mobOrder) {
            if ("FLOOR_BOSS".equals(mobCategory.get(id)) && bossFloor(id) <= max) out.add(id);
        }
        return out;
    }

    /** その階層の「フロアボス」= 次の階層に入る条件のボス。最上階なら、その階層のワールドで最後に定義されたボス。 */
    private String bossFor(int floor) {
        String[] next = tps.get(floor + 1);
        if (next != null && next[1] != null) return next[1];
        String last = null;
        for (String id : mobOrder) {
            if ("FLOOR_BOSS".equals(mobCategory.get(id)) && bossFloor(id) == floor) last = id;
        }
        return last;
    }

    private List<String> mobsOf(int floor) {
        List<String> out = new ArrayList<>();
        for (String id : mobOrder) {
            String cat = mobCategory.get(id);
            boolean eligible = "NORMAL".equals(cat) || (INCLUDE_MINIONS && "MINION".equals(cat));
            if (!eligible || rareMobs.contains(id)) continue;
            Integer f = mobFloor.get(id);
            if (f != null && f == floor) out.add(id);
        }
        return out;
    }

    private List<String> treasuresOf(int floor) {
        List<String> out = new ArrayList<>();
        if (plugin.getTreasureManager() == null) return out;
        for (TreasureData t : plugin.getTreasureManager().getAllTreasures().values()) {
            if (t != null && t.world != null && worldToFloor(t.world) == floor) out.add(t.id);
        }
        Collections.sort(out);
        return out;
    }

    private List<String> questsOf(int floor) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : questFloor.entrySet()) if (e.getValue() == floor) out.add(e.getKey());
        Collections.sort(out);
        return out;
    }

    private static final String[] KANJI = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static String floorName(int f) { return "第" + (f >= 0 && f < KANJI.length ? KANJI[f] : String.valueOf(f)) + "層"; }

    public static String skillDisplayName(String key) {
        switch (key) {
            case "Fishing": return "釣り";
            case "Commerce": return "商業";
            case "Farming": return "農業";
            case "Cooking_焼く": return "料理(焼く)";
            case "Cooking_煮込む": return "料理(煮込む)";
            case "Cooking_茹でる": return "料理(茹でる)";
            case "Cooking_揚げる": return "料理(揚げる)";
            default: return key;
        }
    }

    private static String sig(String... parts) { return Integer.toHexString(String.join("|", parts).hashCode()); }
    private static String sigList(String prefix, Collection<String> items) {
        List<String> sorted = new ArrayList<>(items);
        Collections.sort(sorted);
        return sig(prefix, String.join(",", sorted));
    }

    /** そのプレイヤーが討伐済みのボス。同じワールドで先に定義されているボスは(連鎖ボス)討伐済みとみなす。 */
    private Set<String> defeatedBosses(PlayerData stats, AchData ad) {
        Set<String> set = new HashSet<>(ad.bossKills.keySet());
        if (stats != null && stats.defeatedBosses != null) set.addAll(stats.defeatedBosses);
        Set<String> out = new HashSet<>(set);
        for (String b : set) {
            int idx = mobOrder.indexOf(b);
            if (idx < 0) continue;
            int bf = bossFloor(b);
            for (int i = 0; i < idx; i++) {
                String c = mobOrder.get(i);
                if ("FLOOR_BOSS".equals(mobCategory.get(c)) && bossFloor(c) == bf) out.add(c);
            }
        }
        return out;
    }

    // ==========================================
    // ★ 実績定義の組み立て
    // ==========================================
    public synchronized void rebuildDefs() {
        List<AchievementDef> out = new ArrayList<>();
        try {
            int max = maxFloor();
            TreeSet<Integer> floors = new TreeSet<>();
            for (int f : tps.keySet()) floors.add(f);
            for (int f : questFloor.values()) floors.add(f);
            for (int f : mobFloor.values()) floors.add(f);
            floors.removeIf(f -> f < 1 || f > max);

            List<String> bosses = allBosses();

            // ---------------- ノーマル(階層ごと) ----------------
            for (int f : floors) {
                final String fn = floorName(f);

                List<String> mobs = mobsOf(f);
                if (!mobs.isEmpty()) {
                    final List<String> m = mobs;
                    out.add(new AchievementDef("n_mob_" + f, AchievementType.NORMAL, fn + "の掃討者",
                            fn + "の全モブ(ボス・レアモブ除く)を各" + MOB_KILL_TARGET + "体ずつ討伐する (" + m.size() + "種)",
                            sigList("mob" + f + "x" + MOB_KILL_TARGET, m),
                            (st, ad) -> {
                                int ok = 0;
                                for (String id : m) if (ad.kills.getOrDefault(id, 0) >= MOB_KILL_TARGET) ok++;
                                return new int[]{ok, m.size()};
                            }, null));
                }

                final String boss = bossFor(f);
                if (boss != null) {
                    out.add(new AchievementDef("n_boss_" + f, AchievementType.NORMAL, fn + "の攻略者",
                            fn + "のフロアボスを討伐する", sig("boss" + f, boss),
                            (st, ad) -> new int[]{defeatedBosses(st, ad).contains(boss) ? 1 : 0, 1}, null));
                }

                final List<String> treasures = treasuresOf(f);
                if (!treasures.isEmpty()) {
                    out.add(new AchievementDef("n_treasure_" + f, AchievementType.NORMAL, fn + "のトレジャーハンター",
                            fn + "の宝箱を全て回収する (" + treasures.size() + "個)", sigList("tre" + f, treasures),
                            (st, ad) -> {
                                int ok = 0;
                                for (String id : treasures) {
                                    if (ad.treasures.contains(id) || plugin.getTreasureManager().hasOpened(id, ad.uuid)) ok++;
                                }
                                return new int[]{ok, treasures.size()};
                            }, null));
                }

                final List<String> quests = questsOf(f);
                if (!quests.isEmpty()) {
                    out.add(new AchievementDef("n_quest_" + f, AchievementType.NORMAL, fn + "のクエストマスター",
                            fn + "のクエストを全てクリアする (" + quests.size() + "件)", sigList("quest" + f, quests),
                            (st, ad) -> {
                                int ok = 0;
                                for (String id : quests) if (st.questCounts != null && st.questCounts.getOrDefault(id, 0) >= 1) ok++;
                                return new int[]{ok, quests.size()};
                            }, null));
                }
            }

            // ---------------- ノーマル(スキルの使い手) ----------------
            List<String> skills = skillKeys();
            for (String skill : skills) {
                final int target = Math.min(SKILL_USER_LEVEL, plugin.getLimitManager().getSkillLimit(skill));
                final String key = skill;
                out.add(new AchievementDef("n_skill_" + skill, AchievementType.NORMAL, skillDisplayName(skill) + "スキルの使い手",
                        skillDisplayName(skill) + "スキルの熟練度をLv." + target + "に到達させる", sig("skill", skill, String.valueOf(target)),
                        (st, ad) -> new int[]{Math.min(target, st.proficiency.getOrDefault(key, 1)), target}, null));
            }

            // ---------------- ノーマル(全ボス討伐) ----------------
            if (!bosses.isEmpty()) {
                final List<String> b = bosses;
                out.add(new AchievementDef("n_allboss", AchievementType.NORMAL, "ボスハンター",
                        "実装済みのフロアボスを全て討伐する (" + b.size() + "体)", sigList("allboss", b),
                        (st, ad) -> {
                            Set<String> d = defeatedBosses(st, ad);
                            int ok = 0;
                            for (String id : b) if (d.contains(id)) ok++;
                            return new int[]{ok, b.size()};
                        }, null));
            }

            // ---------------- エクストラ ----------------
            for (int f : floors) {
                final List<String> quests = questsOf(f);
                if (quests.isEmpty()) continue;
                out.add(new AchievementDef("e_grandquest_" + f, AchievementType.EXTRA, "特級冒険者【" + floorName(f) + "】",
                        floorName(f) + "の全クエストを各" + GRAND_QUEST_CLEARS + "回ずつクリアする", sigList("grandq" + f + "x" + GRAND_QUEST_CLEARS, quests),
                        (st, ad) -> {
                            int ok = 0;
                            for (String id : quests) if (st.questCounts != null && st.questCounts.getOrDefault(id, 0) >= GRAND_QUEST_CLEARS) ok++;
                            return new int[]{ok, quests.size()};
                        }, null));
            }

            if (!bosses.isEmpty()) {
                final List<String> b = bosses;
                out.add(new AchievementDef("e_solo", AchievementType.EXTRA, "ソロプレイヤー",
                        "実装済みのフロアボスを全て一人で攻略する", sigList("solo", b),
                        (st, ad) -> {
                            int ok = 0;
                            for (String id : b) if (ad.soloBosses.contains(id)) ok++;
                            return new int[]{ok, b.size()};
                        }, null));
            }

            if (plugin.getCookingManager() != null) {
                final List<String> recipes = new ArrayList<>();
                for (CookingRecipe r : plugin.getCookingManager().getRecipes()) {
                    if (r != null && r.recipeId != null && r.successItem != null) recipes.add(r.recipeId);
                }
                if (!recipes.isEmpty()) {
                    out.add(new AchievementDef("e_chef", AchievementType.EXTRA, "グランシェフ",
                            "料理スキルで全ての料理の調理に成功する", sigList("chef", recipes),
                            (st, ad) -> {
                                int ok = 0;
                                for (String id : recipes) if (ad.cooked.contains(id)) ok++;
                                return new int[]{ok, recipes.size()};
                            }, null));
                }
            }

            addFishAch(out, "B", "凄腕の釣り師");
            addFishAch(out, "A", "一流の釣り師");
            addFishAch(out, "S", "伝説の釣り師");

            out.add(new AchievementDef("e_president", AchievementType.EXTRA, "大手会社社長",
                    "商業スキルで合計" + SALES_TARGET + "Colの売上を記録する", sig("sales", String.valueOf(SALES_TARGET)),
                    (st, ad) -> new int[]{(int) Math.min(Integer.MAX_VALUE, Math.min(ad.commerceSales, SALES_TARGET)), (int) SALES_TARGET}, null));

            if (plugin.getFarmManager() != null) {
                final List<String> crops = new ArrayList<>();
                for (Map.Entry<String, SAOFarmManager.CropData> e : plugin.getFarmManager().getAllCrops().entrySet()) {
                    if (e.getValue() != null && e.getValue().rareResultItemId != null) crops.add(e.getKey());
                }
                if (!crops.isEmpty()) {
                    out.add(new AchievementDef("e_farmer", AchievementType.EXTRA, "腕利き農家",
                            "農業スキルで現在作れる作物全てで最高品質を達成する", sigList("farmer", crops),
                            (st, ad) -> {
                                int ok = 0;
                                for (String id : crops) if (ad.bestCrops.contains(id)) ok++;
                                return new int[]{ok, crops.size()};
                            }, null));
                }
            }

            addMasterMace(out);

            // ---------------- ユニーク ----------------
            // 武器覇王: 武器種の熟練度が現在の最大 かつ 解放済みの最上階層のボスを(導入後に)討伐している
            final String topBoss = bossFor(Math.min(max, tps.isEmpty() ? 1 : tps.lastKey()));
            if (topBoss != null && plugin.getWeaponTypeManager() != null) {
                List<WeaponTypeData> types = new ArrayList<>(plugin.getWeaponTypeManager().getAllTypes().values());
                types.sort(Comparator.comparingInt(t -> t.slotIndex));
                for (WeaponTypeData t : types) {
                    if (t.category == null) continue;
                    final String cat = t.category;
                    final int limit = plugin.getLimitManager().getSkillLimit(cat);
                    out.add(new AchievementDef("u_weapon_" + cat, AchievementType.UNIQUE, "武器覇王-" + cat + "-",
                            cat + "の熟練度を最大(Lv." + limit + ")にし、最上階層のボスを最初に討伐する",
                            sig("weapon", cat, String.valueOf(limit), topBoss),
                            (st, ad) -> new int[]{st.proficiency.getOrDefault(cat, 1) >= limit && ad.bossKills.containsKey(topBoss) ? 1 : 0, 1},
                            ad -> ad.bossKills.getOrDefault(topBoss, Long.MAX_VALUE)));
                }
            }
            // スキルマスター: 武器以外のスキルで到達可能な最大熟練度に最初に到達
            Set<String> weaponCats = plugin.getWeaponTypeManager() != null
                    ? plugin.getWeaponTypeManager().getAllTypes().keySet() : Collections.<String>emptySet();
            for (String skill : skills) {
                if (weaponCats.contains(skill)) continue;
                final String key = skill;
                final int limit = plugin.getLimitManager().getSkillLimit(skill);
                out.add(new AchievementDef("u_skill_" + skill, AchievementType.UNIQUE, skillDisplayName(skill) + "スキルマスター",
                        skillDisplayName(skill) + "スキルを最大熟練度(Lv." + limit + ")まで最初に到達させる", sig("master", skill, String.valueOf(limit)),
                        (st, ad) -> new int[]{st.proficiency.getOrDefault(key, 1) >= limit ? 1 : 0, 1},
                        ad -> ad.firstUse.getOrDefault(key, Long.MAX_VALUE)));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Achievement] 実績定義の組み立てに失敗しました(前回の定義を使い続けます): " + ex);
            ex.printStackTrace();
            return;
        }
        this.defs = out;
        defById.clear();
        for (AchievementDef d : out) defById.put(d.id, d);
    }

    private void addFishAch(List<AchievementDef> out, String rank, String name) {
        out.add(new AchievementDef("e_fish_" + rank, AchievementType.EXTRA, name,
                "釣りスキルで" + rank + "ランクの大物を" + BIG_FISH_TARGET + "匹釣る", sig("fish", rank, String.valueOf(BIG_FISH_TARGET)),
                (st, ad) -> new int[]{Math.min(BIG_FISH_TARGET, ad.bigCatch.getOrDefault(rank, 0)), BIG_FISH_TARGET}, null));
    }

    /** ○○マスターメイス: 鍛造スキルごとに、作成できる全アイテムで奇跡的成功を出す */
    private void addMasterMace(List<AchievementDef> out) {
        if (plugin.getForgeManager() == null) return;
        Map<String, List<String>> bySkill = new LinkedHashMap<>();
        for (String s : new String[]{"斬撃武器鍛造", "刺突武器鍛造", "打撃武器鍛造"}) bySkill.put(s, new ArrayList<>());
        for (ForgeRecipe r : plugin.getForgeManager().getRecipes()) {
            if (r == null || r.resultWeaponId == null) continue;
            SAOWeapon w = plugin.getSaoItemManager().getWeapon(r.resultWeaponId);
            if (w == null) continue;
            String skill = ForgeManager.getForgingSkillForType(w.type);
            if (skill != null) bySkill.get(skill).add("w:" + r.resultWeaponId);
        }
        if (plugin.getArmorForgeManager() != null) {
            List<String> armors = new ArrayList<>();
            for (ArmorForgeRecipe r : plugin.getArmorForgeManager().getRecipes()) {
                if (r != null && r.resultArmorId != null) armors.add("a:" + r.resultArmorId);
            }
            bySkill.put("金属防具鍛造", armors);
        }
        for (Map.Entry<String, List<String>> e : bySkill.entrySet()) {
            final List<String> items = e.getValue();
            if (items.isEmpty()) continue;
            out.add(new AchievementDef("e_mace_" + e.getKey(), AchievementType.EXTRA, e.getKey() + "マスターメイス",
                    e.getKey() + "で作成できる全てのアイテムで奇跡的成功を出す", sigList("mace" + e.getKey(), items),
                    (st, ad) -> {
                        int ok = 0;
                        for (String id : items) if (ad.miracles.contains(id)) ok++;
                        return new int[]{ok, items.size()};
                    }, null));
        }
    }

    /** 熟練度が設定されている全スキル(level_limits.jsonのskill_limitsのキー) */
    private List<String> skillKeys() {
        return plugin.getLimitManager() != null ? plugin.getLimitManager().getSkillLimitKeys() : new ArrayList<>();
    }

    // ==========================================
    // ★ 参照用API(メニューなど)
    // ==========================================
    public List<AchievementDef> getDefs() { return defs; }
    public AchievementDef getDef(String id) { return defById.get(id); }
    public Holder getHolder(String id) { return holders.get(id); }

    public boolean isOwned(AchData ad, AchievementDef def) {
        if (ad == null || def == null) return false;
        if (def.isUnique()) {
            Holder h = holders.get(def.id);
            return h != null && h.uuid.equals(ad.uuid) && h.sig.equals(def.signature);
        }
        AchData.Grant g = ad.granted.get(def.id);
        return g != null && g.sig.equals(def.signature);
    }

    public long grantedAt(AchData ad, AchievementDef def) {
        if (def.isUnique()) {
            Holder h = holders.get(def.id);
            return h != null ? h.at : 0L;
        }
        AchData.Grant g = ad.granted.get(def.id);
        return g != null ? g.at : 0L;
    }

    /** 閲覧用: オンラインならキャッシュ、オフラインならファイルから読み込む(キャッシュはしない) */
    public AchData getDataForView(UUID uuid) {
        AchData d = cache.get(uuid);
        if (d != null) return d;
        return AchData.load(fileOf(uuid), uuid);
    }

    /** 進捗(オンラインのプレイヤーのみ)。nullなら評価できない。 */
    public int[] progressOf(Player p, AchievementDef def) {
        PlayerData st = plugin.getPlayerManager().getStats(p);
        AchData ad = cache.get(p.getUniqueId());
        if (st == null || ad == null) return null;
        try { return def.evaluator.eval(st, ad); } catch (Exception e) { return null; }
    }

    private File fileOf(UUID uuid) { return new File(dir, uuid + ".yml"); }

    private AchData data(Player p) {
        AchData d = cache.get(p.getUniqueId());
        if (d == null) {
            d = AchData.load(fileOf(p.getUniqueId()), p.getUniqueId());
            d.lastName = p.getName();
            cache.put(p.getUniqueId(), d);
        }
        return d;
    }

    private void saveNow(AchData d) { d.save(fileOf(d.uuid)); }

    // ==========================================
    // ★ ユニーク保持者ファイル
    // ==========================================
    private void loadHolders() {
        holders.clear();
        if (!uniqueFile.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(uniqueFile);
        for (String id : c.getKeys(false)) {
            try {
                UUID u = UUID.fromString(c.getString(id + ".uuid", ""));
                holders.put(id, new Holder(u, c.getString(id + ".name", "?"), c.getString(id + ".sig", ""), c.getLong(id + ".at", 0L)));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveHolders() {
        YamlConfiguration c = new YamlConfiguration();
        for (Map.Entry<String, Holder> e : holders.entrySet()) {
            c.set(e.getKey() + ".uuid", e.getValue().uuid.toString());
            c.set(e.getKey() + ".name", e.getValue().name);
            c.set(e.getKey() + ".sig", e.getValue().sig);
            c.set(e.getKey() + ".at", e.getValue().at);
        }
        AchData.writeAtomically(c, uniqueFile);
    }

    // ==========================================
    // ★ 周期処理 / 評価
    // ==========================================
    private void periodic() {
        try {
            tick++;
            if (tick % 5 == 0) rebuildDefs();                 // 5秒ごとに定義を作り直す(宝箱・クエスト等の増減を反映)
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            for (Player p : online) evaluate(p);
            resolveUniques(online);
            if (tick % 30 == 0) for (AchData d : cache.values()) if (d.dirty) saveNow(d);   // 30秒ごとに保存
            if (tick % 10 == 0) refreshAllTitles();            // 10秒ごとに称号表示を整える(スコアボード作り直し対策)
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** ノーマル/エクストラの取り消し・付与。ユニークは resolveUniques で全員分をまとめて判定する。 */
    public void evaluate(Player p) {
        PlayerData st = plugin.getPlayerManager().getStats(p);
        if (st == null || !p.isOnline()) return;
        AchData ad = data(p);
        List<AchievementDef> list = defs;
        if (list.isEmpty()) return;

        // 初めてスキルを使った(=熟練度が動いた)時刻を記録する
        long now = System.currentTimeMillis();
        for (String skill : skillKeys()) {
            if (ad.firstUse.containsKey(skill)) continue;
            if (st.proficiency.getOrDefault(skill, 1) > 1 || st.proficiencyExp.getOrDefault(skill, 0) > 0) {
                ad.firstUse.put(skill, now);
                ad.dirty = true;
            }
        }

        List<String> revoked = new ArrayList<>();
        List<AchievementDef> newlyGranted = new ArrayList<>();

        // 定義が無くなった / 条件(署名)が変わった実績は取り消す
        Iterator<Map.Entry<String, AchData.Grant>> it = ad.granted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AchData.Grant> e = it.next();
            AchievementDef def = defById.get(e.getKey());
            if (def == null || !e.getValue().sig.equals(def.signature)) {
                it.remove();
                ad.dirty = true;
                revoked.add(def != null ? def.name : e.getKey());
                if (e.getKey().equals(ad.equipped)) ad.equipped = null;
            }
        }

        for (AchievementDef def : list) {
            if (def.isUnique() || ad.granted.containsKey(def.id)) continue;
            int[] r;
            try { r = def.evaluator.eval(st, ad); } catch (Exception ex) { continue; }
            if (r[1] > 0 && r[0] >= r[1]) {
                ad.granted.put(def.id, new AchData.Grant(def.signature, now));
                ad.dirty = true;
                newlyGranted.add(def);
            }
        }

        boolean titleChanged = sanitizeEquipped(ad) || !revoked.isEmpty();
        if (!revoked.isEmpty() || !newlyGranted.isEmpty()) saveNow(ad);

        for (String name : revoked) {
            p.sendMessage("§c[実績] §f『" + name + "』は条件が更新されたため取り消されました。§7(進捗は引き継がれます。新しい条件を満たすと自動で再獲得します)");
        }
        announceGrants(p, newlyGranted);
        if (titleChanged) refreshTitle(p);
    }

    private void announceGrants(Player p, List<AchievementDef> list) {
        if (list.isEmpty()) return;
        if (list.size() > 3) {
            p.sendMessage("§6[実績] §f" + list.size() + "件の実績を獲得しました！ §7(ステータスメニュー → 実績確認)");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            return;
        }
        for (AchievementDef def : list) {
            p.sendMessage("§6[実績] §f『" + def.type.color + def.name + "§f』を獲得しました！ §7(" + def.type.label + ")");
            p.sendTitle("§6実績解除", def.type.color + def.name, 5, 50, 10);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    /** 装備中の実績が、取り消し等で無効になっていたら外す。外したらtrue。 */
    private boolean sanitizeEquipped(AchData ad) {
        if (ad.equipped == null) return false;
        AchievementDef def = defById.get(ad.equipped);
        if (def == null || !isOwned(ad, def)) {
            ad.equipped = null;
            ad.dirty = true;
            return true;
        }
        return false;
    }

    /**
     * ユニーク実績の判定。1つの実績につき、判定は必ずこのメソッド内(メインスレッド)で
     * 「保持者がいないことを確認 → 最優先の候補を選ぶ → 保持者を書き込む」を一続きで行うので、二重付与は起きない。
     * 同じ判定タイミングで複数人が条件を満たしていた場合は、priority(より早く条件を満たした/先に使った方)で決める。
     */
    private void resolveUniques(Collection<? extends Player> online) {
        List<AchievementDef> list = defs;
        if (list.isEmpty()) return;

        // 定義が無くなった / 条件が変わったユニークは保持者を外して、再び早い者勝ちに戻す
        Iterator<Map.Entry<String, Holder>> it = holders.entrySet().iterator();
        boolean holdersChanged = false;
        while (it.hasNext()) {
            Map.Entry<String, Holder> e = it.next();
            AchievementDef def = defById.get(e.getKey());
            if (def == null || !def.isUnique() || !e.getValue().sig.equals(def.signature)) {
                Holder h = e.getValue();
                it.remove();
                holdersChanged = true;
                Player hp = Bukkit.getPlayer(h.uuid);
                String nm = def != null ? def.name : e.getKey();
                if (hp != null) {
                    AchData ad = data(hp);
                    if (e.getKey().equals(ad.equipped)) { ad.equipped = null; ad.dirty = true; refreshTitle(hp); }
                    hp.sendMessage("§c[実績] §f『" + nm + "』は条件が更新されたため取り消されました。§7(再び早い者勝ちになります)");
                }
                plugin.getLogger().info("[Achievement] ユニーク『" + nm + "』を条件更新により白紙に戻しました(旧保持者: " + h.name + ")");
            }
        }
        if (holdersChanged) saveHolders();

        for (AchievementDef def : list) {
            if (!def.isUnique() || holders.containsKey(def.id)) continue;
            Player best = null;
            long bestPriority = Long.MAX_VALUE;
            for (Player p : online) {
                PlayerData st = plugin.getPlayerManager().getStats(p);
                if (st == null) continue;
                AchData ad = data(p);
                int[] r;
                try { r = def.evaluator.eval(st, ad); } catch (Exception ex) { continue; }
                if (r[0] < r[1]) continue;
                long pr = def.priority != null ? def.priority.of(ad) : Long.MAX_VALUE;
                if (best == null || pr < bestPriority) { best = p; bestPriority = pr; }
            }
            if (best != null) claimUnique(def, best);
        }
    }

    private void claimUnique(AchievementDef def, Player p) {
        if (holders.containsKey(def.id)) return;   // 念のため(二重付与防止)
        long now = System.currentTimeMillis();
        holders.put(def.id, new Holder(p.getUniqueId(), p.getName(), def.signature, now));
        saveHolders();                              // 付与の瞬間にファイルへ書く(クラッシュしても取り消されない)
        AchData ad = data(p);
        ad.dirty = true;
        Bukkit.broadcastMessage("§6[実績] §e" + p.getName() + " §fがユニーク実績『§6" + def.name + "§f』を獲得しました！");
        p.sendTitle("§6ユニーク実績解除", "§6" + def.name, 10, 80, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
        plugin.getLogger().info("[Achievement] ユニーク『" + def.name + "』の保持者: " + p.getName() + " (" + p.getUniqueId() + ")");
    }

    // ==========================================
    // ★ 称号(名前の横に表示する実績)
    // ==========================================
    public String titlePrefix(UUID uuid) {
        AchData ad = cache.get(uuid);
        if (ad == null || ad.equipped == null) return "";
        AchievementDef def = defById.get(ad.equipped);
        if (def == null || !isOwned(ad, def)) return "";
        String s = "§6[" + def.type.color + def.name + "§6] §r";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** 称号の変更/取り外し(メニューからのクリック) */
    public boolean equip(Player p, String achId) {
        AchData ad = data(p);
        AchievementDef def = defById.get(achId);
        if (def == null || !isOwned(ad, def)) return false;
        ad.equipped = achId;
        ad.dirty = true;
        saveNow(ad);
        refreshTitle(p);
        return true;
    }

    public void unequip(Player p) {
        AchData ad = data(p);
        ad.equipped = null;
        ad.dirty = true;
        saveNow(ad);
        refreshTitle(p);
    }

    public void refreshAllTitles() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshTitle(p);
    }

    /** 全員のスコアボード上で、対象プレイヤーの名前の前(Teamのprefix)と、Tabリストに称号を出す */
    public void refreshTitle(Player target) {
        String prefix = titlePrefix(target.getUniqueId());
        try {
            target.setPlayerListName(prefix + target.getName());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                Scoreboard board = viewer.getScoreboard();
                if (board == null) continue;
                Team team = board.getTeam(target.getName());
                if (team == null) {
                    if (prefix.isEmpty()) continue;
                    team = board.registerNewTeam(target.getName());
                }
                if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
                if (!prefix.equals(team.getPrefix())) team.setPrefix(prefix);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Achievement] 称号の表示更新に失敗: " + e.getMessage());
        }
    }

    // ==========================================
    // ★ 参加 / 退出
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // PlayerDataの読み込み完了を待ってから評価する
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            AchData ad = data(p);
            ad.lastName = p.getName();
            evaluate(p);
            refreshAllTitles();
        }, 30L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        AchData ad = cache.remove(event.getPlayer().getUniqueId());
        if (ad != null) saveNow(ad);
    }

    // ==========================================
    // ★ 進捗の記録(各システムから呼ぶフック)
    // ==========================================
    public void onMobKill(Player p, String mobId) {
        if (p == null || mobId == null) return;
        AchData ad = data(p);
        ad.kills.merge(mobId, 1, (a, b) -> Math.min(a + b, 1_000_000));
        ad.dirty = true;
    }

    /** ボス討伐。solo = 討伐時にボス部屋に自分しかいなかった */
    public void onBossKill(Player p, String bossId, boolean solo) {
        if (p == null || bossId == null) return;
        AchData ad = data(p);
        ad.bossKills.putIfAbsent(bossId, System.currentTimeMillis());
        if (solo) ad.soloBosses.add(bossId);
        ad.dirty = true;
        saveNow(ad);
    }

    public void onTreasureOpened(Player p, String treasureId) {
        if (p == null || treasureId == null) return;
        AchData ad = data(p);
        if (ad.treasures.add(treasureId)) ad.dirty = true;
    }

    public void onCookSuccess(Player p, String recipeId) {
        if (p == null || recipeId == null) return;
        AchData ad = data(p);
        if (ad.cooked.add(recipeId)) ad.dirty = true;
    }

    public void onFishCaught(Player p, String rank, boolean big) {
        if (p == null || rank == null || !big) return;
        AchData ad = data(p);
        ad.bigCatch.merge(rank.toUpperCase(Locale.ROOT), 1, (a, b) -> a + b);
        ad.dirty = true;
    }

    /** プレイヤーショップの売上。店主がオフラインでも記録する。 */
    public void onShopSale(UUID owner, long amount) {
        if (owner == null || amount <= 0) return;
        AchData ad = cache.get(owner);
        if (ad != null) {
            ad.commerceSales += amount;
            ad.dirty = true;
            return;
        }
        AchData off = AchData.load(fileOf(owner), owner);
        off.commerceSales += amount;
        saveNow(off);
    }

    public void onBestCrop(Player p, String seedId) {
        if (p == null || seedId == null) return;
        AchData ad = data(p);
        if (ad.bestCrops.add(seedId)) ad.dirty = true;
    }

    /** 鍛造の結果。奇跡的成功のときだけ記録する。itemKeyは "w:武器ID" / "a:防具ID" */
    public void onForge(Player p, String tierName, String itemKey) {
        if (p == null || itemKey == null || !"奇跡的成功".equals(tierName)) return;
        AchData ad = data(p);
        if (ad.miracles.add(itemKey)) ad.dirty = true;
    }

    // ==========================================
    // ★ 管理者用
    // ==========================================
    public boolean adminGrant(UUID uuid, String achId) {
        AchievementDef def = defById.get(achId);
        if (def == null) return false;
        if (def.isUnique()) {
            holders.put(def.id, new Holder(uuid, nameOf(uuid), def.signature, System.currentTimeMillis()));
            saveHolders();
            return true;
        }
        AchData ad = cache.containsKey(uuid) ? cache.get(uuid) : AchData.load(fileOf(uuid), uuid);
        ad.granted.put(def.id, new AchData.Grant(def.signature, System.currentTimeMillis()));
        ad.dirty = true;
        saveNow(ad);
        return true;
    }

    public boolean adminRevoke(UUID uuid, String achId) {
        AchievementDef def = defById.get(achId);
        if (def == null) return false;
        AchData ad = cache.containsKey(uuid) ? cache.get(uuid) : AchData.load(fileOf(uuid), uuid);
        if (def.isUnique()) {
            Holder h = holders.get(def.id);
            if (h == null || !h.uuid.equals(uuid)) return false;
            holders.remove(def.id);
            saveHolders();
        } else {
            ad.granted.remove(def.id);
        }
        if (achId.equals(ad.equipped)) ad.equipped = null;
        ad.dirty = true;
        saveNow(ad);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) refreshTitle(p);
        return true;
    }

    /** ユニークの保持者を外して、再び早い者勝ちに戻す */
    public boolean adminClearUnique(String achId) {
        Holder h = holders.remove(achId);
        if (h == null) return false;
        saveHolders();
        Player hp = Bukkit.getPlayer(h.uuid);
        if (hp != null) {
            AchData ad = data(hp);
            if (achId.equals(ad.equipped)) { ad.equipped = null; ad.dirty = true; refreshTitle(hp); }
        }
        return true;
    }

    private String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(uuid).getName();
        return n != null ? n : "?";
    }
}
