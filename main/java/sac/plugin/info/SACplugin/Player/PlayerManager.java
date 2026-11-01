package sac.plugin.info.SACplugin.Player;

import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {
    private final SACplugin plugin;
    // プレイヤーごとにデータを保持するMap。
    // インスタンス自体は変えないので final を使用。
    private final Map<UUID, PlayerData> statsMap = new HashMap<>();

    public PlayerManager(SACplugin plugin) {
        this.plugin = plugin;
    }
    public void setupPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        File userDataFile = new File(plugin.getDataFolder(), "userdata/" + uuid + ".yml");
        PlayerData data = new PlayerData();

        if (userDataFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(userDataFile);
            // ★修正: config.getInt(key) はキーが無いと無条件で0を返すため、
            // PlayerDataのデフォルト値を握り潰してしまう。明示的にデフォルト値を指定する。
            data.level = config.getInt("level", 1);
            data.skillPoint = config.getInt("skillPoint", 0);
            data.str = config.getInt("str", 0);
            data.agi = config.getInt("agi", 0);
            data.col = config.getLong("col", 400);
            data.lastSkillResetTime = config.getLong("lastSkillResetTime", 0L);
            data.exp = config.getInt("exp", 0);

            data.maxHP = config.getDouble("maxHP", 250.0);
            data.currentHP = config.getDouble("currentHP", 250.0);
            // ★追加: 満腹ゲージのロード(未保存なら満腹100%スタート)
            data.hunger = config.getDouble("hunger", 100.0);
            data.vitality = Math.max(0, Math.min(100, config.getDouble("vitality", 100.0)));
            data.restingOffline = config.getBoolean("restingOffline", false);
            data.vitalityLogoutAt = config.getLong("vitalityLogoutAt", 0L);
            // ★追加: 腹持ち%(隠しバッファ)のロード
            data.satietyBuffer = config.getDouble("satietyBuffer", 0.0);

            // ★【追加】クエスト進行状況の読み込み
            data.currentQuestId = config.getString("currentQuestId", "");
            data.questProgress = config.getInt("questProgress", 0);
            data.questCompleted = config.getBoolean("questCompleted", false);

            // ★【追加】選択中のソードスキルを読み込む（保存されていなければ初期値の"vertical"にする）
            data.selectedSkillId = config.getString("selectedSkillId", "vertical");
            // ★これを追加: 死亡状態を読み込む
            data.isDead = config.getBoolean("isDead", false);
            // ★追加: レベル必要経験値カーブ変更の一度きり移行が済んでいるかどうか
            data.expCurveMigrated = config.getBoolean("expCurveMigrated", false);
            data.expCurveMigratedV2 = config.getBoolean("expCurveMigratedV2", false);
            data.skillSlotMigrated = config.getBoolean("skillSlotMigrated", false);
            data.questSkillUnlockChecked = config.getBoolean("questSkillUnlockChecked", false);

            if (config.getConfigurationSection("questCounts") != null) {
                for (String key : config.getConfigurationSection("questCounts").getKeys(false)) {
                    data.questCounts.put(key, config.getInt("questCounts." + key));
                }
            }

            // ★【追加】熟練度レベルの読み込み
            if (config.getConfigurationSection("proficiency") != null) {
                for (String key : config.getConfigurationSection("proficiency").getKeys(false)) {
                    data.proficiency.put(key, config.getInt("proficiency." + key));
                }
            }

            // ★【追加】進捗(経験値)の読み込み
            if (config.getConfigurationSection("usedDurability") != null) {
                for (String key : config.getConfigurationSection("usedDurability").getKeys(false)) {
                    data.usedDurability.put(key, config.getInt("usedDurability." + key));
                }
            }
            if (config.getConfigurationSection("proficiencyExp") != null) {
                for (String key : config.getConfigurationSection("proficiencyExp").getKeys(false)) {
                    data.proficiencyExp.put(key, config.getInt("proficiencyExp." + key));
                }
            }
            // ★【追加】装備アイテムの読み込み
            if (config.getConfigurationSection("equipment") != null) {
                for (String key : config.getConfigurationSection("equipment").getKeys(false)) {
                    data.equipment.put(key, config.getItemStack("equipment." + key));
                }
            }
            // ★【追加】フレンドの読み込み
            if (config.getStringList("friends") != null) {
                for (String friendUuidStr : config.getStringList("friends")) {
                    try {
                        data.friends.add(UUID.fromString(friendUuidStr));
                    } catch (Exception ignored) {}
                }
            }
            // ==========================================
            // ★追加: 装備中スキルの読み込み
            // ==========================================
            if (config.getStringList("equippedSkills") != null && !config.getStringList("equippedSkills").isEmpty()) {
                java.util.List<String> eqList = config.getStringList("equippedSkills");
                for (int i = 0; i < data.equippedSkills.length && i < eqList.size(); i++) {
                    data.equippedSkills[i] = eqList.get(i);
                }
            }
            // ★これを追加: ストレージデータの読み込み
            if (config.contains("storage")) {
                java.util.List<?> list = config.getList("storage");
                if (list != null) {
                    for (int i = 0; i < Math.min(list.size(), 54); i++) {
                        Object itemObj = list.get(i);
                        if (itemObj instanceof org.bukkit.inventory.ItemStack) {
                            data.storage[i] = (org.bukkit.inventory.ItemStack) itemObj;
                        }
                    }
                }
            }
            if (config.contains("caughtFishes")) {
                data.caughtFishes = config.getStringList("caughtFishes");
            }
            // ★追加: 討伐済みボスリストの読み込み
            if (config.contains("defeatedBosses")) {
                data.defeatedBosses = config.getStringList("defeatedBosses");
            }
            if (config.contains("completedTutorials")) {
                data.completedTutorials = config.getStringList("completedTutorials");
            }
            // ★修正: unlockedWeaponTypes(体術などクエスト解放スキル)が今まで一切
            // 保存・読み込みされておらず、ログインし直すと解放状態が消えていたバグを修正
            if (config.contains("unlockedWeaponTypes")) {
                data.unlockedWeaponTypes = config.getStringList("unlockedWeaponTypes");
            }
            // ★変更: デイリークエストの最終クリア日(全階層共通)の読み込み
            data.lastDailyClearDate = config.getString("lastDailyClearDate", "");
            // ★追加: 投票報酬の最終取得日の読み込み
            data.lastVoteDate = config.getString("lastVoteDate", "");
            // ★追加: デスペナルティ用の死亡回数・最終ログイン時刻
            data.deathCount = config.getInt("deathCount", 0);
            data.lastLoginTime = config.getLong("lastLoginTime", 0L);
        }

        // ==========================================
        // ★追加: 「レベル10ごとに必要経験値+500」仕様変更に伴う、既存プレイヤーの
        // 一度きりの再計算移行。
        // 旧カーブ(500 + 75×Lv×(Lv-1))で「これまでに稼いだ総経験値」を逆算し、
        // 新カーブ(500 + 75×Lv×(Lv-1) + (Lv/10)×500)でレベル・残りexpを再計算する。
        // 新規プレイヤーはexpCurveMigratedV2が最初からtrueになることはないため、
        // 誤って新規プレイヤーに謎のレベル変動が起きないよう、必ずuserdataファイルが
        // 既に存在していた(=既存プレイヤー)場合のみ判定する。
        // ==========================================
        boolean expCurveNeedsSave = false;
        if (userDataFile.exists() && !data.expCurveMigratedV2) {
            long totalOldExp = 0;
            for (int lv = 1; lv < data.level; lv++) {
                totalOldExp += 500L + 75L * lv * (lv - 1);
            }
            totalOldExp += data.exp; // 現在のレベルで貯めている分も加算

            int maxLvl = plugin.getLimitManager().getMaxPlayerLevel();
            int newLevel = 1;
            long remaining = totalOldExp;
            while (newLevel < maxLvl) {
                long required = 500L + 75L * newLevel * (newLevel - 1) + (newLevel / 10) * 500L;
                if (remaining >= required) {
                    remaining -= required;
                    newLevel++;
                } else {
                    break;
                }
            }

            int oldLevel = data.level;
            data.level = newLevel;
            data.exp = (int) remaining;
            data.str = 0;
            data.agi = 0;
            data.skillPoint = Math.max(0, newLevel - 1);
            data.expCurveMigratedV2 = true;

            plugin.getLogger().info("[SAO] " + player.getName() + " の必要経験値カーブを新仕様(10Lvごとに+500)に移行しました(旧Lv." + oldLevel + " -> 新Lv." + newLevel + ")");
            player.sendMessage("§e[System] §fLEVEL UPに必要な経験値の仕様が変更されました。");
            player.sendMessage("§b[System] §r§7これまでの総経験値をもとに再計算した結果、レベルが §bLv." + oldLevel + " §7から §bLv." + newLevel + " §7になりました。");
            player.sendMessage("§b[System] §r§7STR/AGIは §c0 §7にリセットされ、代わりに §aスキルポイント " + data.skillPoint + " §7を獲得しました。Menuから振り直してください。");

            expCurveNeedsSave = true;
        } else if (!userDataFile.exists()) {
            // 新規プレイヤーは移行対象ではないので、最初からフラグを立てておく
            data.expCurveMigratedV2 = true;
        }

        // ==========================================
        // ★追加: 6固定スロット→レベル制の可変スロットへの一度きりの移行。
        // 既にセットされていたスキルは、熟練度・経験値をそのまま保持した状態で
        // 「外す」(＝equippedSkillsだけを空にする。proficiency/proficiencyExp/usedDurabilityは触らない)。
        // 新仕様のスロットに再セットすれば、そのまま同じ熟練度で使い続けられる。
        // ==========================================
        boolean skillSlotNeedsSave = false;
        if (userDataFile.exists() && !data.skillSlotMigrated) {
            boolean hadAnyEquipped = false;
            for (int i = 0; i < data.equippedSkills.length; i++) {
                if (data.equippedSkills[i] != null && !data.equippedSkills[i].isEmpty()) {
                    hadAnyEquipped = true;
                    data.equippedSkills[i] = ""; // ★熟練度は一切リセットせず、装備だけ外す
                }
            }
            data.skillSlotMigrated = true;
            skillSlotNeedsSave = true;

            plugin.getLogger().info("[SAO] " + player.getName() + " のスキルスロットを可変制に移行しました(既存のセットは解除、熟練度は維持)。");
            player.sendMessage("§e[System] §fスキルスロットの仕様が変更されました。");
            if (hadAnyEquipped) {
                player.sendMessage("§b[System] §r§7以前セットしていたスキルは一旦すべて外れましたが、熟練度はそのまま保持されています。");
                player.sendMessage("§b[System] §r§7Menuのスキル設定から、新しいスロットに再度セットしてください。");
            }
        } else if (!userDataFile.exists()) {
            // 新規プレイヤーは移行対象ではないので、最初からフラグを立てておく
            data.skillSlotMigrated = true;
        }

        // ==========================================
        // ★追加: unlockedWeaponTypesが保存されていなかった不具合により、
        // 体術などクエストで解放したはずのスキルが消えてしまった人向けの復元チェック。
        // 「そのスキルを解放するクエスト(rewardSkillUnlock)を過去にクリアしている」
        // (questCountsのクリア回数が1以上)ことをもとに、一度だけ復元する。
        // 以後のログインでは二度とチェックしない(questSkillUnlockCheckedで制御)。
        // ==========================================
        boolean questSkillUnlockNeedsSave = false;
        if (userDataFile.exists() && !data.questSkillUnlockChecked) {
            if (data.unlockedWeaponTypes == null) data.unlockedWeaponTypes = new java.util.ArrayList<>();

            for (sac.plugin.info.SACplugin.npc.SAOQuestData quest : plugin.getQuestManager().getAllQuests()) {
                String skill = quest.rewardSkillUnlock;
                if (skill == null || skill.isEmpty()) continue;
                if (data.unlockedWeaponTypes.contains(skill)) continue;

                // そのスキルを解放するクエストを過去にクリアしている(クリア回数1以上)なら
                // 過去に解放していた証拠とみなして復元する
                int clearCount = data.questCounts != null ? data.questCounts.getOrDefault(quest.id, 0) : 0;
                if (clearCount > 0) {
                    data.unlockedWeaponTypes.add(skill);
                    plugin.getLogger().info("[SAO] " + player.getName() + " の「" + skill + "」解放状態を復元しました(不具合復旧)。");
                    player.sendMessage("§e[System] §f「" + skill + "」の解放状態を復元しました。スキルカタログから再度セットできます。");
                    questSkillUnlockNeedsSave = true;
                }
            }

            data.questSkillUnlockChecked = true;
            questSkillUnlockNeedsSave = true;
        } else if (!userDataFile.exists()) {
            // 新規プレイヤーは移行対象ではないので、最初からフラグを立てておく
            data.questSkillUnlockChecked = true;
        }

        // ==========================================
        // ★追加: 鍛造スキル細分化への移行処理
        // 旧仕様の単一「鍛冶」スキル(Forgingという内部キーで熟練度管理していた)を持つ
        // プレイヤーを検知し、新しい「斬撃武器鍛造」「刺突武器鍛造」に熟練度を引き継ぐ。
        // 新規プレイヤーはそもそも"Forging"キーを持たないため、この分岐には入らない。
        // ==========================================
        if (data.proficiency.containsKey("Forging")) {
            int oldLevel = data.proficiency.get("Forging");
            int oldExp = data.proficiencyExp.getOrDefault("Forging", 0);

            data.proficiency.put("斬撃武器鍛造", oldLevel);
            data.proficiency.put("刺突武器鍛造", oldLevel);
            data.proficiencyExp.put("斬撃武器鍛造", oldExp);
            data.proficiencyExp.put("刺突武器鍛造", oldExp);

            data.proficiency.remove("Forging");
            data.proficiencyExp.remove("Forging");
            data.usedDurability.remove("Forging");

            // 装備スロットに旧「鍛冶」が入っていれば「斬撃武器鍛造」に置き換える
            for (int i = 0; i < data.equippedSkills.length; i++) {
                if ("鍛冶".equals(data.equippedSkills[i])) {
                    data.equippedSkills[i] = "斬撃武器鍛造";
                }
            }

            plugin.getLogger().info("[SAO] " + player.getName() + " の鍛造スキルを新システムに移行しました(旧Lv." + oldLevel + " -> 斬撃武器鍛造/刺突武器鍛造)");
            player.sendMessage("§e[System] §f鍛造スキルが刷新されました。これまでの熟練度(Lv." + oldLevel + ")は§b「斬撃武器鍛造」§fと§b「刺突武器鍛造」§fの両方に引き継がれています。");
            player.sendMessage("§b[System] §r§7スロットには「斬撃武器鍛造」が自動でセットされました。「刺突武器鍛造」を使うには空いているスロットに手動でセットしてください。");

            statsMap.put(uuid, data);
            savePlayerData(player, data); // ★移行結果を即座に保存(離脱による消失防止)
        } else {
            statsMap.put(uuid, data);
            if (expCurveNeedsSave || skillSlotNeedsSave || questSkillUnlockNeedsSave) {
                savePlayerData(player, data); // ★経験値カーブ・スキルスロット・クエスト解放復元も即座に保存(離脱による消失防止)
            }
        }

        syncHealth(player, data);

        // ★安全策: maxHPが0以下だとNaN(0除算)が発生し、以降の処理が全て止まってしまう
        if (data.maxHP <= 0) {
            plugin.getLogger().warning("[SAO] " + player.getName() + " のmaxHPが異常値(" + data.maxHP + ")だったため250.0に補正しました。");
            data.maxHP = 250.0;
        }
        if (data.currentHP <= 0) {
            data.currentHP = data.maxHP;
        }

        double visualHealth = (data.currentHP / data.maxHP) * 20.0;
        player.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));
    }

    public void savePlayerData(Player player, PlayerData data) {
        if (data == null) return;
        UUID uuid = player.getUniqueId();

        File userDataFile = new File(plugin.getDataFolder(), "userdata/" + uuid + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(userDataFile);

        config.set("level", data.level);
        config.set("skillPoint", data.skillPoint);
        config.set("str", data.str);
        config.set("agi", data.agi);
        config.set("col", data.col);
        config.set("lastSkillResetTime", data.lastSkillResetTime);
        config.set("exp", data.exp);

        // 【修正ポイント】player.getHealth() ではなく data.currentHP を保存！
        config.set("currentHP", data.currentHP);
        config.set("maxHP", data.maxHP);
        // ★追加: 満腹ゲージの保存
        config.set("hunger", data.hunger);
        config.set("vitality", data.vitality);
        config.set("restingOffline", data.restingOffline);
        config.set("vitalityLogoutAt", data.vitalityLogoutAt);
        // ★追加: 腹持ち%(隠しバッファ)の保存
        config.set("satietyBuffer", data.satietyBuffer);

        // ★【追加】クエスト進行状況の保存
        config.set("currentQuestId", data.currentQuestId);
        config.set("questProgress", data.questProgress);
        config.set("questCompleted", data.questCompleted);

        // ★【追加】選択中のソードスキルを保存する
        config.set("selectedSkillId", data.selectedSkillId);
        // ★これを追加: 死亡状態を保存する
        config.set("isDead", data.isDead);
        config.set("expCurveMigrated", data.expCurveMigrated);
        config.set("expCurveMigratedV2", data.expCurveMigratedV2);
        config.set("skillSlotMigrated", data.skillSlotMigrated);
        config.set("questSkillUnlockChecked", data.questSkillUnlockChecked);
        config.set("caughtFishes", data.caughtFishes);
        // ★追加: 討伐済みボスリストの保存
        config.set("defeatedBosses", data.defeatedBosses);
        config.set("completedTutorials", data.completedTutorials);
        config.set("unlockedWeaponTypes", data.unlockedWeaponTypes);

        config.set("questCounts", null);
        for (String key : data.questCounts.keySet()) {
            config.set("questCounts." + key, data.questCounts.get(key));
        }

        config.set("proficiency", null);
        for (String key : data.proficiency.keySet()) {
            config.set("proficiency." + key, data.proficiency.get(key));
        }

        // ★【追加】進捗(経験値)の保存
        config.set("usedDurability", null);
        for (String key : data.usedDurability.keySet()) {
            config.set("usedDurability." + key, data.usedDurability.get(key));
        }

        config.set("proficiencyExp", null);
        for (String key : data.proficiencyExp.keySet()) {
            config.set("proficiencyExp." + key, data.proficiencyExp.get(key));
        }
        // ★【追加】装備アイテムの保存
        config.set("equipment", null);
        for (String key : data.equipment.keySet()) {
            config.set("equipment." + key, data.equipment.get(key));
        }
        // ★【追加】フレンドの保存
        java.util.List<String> friendStrs = new java.util.ArrayList<>();
        for (UUID friendUuid : data.friends) {
            friendStrs.add(friendUuid.toString());
        }
        config.set("friends", friendStrs);
        // ストレージデータの保存
        // ItemStackの配列をListに変換してYMLに安全に保存します
        config.set("storage", java.util.Arrays.asList(data.storage));
        // ==========================================
        // ★追加: 装備中スキルの保存
        // ==========================================
        java.util.List<String> eqListToSave = new java.util.ArrayList<>();
        for (int i = 0; i < data.equippedSkills.length; i++) {
            eqListToSave.add(data.equippedSkills[i] != null ? data.equippedSkills[i] : "");
        }
        config.set("equippedSkills", eqListToSave);

        // ★変更: デイリークエストの最終クリア日(全階層共通)の保存
        config.set("lastDailyClearDate", data.lastDailyClearDate);
        // ★追加: 投票報酬の最終取得日の保存
        config.set("lastVoteDate", data.lastVoteDate);
        // ★追加: デスペナルティ用の死亡回数・最終ログイン時刻
        config.set("deathCount", data.deathCount);
        config.set("lastLoginTime", data.lastLoginTime);

        try {
            config.save(userDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void syncHealth(Player player, PlayerData data) {
        // ★安全策: levelが0以下だとmaxHPが0になりNaNの原因になるため最低1を保証
        if (data.level < 1) data.level = 1;
        // ==========================================
        // ★変更: レベルアップ時のHP上昇量を250固定から、レベル帯ごとの段階式に変更。
        // Lv1は基礎250。Lv2以降は「2〜9=+50, 10〜19=+60, 20〜29=+70, 30〜39=+80...」と
        // 10レベルごとに上昇量が+10ずつ増えていく(50 + 10×(そのレベル/10))。
        // ==========================================
        double newMaxHP = 250.0;
        for (int lv = 2; lv <= data.level; lv++) {
            newMaxHP += 50 + 10 * (lv / 10);
        }
        data.maxHP = newMaxHP;

        int totalDefense = 0;
        int totalWeight = 0;
        // ★追加: 装備中の防具から得られるSTR/AGIボーナスの合計
        int totalArmorStr = 0;
        int totalArmorAgi = 0;
        org.bukkit.inventory.ItemStack chestToEquip = null;

        Map<String, String> slotTypes = new HashMap<>();
        slotTypes.put("head", "HEAD");
        slotTypes.put("chest", "CHEST");
        slotTypes.put("arm", "ARM");
        slotTypes.put("legs", "LEGS");
        slotTypes.put("boots", "BOOTS");

        for (Map.Entry<String, org.bukkit.inventory.ItemStack> entry : data.equipment.entrySet()) {
            String slotKey = entry.getKey();
            org.bukkit.inventory.ItemStack item = entry.getValue();

            if (item != null && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                java.util.List<String> lore = item.getItemMeta().getLore();
                String equipType = "";
                int defense = 0;
                int weight = 0;

                for (String line : lore) {
                    String plain = org.bukkit.ChatColor.stripColor(line);
                    if (plain.startsWith("Equip: ")) equipType = plain.replace("Equip: ", "").trim();
                        // ★修正: ボーナス反映後は "98 (+15)" のような表記になり、
                        // 文字列全体をparseIntすると失敗して0になってしまっていた。
                        // 先頭の数値部分だけを取り出してパースする。
                    else if (plain.startsWith("Defense: ")) try { defense = Integer.parseInt(plain.replace("Defense: ", "").trim().split("\\s+")[0]); } catch (Exception ignored) {}
                    else if (plain.startsWith("Weight: ")) try { weight = Integer.parseInt(plain.replace("Weight: ", "").trim().split("\\s+")[0]); } catch (Exception ignored) {}
                }

                String expectedType = slotTypes.get(slotKey);
                if (expectedType != null && expectedType.equalsIgnoreCase(equipType)) {
                    totalDefense += defense;
                    totalWeight += weight;
                    // ★追加: 金属防具鍛造のSTR/AGIボーナス(PDCに保持、1点=1のまま)を加算
                    totalArmorStr += sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.getBonusStat(item, "STR");
                    totalArmorAgi += sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.getBonusStat(item, "AGI");
                    if (slotKey.equals("chest")) {
                        chestToEquip = item.clone();
                    }
                }
            }
        }

        // 計算した数値をPlayerDataに保存
        data.defense = totalDefense;
        data.armorWeight = totalWeight;
        // ★変更: 防具のSTR/AGIボーナスは「3につき1」でプレイヤーステータスに反映する
        // (例: 防具ステSTR6 → プレイヤーステSTR+2)
        data.armorStrBonus = totalArmorStr / 3;
        data.armorAgiBonus = totalArmorAgi / 3;

        // ==========================================
        // ★追加: アクセサリー("acc"スロット)のステータス反映。
        // HP%/HP実数値はmaxHPへ、STR/AGIはarmorStrBonus/armorAgiBonusへ直接加算する
        // (防具の「3につき1」変換とは別枠。アクセサリーは実数値として直接効く)。
        // ==========================================
        data.accAccuracyBonus = 0;
        data.accReqBonus = 0;
        org.bukkit.inventory.ItemStack accessoryItem = data.equipment.get("accessory");
        if (accessoryItem != null && plugin.getAccessoryManager() != null
                && plugin.getAccessoryManager().isAccessory(accessoryItem)) {
            for (sac.plugin.info.SACplugin.accessory.AccessoryStatEntry stat : plugin.getAccessoryManager().getStats(accessoryItem)) {
                switch (stat.type) {
                    case "HP_PERCENT":
                        data.maxHP += Math.ceil(data.maxHP * (stat.value / 100.0));
                        break;
                    case "HP_FLAT":
                        data.maxHP += stat.value;
                        break;
                    case "STR_PERCENT":
                        data.armorStrBonus += (int) Math.ceil(data.str * (stat.value / 100.0));
                        break;
                    case "STR_FLAT":
                        data.armorStrBonus += (int) stat.value;
                        break;
                    case "AGI_PERCENT":
                        data.armorAgiBonus += (int) Math.ceil(data.agi * (stat.value / 100.0));
                        break;
                    case "AGI_FLAT":
                        data.armorAgiBonus += (int) stat.value;
                        break;
                    case "ACC":
                        data.accAccuracyBonus += (int) stat.value;
                        break;
                    case "REQ":
                        data.accReqBonus += (int) stat.value;
                        break;
                    default:
                        break;
                }
            }
        }

        // 胴防具を頭に被せて見た目を適用
        if (chestToEquip != null) {
            player.getInventory().setHelmet(chestToEquip);
        } else {
            player.getInventory().setHelmet(null);
        }

        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        }

        // ★【重要】ここでAGIリスナーを呼び出し、移動速度の計算を任せる！
        // ★修正: AttributeModifier関連の例外がダメージ処理など呼び出し元を巻き込んで
        // 処理全体を止めてしまうことがあったため、try-catchで保護する
        try {
            plugin.getAgiListener().updateMovementSpeed(player);
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のupdateMovementSpeedで例外が発生しましたが、処理を継続します。");
            e.printStackTrace();
        }
    }

    // 【重要】死亡時のデータ削除
    public void deletePlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        statsMap.remove(uuid);
        File userDataFile = new File(plugin.getDataFolder(), "userdata/" + uuid + ".yml");
        if (userDataFile.exists()) {
            userDataFile.delete(); // 物理ファイルを削除！
        }
    }

    /**
     * ★追加: 独自の満腹ゲージ(0-100%, SurvivalManagerが主導)を、
     * バニラの空腹バー(0-20)に見た目だけ反映させる。
     * バニラ側の独自減少(ダッシュ/ジャンプ等)はSAOFoodListenerで常にキャンセルしているため、
     * ここで明示的にセットしない限りバニラバーは変化しない(表示専用のミラー)。
     */
    public void syncFoodLevelBar(Player player, PlayerData data) {
        int level = (int) Math.round(data.hunger / 5.0);
        level = Math.max(0, Math.min(20, level));
        player.setFoodLevel(level);
        player.setSaturation(0f);
    }

    public PlayerData getStats(Player player) {
        return statsMap.get(player.getUniqueId());
    }

    /**
     * ★追加: currentHP/maxHPの割合に応じてバニラのハート表示(10ハート固定)を更新する共通処理。
     * SAODamageListenerの被ダメージ処理と同じ計算式。SurvivalManager(満腹度システム)の
     * セーフゾーン回復・空腹ダメージ・ポーション回復などから再利用する。
     */
    public void applyVisualHealth(Player player, PlayerData data) {
        double visualHealth = (data.currentHP / data.maxHP) * 20.0;
        visualHealth = Math.min(20.0, Math.max(1.0, visualHealth));
        player.setHealth(visualHealth);
    }

    public void removePlayer(Player player) {
        // statsMap（メモリ上のデータ）から指定したプレイヤーのUUIDを削除する
        statsMap.remove(player.getUniqueId());
    }

    /**
     * ★追加: 黒鉄宮の死亡回数ランキング表示用。
     * userdataフォルダ内の全プレイヤーファイルを走査し、(UUID, 死亡回数, 最終ログイン時刻)を返す。
     * オンライン・オフライン問わず、これまでにログインした全プレイヤーが対象になる。
     */
    public java.util.List<DeathRecord> getAllDeathRecords() {
        java.util.List<DeathRecord> records = new java.util.ArrayList<>();
        File userDataDir = new File(plugin.getDataFolder(), "userdata");
        File[] files = userDataDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return records;

        for (File file : files) {
            try {
                UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                int deathCount = config.getInt("deathCount", 0);
                long lastLoginTime = config.getLong("lastLoginTime", 0L);
                records.add(new DeathRecord(uuid, deathCount, lastLoginTime));
            } catch (IllegalArgumentException ignored) {
                // ファイル名がUUID形式でない場合はスキップ
            }
        }
        return records;
    }

    /** ★追加: getAllDeathRecords()で使う単純なデータの入れ物 */
    public static class DeathRecord {
        public final UUID uuid;
        public final int deathCount;
        public final long lastLoginTime;
        public DeathRecord(UUID uuid, int deathCount, long lastLoginTime) {
            this.uuid = uuid;
            this.deathCount = deathCount;
            this.lastLoginTime = lastLoginTime;
        }
    }

    // ★【追加】相手がオフラインでもフレンドリストから自分を消すメソッド
    public void removeFriendFromOfflinePlayer(UUID targetId, UUID myId) {
        if (statsMap.containsKey(targetId)) {
            statsMap.get(targetId).friends.remove(myId);
        }
        File targetFile = new File(plugin.getDataFolder(), "userdata/" + targetId + ".yml");
        if (targetFile.exists()) {
            org.bukkit.configuration.file.FileConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(targetFile);
            java.util.List<String> friendsList = cfg.getStringList("friends");
            if (friendsList.remove(myId.toString())) {
                cfg.set("friends", friendsList);
                try { cfg.save(targetFile); } catch (IOException ignored) {}
            }
        }
    }
}