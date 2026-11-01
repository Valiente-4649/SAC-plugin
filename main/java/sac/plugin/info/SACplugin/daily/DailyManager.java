package sac.plugin.info.SACplugin.daily;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.npc.SAOQuestData;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;

public class DailyManager implements Reloadable {
    private final SACplugin plugin;

    // 階層 -> 抽選プール定義（daily_floors.jsonから読み込み）
    private final Map<Integer, DailyFloorPoolData> floorPools = new HashMap<>();
    // 階層 -> 登録済みdailyspot座標一覧（/dailyspotコマンドで登録）
    private final Map<Integer, List<Location>> registeredSpots = new HashMap<>();
    // 階層 -> 本日のデイリー内容
    private final Map<Integer, DailyEntry> todaysDailies = new HashMap<>();

    private String currentLogicalDate = "";

    private final Random random = new Random();

    // 報酬候補（Col/EXP共通）。indexが大きいほど値が高く、当選確率は下がる。
    private static final long[] REWARD_VALUES = {500, 600, 700, 800, 900, 1000, 1200, 1300, 1400, 1500};
    // 値が小さいほど重みが大きい（当選しやすい）
    private static final int[] REWARD_WEIGHTS = {10, 9, 8, 7, 6, 5, 4, 3, 2, 1};

    public enum DailyType { KILL, DELIVERY, TREASURE }

    /** 1つの報酬アイテムとその個数 */
    public static class RewardStack {
        public String itemId;
        public int amount;
        public RewardStack(String itemId, int amount) { this.itemId = itemId; this.amount = amount; }
    }

    /** 1階層分の「本日のデイリー」内容 */
    public static class DailyEntry {
        public int floor;
        public DailyType type;
        public String targetId;       // KILL/DELIVERY用
        public int requiredAmount;
        public long rewardCol;
        public int rewardExp;
        public List<RewardStack> rewardItems = new ArrayList<>(); // 種類・個数を3回抽選した結果（合算済み）
        public String chestWorld;     // TREASURE用
        public double chestX, chestY, chestZ;

        public String getQuestId() { return "daily_floor" + floor; }
    }

    public DailyManager(SACplugin plugin) {
        this.plugin = plugin;
        loadFloorPools();
        loadSpots();
        loadState();

        String today = getLogicalDateString();
        if (!today.equals(currentLogicalDate) || todaysDailies.isEmpty()) {
            rollAll();
        } else {
            // 再起動をまたいでも同じ内容を維持するため、保存済みの内容からクエストを再登録する
            for (DailyEntry entry : todaysDailies.values()) {
                reregisterQuestIfNeeded(entry);
            }
        }
    }

    // ==========================================
    // 日付・リセット判定
    // ==========================================

    /** 現実時間4時を基準にした「論理上の日付」を返す (yyyy-MM-dd)。4時未満はまだ前日扱い。 */
    public String getLogicalDateString() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() < 4) {
            now = now.minusDays(1);
        }
        return now.toLocalDate().toString();
    }

    /**
     * 1分に1回程度呼び出す想定。論理日付が変わっていれば再抽選する。
     * SACplugin側でBukkitのスケジューラから定期的に呼び出すこと。
     */
    public void checkAndRollIfNeeded() {
        String today = getLogicalDateString();
        if (!today.equals(currentLogicalDate)) {
            plugin.getLogger().info("[Daily] 日付が変わったためデイリーを再抽選します: " + currentLogicalDate + " -> " + today);
            rollAll();
        }
    }

    // ==========================================
    // プール読み込み
    // ==========================================

    /**
     * daily_floors.json と daily_spots.yml を再読み込みする（コード自体は変えず、データだけ更新したい時用）。
     * 本日すでに抽選済みの内容(todaysDailies)には触らないので、進行中のプレイヤーの受注状況は壊れない。
     * 新しいプールを反映させたい場合は、この後に /daily reload で再抽選すること。
     */
    public void reloadPools() {
        floorPools.clear();
        loadFloorPools();
        registeredSpots.clear();
        loadSpots();
        plugin.getLogger().info("[Daily] daily_floors.json / daily_spots.yml を再読み込みしました。");
    }

    @Override
    public void reload() {
        reloadPools();
    }

    private void loadFloorPools() {
        File file = new File(plugin.getDataFolder(), "daily_floors.json");
        if (!file.exists()) {
            plugin.saveResource("daily_floors.json", false);
        }
        if (!file.exists()) {
            plugin.getLogger().warning("[Daily] daily_floors.json が見つかりません。デイリー機能は無効になります。");
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<List<DailyFloorPoolData>>() {}.getType();
            List<DailyFloorPoolData> list = gson.fromJson(reader, type);
            if (list != null) {
                for (DailyFloorPoolData data : list) {
                    floorPools.put(data.floor, data);
                }
            }
            plugin.getLogger().info("[Daily] " + floorPools.size() + "階層分のデイリープールを読み込みました。");
        } catch (Exception e) {
            plugin.getLogger().severe("[Daily] daily_floors.json の読み込みに失敗しました: " + e.getMessage());
        }
    }

    public DailyFloorPoolData getPool(int floor) {
        return floorPools.get(floor);
    }

    public Set<Integer> getFloors() {
        return floorPools.keySet();
    }

    // ==========================================
    // dailyspot（宝箱候補地点）の登録・永続化
    // ==========================================

    private File getSpotsFile() {
        return new File(plugin.getDataFolder(), "daily_spots.yml");
    }

    private void loadSpots() {
        File file = getSpotsFile();
        if (!file.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (config.getConfigurationSection("spots") == null) return;

        for (String floorKey : config.getConfigurationSection("spots").getKeys(false)) {
            int floor;
            try { floor = Integer.parseInt(floorKey); } catch (NumberFormatException e) { continue; }

            List<Location> locs = new ArrayList<>();
            List<Map<?, ?>> rawList = config.getMapList("spots." + floorKey);
            for (Map<?, ?> raw : rawList) {
                try {
                    String worldName = String.valueOf(raw.get("world"));
                    double x = Double.parseDouble(String.valueOf(raw.get("x")));
                    double y = Double.parseDouble(String.valueOf(raw.get("y")));
                    double z = Double.parseDouble(String.valueOf(raw.get("z")));
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        locs.add(new Location(world, x, y, z));
                    }
                } catch (Exception ignored) {}
            }
            registeredSpots.put(floor, locs);
        }
    }

    private void saveSpots() {
        File file = getSpotsFile();
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("spots", null);
        for (Map.Entry<Integer, List<Location>> entry : registeredSpots.entrySet()) {
            List<Map<String, Object>> rawList = new ArrayList<>();
            for (Location loc : entry.getValue()) {
                Map<String, Object> raw = new HashMap<>();
                raw.put("world", loc.getWorld().getName());
                raw.put("x", loc.getX());
                raw.put("y", loc.getY());
                raw.put("z", loc.getZ());
                rawList.add(raw);
            }
            config.set("spots." + entry.getKey(), rawList);
        }
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** /dailyspot コマンドから呼ばれる。指定階層に候補地点を1つ追加登録する。 */
    public int registerSpot(int floor, Location loc) {
        registeredSpots.computeIfAbsent(floor, k -> new ArrayList<>()).add(loc.clone());
        saveSpots();
        return registeredSpots.get(floor).size();
    }

    public List<Location> getSpots(int floor) {
        return registeredSpots.getOrDefault(floor, Collections.emptyList());
    }

    // ==========================================
    // 状態(本日のデイリー内容)の永続化
    // ==========================================

    private File getStateFile() {
        return new File(plugin.getDataFolder(), "daily_state.yml");
    }

    private void loadState() {
        File file = getStateFile();
        if (!file.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        currentLogicalDate = config.getString("date", "");

        if (config.getConfigurationSection("floors") == null) return;
        for (String floorKey : config.getConfigurationSection("floors").getKeys(false)) {
            int floor;
            try { floor = Integer.parseInt(floorKey); } catch (NumberFormatException e) { continue; }

            String path = "floors." + floorKey + ".";
            DailyEntry entry = new DailyEntry();
            entry.floor = floor;
            try {
                entry.type = DailyType.valueOf(config.getString(path + "type", "KILL"));
            } catch (IllegalArgumentException e) {
                entry.type = DailyType.KILL;
            }
            entry.targetId = config.getString(path + "targetId", "");
            entry.requiredAmount = config.getInt(path + "requiredAmount", 1);
            entry.rewardCol = config.getLong(path + "rewardCol", 100);
            entry.rewardExp = config.getInt(path + "rewardExp", 100);
            entry.chestWorld = config.getString(path + "chestWorld", null);
            entry.chestX = config.getDouble(path + "chestX", 0);
            entry.chestY = config.getDouble(path + "chestY", 0);
            entry.chestZ = config.getDouble(path + "chestZ", 0);

            List<String> itemLines = config.getStringList(path + "rewardItems");
            for (String line : itemLines) {
                // 形式: "itemId:amount"
                int idx = line.lastIndexOf(':');
                if (idx <= 0) continue;
                try {
                    String itemId = line.substring(0, idx);
                    int amount = Integer.parseInt(line.substring(idx + 1));
                    entry.rewardItems.add(new RewardStack(itemId, amount));
                } catch (NumberFormatException ignored) {}
            }

            todaysDailies.put(floor, entry);
        }
    }

    private void saveState() {
        File file = getStateFile();
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("date", currentLogicalDate);
        config.set("floors", null);

        for (DailyEntry entry : todaysDailies.values()) {
            String path = "floors." + entry.floor + ".";
            config.set(path + "type", entry.type.name());
            config.set(path + "targetId", entry.targetId);
            config.set(path + "requiredAmount", entry.requiredAmount);
            config.set(path + "rewardCol", entry.rewardCol);
            config.set(path + "rewardExp", entry.rewardExp);
            List<String> itemLines = new ArrayList<>();
            for (RewardStack stack : entry.rewardItems) {
                itemLines.add(stack.itemId + ":" + stack.amount);
            }
            config.set(path + "rewardItems", itemLines);
            config.set(path + "chestWorld", entry.chestWorld);
            config.set(path + "chestX", entry.chestX);
            config.set(path + "chestY", entry.chestY);
            config.set(path + "chestZ", entry.chestZ);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // 抽選ロジック
    // ==========================================

    /** 全階層のデイリーを再抽選する */
    public void rollAll() {
        // 前日の宝箱ブロックを撤去
        for (DailyEntry old : todaysDailies.values()) {
            clearOldChest(old);
            plugin.getQuestManager().unregisterDynamicQuest(old.getQuestId());
        }
        todaysDailies.clear();

        // ★変更: Col/EXPは全階層共通にするため、1日1回だけ抽選してすべての階層に適用する
        long sharedRewardCol = weightedRewardValue();
        int sharedRewardExp = (int) weightedRewardValue();

        for (int floor : floorPools.keySet()) {
            DailyEntry entry = rollFloor(floor);
            if (entry != null) {
                entry.rewardCol = sharedRewardCol;
                entry.rewardExp = sharedRewardExp;
                todaysDailies.put(floor, entry);
                applyEntry(entry);
            }
        }

        currentLogicalDate = getLogicalDateString();
        saveState();
        plugin.getLogger().info("[Daily] デイリーを再抽選しました (" + currentLogicalDate + ") Col=" + sharedRewardCol + " EXP=" + sharedRewardExp);
    }

    private void clearOldChest(DailyEntry entry) {
        if (entry.type != DailyType.TREASURE || entry.chestWorld == null) return;
        World world = Bukkit.getWorld(entry.chestWorld);
        if (world == null) return;
        Location loc = new Location(world, entry.chestX, entry.chestY, entry.chestZ);
        if (loc.getBlock().getType() == Material.CHEST) {
            loc.getBlock().setType(Material.AIR);
        }
    }

    private DailyEntry rollFloor(int floor) {
        DailyFloorPoolData pool = floorPools.get(floor);
        if (pool == null) return null;

        List<DailyType> candidates = new ArrayList<>();
        if (!pool.killMobIds.isEmpty()) candidates.add(DailyType.KILL);
        if (!pool.deliveryItemIds.isEmpty()) candidates.add(DailyType.DELIVERY);
        if (!getSpots(floor).isEmpty()) candidates.add(DailyType.TREASURE);

        if (candidates.isEmpty()) {
            plugin.getLogger().warning("[Daily] 第" + floor + "層は討伐/納品/宝箱いずれの候補も無いためスキップします。");
            return null;
        }

        DailyType type = candidates.get(random.nextInt(candidates.size()));

        DailyEntry entry = new DailyEntry();
        entry.floor = floor;
        entry.type = type;

        int amount = pool.minAmount + random.nextInt(Math.max(1, pool.maxAmount - pool.minAmount + 1));

        switch (type) {
            case KILL:
                entry.targetId = pool.killMobIds.get(random.nextInt(pool.killMobIds.size()));
                entry.requiredAmount = amount;
                break;
            case DELIVERY:
                entry.targetId = pool.deliveryItemIds.get(random.nextInt(pool.deliveryItemIds.size()));
                entry.requiredAmount = amount;
                break;
            case TREASURE:
                List<Location> spots = getSpots(floor);
                Location chosen = spots.get(random.nextInt(spots.size()));
                entry.chestWorld = chosen.getWorld().getName();
                entry.chestX = chosen.getBlockX() + 0.5;
                entry.chestY = chosen.getBlockY();
                entry.chestZ = chosen.getBlockZ() + 0.5;
                break;
        }

        // ★変更: 討伐・納品・宝箱すべての種別で、共通のアイテム報酬プールから「種類・個数」を3回抽選する。
        // プールには自階層に加えて、1つ前の階層のアイテムも混ぜる。
        List<String> combinedPool = new ArrayList<>(pool.rewardItemIds);
        DailyFloorPoolData prevPool = floorPools.get(floor - 1);
        if (prevPool != null && prevPool.rewardItemIds != null) {
            combinedPool.addAll(prevPool.rewardItemIds);
        }

        if (!combinedPool.isEmpty()) {
            Map<String, Integer> merged = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                String itemId = combinedPool.get(random.nextInt(combinedPool.size()));
                int min = pool.rewardItemMinAmount;
                int max = Math.max(min, pool.rewardItemMaxAmount);
                int rolledAmount = min + random.nextInt(max - min + 1);
                merged.merge(itemId, rolledAmount, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : merged.entrySet()) {
                entry.rewardItems.add(new RewardStack(e.getKey(), e.getValue()));
            }
        }

        return entry;
    }

    /** {100,200,...,1000}の中から、値が大きいほど当選しにくい重み付き抽選を行う */
    private long weightedRewardValue() {
        int totalWeight = 0;
        for (int w : REWARD_WEIGHTS) totalWeight += w;

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < REWARD_WEIGHTS.length; i++) {
            cumulative += REWARD_WEIGHTS[i];
            if (roll < cumulative) return REWARD_VALUES[i];
        }
        return REWARD_VALUES[0];
    }

    /** 抽選結果をゲーム内に反映する（クエスト登録 or 宝箱設置） */
    private void applyEntry(DailyEntry entry) {
        if (entry.type == DailyType.TREASURE) {
            World world = Bukkit.getWorld(entry.chestWorld);
            if (world != null) {
                Location loc = new Location(world, entry.chestX, entry.chestY, entry.chestZ);
                loc.getBlock().setType(Material.CHEST);
            }
            return;
        }

        SAOQuestData quest = new SAOQuestData();
        quest.id = entry.getQuestId();
        quest.floor = entry.floor;
        DailyFloorPoolData pool = floorPools.get(entry.floor);
        quest.npcName = (pool != null && pool.npcName != null && !pool.npcName.isEmpty())
                ? pool.npcName : ("デイリーおじさん(第" + entry.floor + "層)");
        quest.type = entry.type == DailyType.KILL ? "MOB" : "ITEM";
        quest.targetId = entry.targetId;
        quest.requiredAmount = entry.requiredAmount;
        quest.rewardCol = entry.rewardCol;
        quest.rewardExp = entry.rewardExp;
        // ★注意: アイテム報酬は複数種類になり得るため、通常のクエスト単一アイテム報酬の仕組みは使わず
        // 完了時に DailyManager.grantRewardItems() で別途まとめて付与する（SAOQuestListener側で呼び出し）

        String targetName = resolveTargetName(quest.targetId, quest.type);
        String goalType = entry.type == DailyType.KILL ? "討伐" : "納品";
        // ★修正: questNameが未設定だったため「クエスト[ null ]を受注しました」等になっていた不具合を修正
        quest.questName = "第" + entry.floor + "層のデイリー(" + goalType + ")";
        quest.descriptions = Arrays.asList(
                "今日のデイリーだよ。",
                targetName + " を " + entry.requiredAmount + " " + goalType + "してきてくれないか。"
        );
        quest.completedDescriptions = Arrays.asList(
                "おお、ありがとう！これは今日のデイリー報酬だ。"
        );

        plugin.getQuestManager().registerDynamicQuest(quest);
    }

    private void reregisterQuestIfNeeded(DailyEntry entry) {
        if (entry.type == DailyType.TREASURE) return;
        applyEntry(entry);
    }

    private String resolveTargetName(String targetId, String type) {
        if ("MOB".equals(type)) {
            sac.plugin.info.SACplugin.mob.SAOMobData mobData = plugin.getSaoMobManager().getMobData(targetId);
            if (mobData != null) return mobData.name;
        } else if ("ITEM".equals(type)) {
            sac.plugin.info.SACplugin.item.SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(targetId);
            if (itemData != null && itemData.name != null) return itemData.name;
            sac.plugin.info.SACplugin.item.SAOWeapon weaponData = plugin.getSaoItemManager().getWeapon(targetId);
            if (weaponData != null && weaponData.name != null) return weaponData.name;
            if (plugin.getFishingManager() != null) {
                sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(targetId);
                if (fishData != null && fishData.name != null) return fishData.name;
            }
        }
        return targetId;
    }

    // ==========================================
    // 外部から使うアクセサ
    // ==========================================

    public DailyEntry getEntry(int floor) {
        return todaysDailies.get(floor);
    }

    public boolean isChestAt(Location loc) {
        for (DailyEntry entry : todaysDailies.values()) {
            if (entry.type != DailyType.TREASURE || entry.chestWorld == null) continue;
            if (!entry.chestWorld.equals(loc.getWorld().getName())) continue;
            if (loc.getBlockX() == (int) Math.floor(entry.chestX)
                    && loc.getBlockY() == (int) Math.floor(entry.chestY)
                    && loc.getBlockZ() == (int) Math.floor(entry.chestZ)) {
                return true;
            }
        }
        return false;
    }

    public DailyEntry getEntryByChestLocation(Location loc) {
        for (DailyEntry entry : todaysDailies.values()) {
            if (entry.type != DailyType.TREASURE || entry.chestWorld == null) continue;
            if (!entry.chestWorld.equals(loc.getWorld().getName())) continue;
            if (loc.getBlockX() == (int) Math.floor(entry.chestX)
                    && loc.getBlockY() == (int) Math.floor(entry.chestY)
                    && loc.getBlockZ() == (int) Math.floor(entry.chestZ)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 抽選された報酬アイテム一覧(entry.rewardItems)を、まとめてプレイヤーに付与する。
     * Col/EXPはこのメソッドでは触らない（討伐・納品は既存クエスト完了処理で、宝箱は呼び出し側で別途付与するため）。
     */
    public void grantRewardItems(org.bukkit.entity.Player player, DailyEntry entry) {
        for (RewardStack stack : entry.rewardItems) {
            org.bukkit.inventory.ItemStack rewardItem = null;
            sac.plugin.info.SACplugin.item.SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(stack.itemId);
            if (weapon != null) {
                rewardItem = plugin.getSaoItemManager().createWeaponItem(weapon);
            } else {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(stack.itemId);
                if (genItem != null) {
                    rewardItem = plugin.getSaoItemManager().createGeneralItem(genItem);
                } else if (plugin.getFishingManager() != null) {
                    sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(stack.itemId);
                    if (fishData != null) {
                        rewardItem = plugin.getFishingManager().createFishItem(fishData);
                    }
                }
            }
            if (rewardItem == null) continue;

            rewardItem.setAmount(Math.max(1, stack.amount));
            java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> over = player.getInventory().addItem(rewardItem);
            for (org.bukkit.inventory.ItemStack drop : over.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            String displayName = rewardItem.hasItemMeta() && rewardItem.getItemMeta().hasDisplayName()
                    ? rewardItem.getItemMeta().getDisplayName() : stack.itemId;
            player.sendMessage("§b[System] §f" + displayName + " §ax" + rewardItem.getAmount() + " §fを受け取った！");
        }
    }
}