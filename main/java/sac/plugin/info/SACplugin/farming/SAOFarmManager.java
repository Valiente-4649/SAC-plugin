package sac.plugin.info.SACplugin.farming;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SAOFarmManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, CropData> cropDataMap = new HashMap<>();
    private final Map<String, FarmData> activeFarms = new HashMap<>();

    // --- JSON用のデータクラス ---
    public static class CropData {
        public String id; public String name; public String category; public String description;
        public int price; public String resultItemId; public String rareResultItemId; public int fertilizerStage;
    }
    public static class PlantedCrop {
        public String cropId; public int stage = 1; public boolean isWatered = false;
        public int dryDays = 0; public boolean isFertilized = false;
        public int waterUses = 0;
    }
    public static class FarmData {
        public String ownerUuid; public String ownerName; public String worldName;
        public int remainingDays = 0; public int emptyDays = 0;
        public Map<String, PlantedCrop> crops = new HashMap<>(); // Key: "x,y,z"
    }

    public SAOFarmManager(SACplugin plugin) {
        this.plugin = plugin;
        loadData();
    }

    public void loadData() {
        File file = new File(plugin.getDataFolder(), "farming.json");
        if (!file.exists()) plugin.saveResource("farming.json", false);
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, CropData>>(){}.getType();
            Map<String, CropData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) cropDataMap.putAll(loaded);
        } catch (Exception e) { e.printStackTrace(); }

        File farmFile = new File(plugin.getDataFolder(), "active_farms.json");
        if (farmFile.exists()) {
            try (FileReader reader = new FileReader(farmFile)) {
                Type type = new TypeToken<Map<String, FarmData>>(){}.getType();
                Map<String, FarmData> loaded = new Gson().fromJson(reader, type);
                if (loaded != null) activeFarms.putAll(loaded);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    public void saveFarms() {
        File farmFile = new File(plugin.getDataFolder(), "active_farms.json");
        try (FileWriter writer = new FileWriter(farmFile)) {
            new Gson().toJson(activeFarms, writer);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public CropData getCrop(String id) { return cropDataMap.get(id); }
    public Map<String, CropData> getAllCrops() { return cropDataMap; }
    public FarmData getFarm(String farmId) { return activeFarms.get(farmId); }
    public void addFarm(String farmId, FarmData data) { activeFarms.put(farmId, data); saveFarms(); }
    public void removeFarm(String farmId) { activeFarms.remove(farmId); saveFarms(); }

    /**
     * ★注意: farming.json(作物の定義)だけを再読み込みする。
     * active_farms.json(進行中の農地の状態)はプレイヤーの進行データそのものなので、
     * ここでは意図的に触らない（触るとプレイ中の農地が巻き戻る危険があるため）。
     */
    @Override
    public void reload() {
        File file = new File(plugin.getDataFolder(), "farming.json");
        if (!file.exists()) plugin.saveResource("farming.json", false);
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, CropData>>(){}.getType();
            Map<String, CropData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                cropDataMap.clear();
                cropDataMap.putAll(loaded);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ==========================================
    // ★ Farm Point の識別に「座標文字列」を使う
    //    アーマースタンドの UUID はリロード後に変わる場合があるため、
    //    ワールド名+ブロック座標を farmId として使用します。
    //    例: "world:100:-64:200"
    // ==========================================

    /** アーマースタンドエンティティから farmId (座標文字列) を生成する */
    public static String toFarmId(org.bukkit.entity.Entity entity) {
        org.bukkit.Location loc = entity.getLocation();
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /** 座標から最寄りの Farm Point の farmId (座標文字列) を返す */
    public String getFarmIdAt(Location loc) {
        for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 4, 3, 4)) {
            if (e instanceof org.bukkit.entity.ArmorStand && e.getScoreboardTags().contains("sao_farm_point")) {
                int dx = Math.abs(e.getLocation().getBlockX() - loc.getBlockX());
                int dz = Math.abs(e.getLocation().getBlockZ() - loc.getBlockZ());
                if (dx <= 2 && dz <= 2) return toFarmId(e);
            }
        }
        return null;
    }

    /** Farm Point エンティティに対応する FarmData を返す（メニューを開くときに使う）*/
    public FarmData getFarmByEntity(org.bukkit.entity.Entity entity) {
        return activeFarms.get(toFarmId(entity));
    }

    // ==========================================
    // ★修正: 成長処理を「日の出(4:00)」「日の入り(18:00)」に同期させる
    // TimeManagerから isDay (true=日の出時, false=日の入り時) を渡して呼び出す
    // ==========================================
    public void executeGrowthLogicPublic(boolean isMorning) {
        executeGrowthLogic(isMorning);
    }

    /** 旧仕様との互換用（呼び出されなくなったが、念のため残す） */
    @Deprecated
    public void processDailyGrowth() {
        executeGrowthLogic(true);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            executeGrowthLogic(false);
        }, 6000L);
    }

    private void executeGrowthLogic(boolean isMorning) {
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, FarmData> entry : activeFarms.entrySet()) {
            FarmData farm = entry.getValue();

            if (isMorning) {
                farm.remainingDays--;
                if (farm.remainingDays <= 0) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                if (farm.crops.isEmpty()) {
                    farm.emptyDays++;
                    if (farm.emptyDays >= 8) {
                        toRemove.add(entry.getKey());
                        continue;
                    }
                } else {
                    farm.emptyDays = 0;
                }
            }

            List<String> deadCrops = new ArrayList<>();
            org.bukkit.World w = Bukkit.getWorld(farm.worldName);
            if (w == null) continue;

            boolean grewAtLeastOnce = false; // この農地で1回でも成長があったか

            for (Map.Entry<String, PlantedCrop> cropEntry : farm.crops.entrySet()) {
                PlantedCrop crop = cropEntry.getValue();
                if (crop.stage >= 4) continue;

                String[] pts = cropEntry.getKey().split(",");
                int x = Integer.parseInt(pts[0]); int y = Integer.parseInt(pts[1]); int z = Integer.parseInt(pts[2]);
                org.bukkit.block.Block farmland = w.getBlockAt(x, y, z);
                org.bukkit.block.Block cropBlock = farmland.getRelative(org.bukkit.block.BlockFace.UP);

                if (crop.isWatered) {
                    crop.stage++;
                    crop.dryDays = 0;
                    crop.waterUses++;
                    grewAtLeastOnce = true; // ★ 成長フラグを立てる

                    if (crop.waterUses >= 2) {
                        crop.isWatered = false;
                        crop.waterUses = 0;

                        if (farmland.getType() == org.bukkit.Material.FARMLAND) {
                            org.bukkit.block.data.type.Farmland fData = (org.bukkit.block.data.type.Farmland) farmland.getBlockData();
                            fData.setMoisture(0);
                            farmland.setBlockData(fData);
                        }
                    }

                    if (cropBlock.getType() == org.bukkit.Material.WHEAT) {
                        org.bukkit.block.data.Ageable ageable = (org.bukkit.block.data.Ageable) cropBlock.getBlockData();
                        int age = (crop.stage == 2) ? 2 : (crop.stage == 3) ? 5 : 7;
                        ageable.setAge(age);
                        cropBlock.setBlockData(ageable);
                    }
                } else if (isMorning) {
                    crop.dryDays++;
                    if (crop.dryDays >= 24) {
                        cropBlock.setType(org.bukkit.Material.DEAD_BUSH);
                        deadCrops.add(cropEntry.getKey());
                    }
                }
            }
            deadCrops.forEach(farm.crops::remove);

            // ★ この農地で成長があった場合、オーナーがオンラインなら通知する
            if (grewAtLeastOnce) {
                org.bukkit.entity.Player owner = Bukkit.getPlayerExact(farm.ownerName);
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage("§a[System] §f作物が1段階成長したようだ！");
                }
            }
        }
        toRemove.forEach(id -> {
            // 契約切れ・放置による自動解約時も物理リセットを行う
            resetPhysicalFarm(id);
            activeFarms.remove(id);
        });
        saveFarms();

        if (isMorning) {
            Bukkit.getLogger().info("[SAO Farming] 朝の成長処理完了");
        } else {
            Bukkit.getLogger().info("[SAO Farming] お昼の成長処理完了");
        }
    }

    // ==========================================
    // ★ 農地の物理的なリセット
    //    farmId が座標文字列("world:x:y:z")の場合と
    //    旧形式(UUID文字列)の場合の両方に対応します
    // ==========================================
    public void resetPhysicalFarm(String farmId) {
        org.bukkit.World world = null;
        int centerX = 0, centerY = 0, centerZ = 0;

        // 新形式: "worldName:x:y:z"
        if (farmId.contains(":")) {
            String[] parts = farmId.split(":");
            // ワールド名にコロンが含まれる場合を考慮して後ろ3要素を座標とする
            int len = parts.length;
            try {
                centerZ = Integer.parseInt(parts[len - 1]);
                centerY = Integer.parseInt(parts[len - 2]);
                centerX = Integer.parseInt(parts[len - 3]);
                String worldName = String.join(":", java.util.Arrays.copyOfRange(parts, 0, len - 3));
                world = Bukkit.getWorld(worldName);
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return;
            }
        } else {
            // 旧形式: UUID → エンティティから座標を取得
            try {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(java.util.UUID.fromString(farmId));
                if (entity == null) return;
                org.bukkit.Location loc = entity.getLocation().getBlock().getLocation();
                world = loc.getWorld();
                centerX = loc.getBlockX();
                centerY = loc.getBlockY();
                centerZ = loc.getBlockZ();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                return;
            }
        }

        if (world == null) return;

        // 浮き文字の2ブロック下が耕地
        int farmlandY = centerY - 2;

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                org.bukkit.block.Block farmland = world.getBlockAt(centerX + x, farmlandY, centerZ + z);
                if (farmland.getType() == org.bukkit.Material.FARMLAND) {
                    org.bukkit.block.data.type.Farmland fData = (org.bukkit.block.data.type.Farmland) farmland.getBlockData();
                    fData.setMoisture(0);
                    farmland.setBlockData(fData);

                    org.bukkit.block.Block plant = farmland.getRelative(org.bukkit.block.BlockFace.UP);
                    if (plant.getType() == org.bukkit.Material.WHEAT || plant.getType() == org.bukkit.Material.DEAD_BUSH) {
                        plant.setType(org.bukkit.Material.AIR);
                    }
                }
            }
        }
    }
}
