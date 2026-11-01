package sac.plugin.info.SACplugin.item;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SAOItemManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, SAOWeapon> weapons = new HashMap<>();
    private final Map<String, SAOShopData> shops = new HashMap<>();
    private final Map<String, SAOGeneralItemData> generalItems = new HashMap<>();
    private final Map<String, SAOArmor> armors = new HashMap<>();

    public SAOItemManager(SACplugin plugin) {
        this.plugin = plugin;
        loadWeapons();
        loadArmors(); // ★これを追加
        loadShops();
        loadGeneralItems();
    }

    @Override
    public void reload() {
        loadWeapons();
        loadArmors();
        loadShops();
        loadGeneralItems();
    }
    public java.util.Set<String> getWeaponIds() {
        return weapons.keySet();
    }
    public List<SAOWeapon> getWeaponsByShop(String shopId) {
        List<SAOWeapon> shopItems = new ArrayList<>();
        return shopItems;
    }
    public java.util.Optional<SAOWeapon> getWeaponByName(String displayName) {
        return weapons.values().stream()
                .filter(w -> w.name.equalsIgnoreCase(displayName))
                .findFirst();
    }
    public SAOArmor getArmor(String id) {
        return armors.get(id);
    }
    public void loadWeapons() {
        File file = new File(plugin.getDataFolder(), "weapons.json");

        // 【チェック用】ファイルがどこにあるかログを出す
        Bukkit.getLogger().info("[SAO] ファイルを探しています: " + file.getAbsolutePath());

        if (!file.exists()) {
            plugin.saveResource("weapons.json", false);
        }

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SAOWeapon>>(){}.getType();
            Map<String, SAOWeapon> loaded = new Gson().fromJson(reader, type);

            if (loaded != null) {
                weapons.clear();
                weapons.putAll(loaded);
                // 成功メッセージ
                Bukkit.getLogger().info("[SAO] ★成功★ " + weapons.size() + "個の武器を読み込みました！");
            } else {
                Bukkit.getLogger().warning("[SAO] weapons.json が読み込めましたが、中身が空です。");
            }
        } catch (Exception e) {
            Bukkit.getLogger().severe("[SAO] 読み込み中にエラーが発生しました！");
            e.printStackTrace();
        }
    }

    public void loadArmors() {
        File file = new File(plugin.getDataFolder(), "armors.json");
        if (!file.exists()) {
            plugin.saveResource("armors.json", false);
        }
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SAOArmor>>(){}.getType();
            Map<String, SAOArmor> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                armors.clear();
                armors.putAll(loaded);
                Bukkit.getLogger().info("[SAO] " + armors.size() + "個の防具を読み込みました！");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ItemStack createWeaponItem(SAOWeapon saoWeapon) {
        ItemStack item = new ItemStack(saoWeapon.material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            Bukkit.getLogger().info("[SAO] 武器生成: " + saoWeapon.id + " / CMD: " + saoWeapon.customModelData);

            meta.setDisplayName("§b"+saoWeapon.name);
            meta.setCustomModelData(saoWeapon.customModelData);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            meta.setUnbreakable(true); // 不可壊にする（これでゲージが消える）
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE); // 「不可壊」という青文字を隠す

            // --- 3. バニラの属性（攻撃力など）を隠して無効化 ---
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            org.bukkit.attribute.AttributeModifier zeroModifier = new org.bukkit.attribute.AttributeModifier(
                    java.util.UUID.randomUUID(), "sao_zero", 0.0,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlot.HAND);
            meta.addAttributeModifier(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE, zeroModifier);

            // すべてのステータスを表示
            List<String> lore = new ArrayList<>();
            lore.add("§8" + saoWeapon.id);
            lore.add("");
            lore.add("§7Range: §f" + saoWeapon.range);
            lore.add("§7Type: §f" + saoWeapon.type);
            lore.add("§7Attack: §b" + (int)saoWeapon.minAttack + " - " + (int)saoWeapon.maxAttack);
            lore.add("§7Weight: §4" + (int)saoWeapon.weight);
            lore.add("§7Requires: §e" + saoWeapon.requires);
            lore.add("");
            lore.add("§7Durability: §a" + saoWeapon.durability + " / " + saoWeapon.maxDurability);

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public SAOWeapon getWeapon(String id) {
        return weapons.get(id);
    }

    public ItemStack createArmorItem(SAOArmor armor) {
        Material mat = Material.matchMaterial(armor.material);
        if (mat == null) mat = Material.LEATHER_CHESTPLATE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§b" + armor.name);
            meta.setCustomModelData(armor.customModelData);

            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            meta.setUnbreakable(true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
            // ★追加: 防具は1つ1つ個別のBonus Statsを持つため、スタックできないようにする
            meta.setMaxStackSize(1);

            // 武器と同じ構成・順序でLoreを作成
            List<String> lore = new ArrayList<>();
            lore.add("§8" + armor.id);
            lore.add("");
            lore.add("§7Equip: §f" + armor.equipType);
            lore.add("§7Type: §f" + armor.type);
            lore.add("§7Defense: §b" + armor.defense);
            lore.add("§7Weight: §4" + armor.weight);
            lore.add("§7Requires: §e" + armor.requires);
            lore.add("");

            // ランダムボーナスの付与
            if (armor.equipBonuses != null && !armor.equipBonuses.isEmpty()) {
                lore.add("§6[ Equip Bonuses ]");
                for (Map.Entry<String, double[]> entry : armor.equipBonuses.entrySet()) {
                    double min = entry.getValue()[0];
                    double max = entry.getValue()[1];
                    long val = Math.round(min + (Math.random() * (max - min)));
                    lore.add("§b- " + entry.getKey() + ": §f+" + val);
                }
                lore.add("");
            }

            lore.add("§7Durability: §a" + armor.durability + " / " + armor.maxDurability);

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void loadShops() {
        File file = new File(plugin.getDataFolder(), "shops.json");
        if (!file.exists()) plugin.saveResource("shops.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SAOShopData>>(){}.getType();
            Map<String, SAOShopData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                shops.clear();
                shops.putAll(loaded);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
    // 特定のショップデータを取得するメソッド
    public SAOShopData getShop(String id) {
        return shops.get(id); // これが null を返すと GUI が開きません
    }
    public void loadGeneralItems() {
        File file = new File(plugin.getDataFolder(), "items.json");
        if (!file.exists()) plugin.saveResource("items.json", false);

        try (FileReader reader = new FileReader(file)) {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, SAOGeneralItemData>>(){}.getType();
            Map<String, SAOGeneralItemData> loaded = new com.google.gson.Gson().fromJson(reader, type);
            if (loaded != null) {
                generalItems.clear();
                generalItems.putAll(loaded);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ★変更: items.jsonに無ければ、料理システム(cooking_recipes.jsonのitems)も見に行く。
    // これにより、ショップ・買取・モンスタードロップ・農業など既存の呼び出し側は
    // 一切変更せずに、料理関連アイテム(食材・完成品・失敗品)も透過的に取得できる。
    // ★修正: DailyManager等、起動シーケンスの早い段階でgetGeneralItem()を呼ぶ処理があり、
    // その時点ではCookingManagerがまだ生成されていない(null)ことがあるため、nullチェックを追加。
    public SAOGeneralItemData getGeneralItem(String id) {
        SAOGeneralItemData item = generalItems.get(id);
        if (item != null) return item;
        if (plugin.getCookingManager() == null) return null;
        return plugin.getCookingManager().getCookingItem(id);
    }
    // ランクごとの色分け
    private String getRankColor(String rank) {
        switch (rank.toUpperCase()) {
            case "S": return "§6"; // 金
            case "A": return "§b"; // 水色
            case "B": return "§c"; // 赤
            case "C": return "§a"; // 緑
            case "D": return "§f"; // ★追加: 灰 (Dランク)
            default: return "§f";  // 白
        }
    }

    // アイテム生成メソッドの修正
    public ItemStack createGeneralItem(SAOGeneralItemData data) {
        // JSONで指定されたマテリアルを使用（なければリンゴにする）
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(data.material != null ? data.material : "APPLE");
        if (mat == null) mat = org.bukkit.Material.APPLE;

        ItemStack item = new ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String color = getRankColor(data.rank);
            meta.setDisplayName(color + data.name);

            // ★カスタムモデルデータを適用
            if (data.customModelData > 0) {
                meta.setCustomModelData(data.customModelData);
            }

            List<String> lore = new ArrayList<>();
            lore.add("§8" + data.id);
            lore.add("§7Rank: " + color + data.rank);
            lore.add("§7Category: §f" + data.category);
            lore.add("");
            lore.add("§f" + data.description);

            meta.setLore(lore);

            // ==========================================
            // ★追加: ポーション(Material.POTION)はバニラ仕様で最大スタック数が1個に
            // 固定されているため、見た目・飲む動作・音は一切変えずに
            // setMaxStackSize()でスタック数だけ5個まで上書きする。
            // (防具で使っている「1個までに制限する」処理と同じAPIの逆利用)
            // ==========================================
            if (mat == org.bukkit.Material.POTION) {
                meta.setMaxStackSize(5);
            }

            item.setItemMeta(meta);
        }
        return item;
    }
}