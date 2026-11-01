package sac.plugin.info.SACplugin.npc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class SAOQuestManager implements Reloadable {
    private final SACplugin plugin;
    private Map<String, SAOQuestData> quests = new HashMap<>();
    // ★追加: デイリー等、動的に登録されたクエストのIDを記録しておく
    // (loadQuests()でquests.jsonから再読み込みする際に消えてしまわないよう退避・復元するため)
    private final Set<String> dynamicQuestIds = new HashSet<>();
    private File npcFile;
    private YamlConfiguration npcConfig;
    // ==========================================
    // ★修正: getLocalizedName()はPaper 1.21.1で信頼できないため、
    // クエスト管理GUIのアイテム識別にはPersistentDataContainerを使う。
    // ==========================================
    private final org.bukkit.NamespacedKey questAdminFloorKey;
    private final org.bukkit.NamespacedKey questAdminIdKey;

    public SAOQuestManager(SACplugin plugin) {
        this.plugin = plugin;
        this.npcFile = new File(plugin.getDataFolder(), "spawned_npcs.yml");
        this.npcConfig = YamlConfiguration.loadConfiguration(npcFile);
        this.questAdminFloorKey = new org.bukkit.NamespacedKey(plugin, "quest_admin_floor");
        this.questAdminIdKey = new org.bukkit.NamespacedKey(plugin, "quest_admin_id");
        loadQuests();
    }

    public void loadQuests() {
        // ★修正: quests.jsonの再読み込みで動的登録クエスト(デイリー等)が消えてしまわないよう、
        // 現在登録されている動的クエストを一旦退避しておく
        Map<String, SAOQuestData> preservedDynamic = new HashMap<>();
        if (quests != null) {
            for (String id : dynamicQuestIds) {
                SAOQuestData d = quests.get(id);
                if (d != null) preservedDynamic.put(id, d);
            }
        }

        File file = new File(plugin.getDataFolder(), "quests.json");
        if (!file.exists()) {
            plugin.saveResource("quests.json", false);
        }

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<Map<String, SAOQuestData>>(){}.getType();
            Map<String, SAOQuestData> loaded = gson.fromJson(reader, type);
            quests = (loaded != null) ? loaded : new HashMap<>();

            // ★修正: 退避しておいた動的クエスト(デイリー等)を復元する
            quests.putAll(preservedDynamic);

            plugin.getLogger().info("クエストデータを読み込みました。");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reload() {
        loadQuests();
    }

    public SAOQuestData getQuest(String id) {
        return quests.get(id);
    }

    /** 全クエストデータを返す(クエスト報酬の後方互換チェックなどに使う) */
    public java.util.Collection<SAOQuestData> getAllQuests() {
        return quests.values();
    }

    /**
     * デイリークエストなど、quests.json以外から動的に生成したクエストデータを登録する。
     * 既に同じIDが存在する場合は上書きされる（毎日のデイリー再抽選時に使用）。
     */
    public void registerDynamicQuest(SAOQuestData data) {
        if (quests == null) quests = new HashMap<>();
        quests.put(data.id, data);
        dynamicQuestIds.add(data.id);
    }

    /** 動的登録したクエスト（デイリーなど）を削除する */
    public void unregisterDynamicQuest(String id) {
        if (quests != null) quests.remove(id);
        dynamicQuestIds.remove(id);
    }

    // NPC名からクエストを探す（NPCクリック判定用）
    public SAOQuestData getQuestByNpcName(String npcName) {
        for (SAOQuestData data : quests.values()) {
            if (data.npcName.equals(npcName)) return data;
        }
        return null;
    }
    // ★追加: 階層選択メニューを開く
    public void openQuestFloorMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b《Quest Floor Selection》");
        Set<Integer> floors = new HashSet<>();
        for (SAOQuestData q : quests.values()) {
            floors.add(q.floor);
        }
        List<Integer> sortedFloors = new ArrayList<>(floors);
        Collections.sort(sortedFloors);

        for (int f : sortedFloors) {
            ItemStack item = new ItemStack(Material.BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§e第 " + f + " 層 クエスト一覧");
            meta.getPersistentDataContainer().set(questAdminFloorKey, org.bukkit.persistence.PersistentDataType.INTEGER, f);
            item.setItemMeta(meta);
            inv.addItem(item);
        }
        player.openInventory(inv);
    }

    // ★追加: 指定した階層のクエスト一覧メニューを開く
    public void openQuestListMenu(Player player, int floor) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b《Floor " + floor + " Quests》");
        for (SAOQuestData q : quests.values()) {
            if (q.floor == floor) {
                ItemStack item = new ItemStack(Material.VILLAGER_SPAWN_EGG);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName("§a" + q.questName);

                boolean isSpawned = checkNpcSpawned(q.id);
                String spawnStatus = isSpawned ? "§c[設置済み]" : "§b[未設置]";

                List<String> lore = new ArrayList<>();
                lore.add("§fNPC名: " + q.npcName);
                lore.add("§f状態: " + spawnStatus);
                lore.add("");
                lore.add("§eクリックで現在地にNPCを召喚します");
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(questAdminIdKey, org.bukkit.persistence.PersistentDataType.STRING, q.id);
                item.setItemMeta(meta);
                inv.addItem(item);
            }
        }
        player.openInventory(inv);
    }

    // ★変更: 確実なファイルチェック方式
    public boolean checkNpcSpawned(String questId) {
        // spawned_npcs.yml にそのクエストIDが登録されていれば true
        return npcConfig.contains("npcs." + questId);
    }

    // ★追加: NPCをプレイヤーの現在地に召喚する
    public void spawnQuestNPC(Player player, String questId) {
        SAOQuestData q = getQuest(questId);
        if (q == null) return;

        Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
        npc.setCustomName(q.npcName);
        npc.setCustomNameVisible(true);
        npc.setAI(false); // 動かないように固定
        npc.setInvulnerable(true); // 無敵化
        // ★これを追加！これで一切の声（サウンド）が消えます
        npc.setSilent(true);
        npc.addScoreboardTag("QUEST_NPC_" + questId); // 管理用のタグを付与
        // ==========================================
        // ★追加: 設置したという事実と、場所をファイルに保存
        // ==========================================
        npcConfig.set("npcs." + questId + ".uuid", npc.getUniqueId().toString());
        npcConfig.set("npcs." + questId + ".world", player.getLocation().getWorld().getName());
        npcConfig.set("npcs." + questId + ".x", player.getLocation().getX());
        npcConfig.set("npcs." + questId + ".y", player.getLocation().getY());
        npcConfig.set("npcs." + questId + ".z", player.getLocation().getZ());
        try {
            npcConfig.save(npcFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        player.sendMessage("§a[SAO] クエストNPC「" + q.npcName + "」を召喚しました！");
    }
    // ==========================================
    // ★追加: 既存のNPCを検知してYMLファイルに自動登録する
    // ==========================================
    public int syncNearbyOldNPCs(org.bukkit.entity.Player player, double radius) {
        int syncedCount = 0;

        // 送っていただいた「プレイヤーの近くを探す」アプローチ（Spigotの便利メソッドを使います）
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            // 村人以外はスルー
            if (!(entity instanceof org.bukkit.entity.Villager)) continue;

            org.bukkit.entity.Villager npc = (org.bukkit.entity.Villager) entity;
            String customName = npc.getCustomName();

            // 名前が設定されていない村人はスルー
            if (customName == null) continue;

            // quests.json のデータと名前を照らし合わせる
            for (SAOQuestData q : quests.values()) {
                if (customName.equals(q.npcName)) {
                    String questId = q.id;

                    // ① 古いNPCに、新しい管理用のタグを付与してあげる
                    if (!npc.getScoreboardTags().contains("QUEST_NPC_" + questId)) {
                        npc.addScoreboardTag("QUEST_NPC_" + questId);
                        npc.setSilent(true); // ついでに声も消しておく
                    }

                    // ② YMLファイルに登録されていなければ座標を記録
                    if (!npcConfig.contains("npcs." + questId)) {
                        npcConfig.set("npcs." + questId + ".uuid", npc.getUniqueId().toString());
                        npcConfig.set("npcs." + questId + ".world", npc.getLocation().getWorld().getName());
                        npcConfig.set("npcs." + questId + ".x", npc.getLocation().getX());
                        npcConfig.set("npcs." + questId + ".y", npc.getLocation().getY());
                        npcConfig.set("npcs." + questId + ".z", npc.getLocation().getZ());
                        syncedCount++;
                    }
                    break; // このNPCの処理は完了したので次のエンティティへ
                }
            }
        }

        // セーブ処理
        if (syncedCount > 0) {
            try {
                npcConfig.save(npcFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return syncedCount;
    }
}