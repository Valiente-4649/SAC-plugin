package sac.plugin.info.SACplugin.Tutorial;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TutorialManager {
    // ==========================================
    // ★ 追加: チュートリアルの進行状況を保存する変数
    // ==========================================
    // プレイヤーのUUID -> 進行中のチュートリアル名 (例: "ショップ")
    public static final java.util.Map<java.util.UUID, String> activeTutorial = new java.util.HashMap<>();
    // プレイヤーのUUID -> 進行ステップ (1:タスク進行中, 2:NPCへ報告可能)
    public static final java.util.Map<java.util.UUID, Integer> tutorialProgress = new java.util.HashMap<>();

    // ==========================================
    // ★ 追加: チュートリアル中断・継続メニュー
    // ==========================================
    public static void openTutorialCancelMenu(org.bukkit.entity.Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 9, "§c《 チュートリアル中断 》");
        inv.setItem(2, createTutorialIcon(org.bukkit.Material.GREEN_WOOL, "§a[ 続ける ]", "§7チュートリアルを継続します"));
        inv.setItem(6, createTutorialIcon(org.bukkit.Material.RED_WOOL, "§c[ 中断する ]", "§7現在のチュートリアルを終了します"));
        player.openInventory(inv);
    }
    // ナーヴギアのアイテム生成
    public static ItemStack getNerveGear() {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8NerveGear");
            meta.setLore(Arrays.asList(
                    "§7フルダイブ型VRマシン。",
                    "§bベッドの上で手に持ち、右クリックで使用できます。"
            ));
            meta.setCustomModelData(1);
            item.setItemMeta(meta);
        }
        return item;
    }

    // スキルメニューアイテム生成 (SkillMenuListener.javaの判定に合わせる)
    public static ItemStack getSkillMenuItem() {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b《Menu》");
            List<String> lore = new ArrayList<>();
            lore.add("§7右クリックでメニューを開く");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ★【修正】本物の転移結晶を SAOItemManager から生成して返す
    public static ItemStack getTeleportCrystal(sac.plugin.info.SACplugin.SACplugin plugin) {
        sac.plugin.info.SACplugin.item.SAOGeneralItemData tpData = plugin.getSaoItemManager().getGeneralItem("tp_crystal");
        if (tpData != null) {
            return plugin.getSaoItemManager().createGeneralItem(tpData);
        }
        return new ItemStack(Material.AIR); // データが見つからなかった場合の保険
    }

    // --- 座標設定 ---
    public static final Location BED_CENTER = new Location(Bukkit.getWorld("world"), -29, 95, 705);
    public static final Location LOADING_ZONE = new Location(Bukkit.getWorld("world"), 284.5, 244, 819.5, 0, 90);
    // ==========================================
    // ★追加: リンクスタート後、チュートリアルを受けるか選ぶための「特設ゾーン」。
    // ★TODO: 実際の座標に差し替えてください(現在は仮座標)。
    // ==========================================
    public static final Location SPECIAL_ZONE = new Location(Bukkit.getWorld("world"), -35.5, 92, 1016.5, 180, 0);
    public static final Location TOWN_SPAWN = new Location(Bukkit.getWorld("world"), 8.5, 87, 252.5, 180, 0);
    public static final Location WEAPON_SHOP = new Location(Bukkit.getWorld("world"), 111.5, 87, 250.5); // 武器屋の座標
    public static final Location FOOD_SHOP = new Location(Bukkit.getWorld("world"), 126.5, 87, 264.5);   // 飯屋の座標
    public static final Location FIELD_ENTRANCE = new Location(Bukkit.getWorld("world"), 8.5, 87, 14.5); // フィールド出口
    public static final Location SMITH_SHOP = new Location(Bukkit.getWorld("world"), 126.5, 88, 241.5); // 鍛冶台の座標
    public static final Location SHORTCUT_BUTTON = new Location(Bukkit.getWorld("world"), 84.5, 87, 258.5); // ショートカットボタンの座標
    public static final Location COOKING_STATION = new Location(Bukkit.getWorld("world"), 36.5, 87, 52.5); // 調理台の座標
    public static final Location FIELD_TP_DEST = new Location(Bukkit.getWorld("world"), 8.4,87,3.0); // フィールド（ボタンで飛んだ先）の座標
    public static final Location TUTORIAL_NPC_LOC = new Location(Bukkit.getWorld("world"), 8.5, 87, 236.5);
    public static final Location SHOP_SPOT = new Location(Bukkit.getWorld("world"), 33.5, 87, 160.5); // 商業：Shop Spotの座標
    public static final Location FISHING_NPC = new Location(Bukkit.getWorld("world"), 148.5, 69, -146.5); // 釣り：釣りおじさんの座標
    public static final Location FISHING_POINT = new Location(Bukkit.getWorld("world"), 148.5, 69, -146.5); // 釣り：釣り場の座標
    public static final Location FARM_POINT = new Location(Bukkit.getWorld("world"), -139.5, 85, 13.5); // 農業：Farm Pointの座標

    public static boolean isWithinRange(Location clicked, Location center) {

        if (clicked == null || center == null) return false;
        if (!clicked.getWorld().getName().equals(center.getWorld().getName())) return false;

        return clicked.getWorld().equals(center.getWorld()) &&
                Math.abs(clicked.getBlockX() - center.getBlockX()) <= 1 &&
                Math.abs(clicked.getBlockZ() - center.getBlockZ()) <= 1;
    }

    // ==========================================
    // ★ 追加: チュートリアル選択メニュー
    // ==========================================
    public static void openTutorialSelectMenu(org.bukkit.entity.Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 27, "§b《 Tutorial Menu 》");

        inv.setItem(9, createTutorialIcon(org.bukkit.Material.NETHER_STAR, "§d最初から(導入編)", "§7リンクスタート直後の導入チュートリアルをやり直します"));
        inv.setItem(10, createTutorialIcon(org.bukkit.Material.IRON_SWORD, "§6NPCショップでの購入", "§7武器・防具・雑貨の買い方を学びます"));
        inv.setItem(11, createTutorialIcon(org.bukkit.Material.BOOK, "§bスキルのセット方法", "§7武器スキルや職業スキルの装備方法を学びます"));
        inv.setItem(12, createTutorialIcon(org.bukkit.Material.SHIELD, "§c戦闘の方法", "§7ステップ・ソードスキル・パリィを学びます"));
        inv.setItem(13, createTutorialIcon(org.bukkit.Material.ANVIL, "§e鍛造スキル", "§7武器の作り方を学びます"));
        inv.setItem(14, createTutorialIcon(org.bukkit.Material.CAMPFIRE, "§d調理スキル", "§7料理の作り方を学びます"));
        inv.setItem(15, createTutorialIcon(org.bukkit.Material.EMERALD, "§a商業スキル", "§7自分のお店を開く方法を学びます"));
        inv.setItem(16, createTutorialIcon(org.bukkit.Material.FISHING_ROD, "§b釣りスキル", "§7釣りのテクニックを学びます"));
        inv.setItem(17, createTutorialIcon(org.bukkit.Material.WHEAT, "§2農業スキル", "§7畑の契約と育て方を学びます"));

        player.openInventory(inv);
    }

    private static org.bukkit.inventory.ItemStack createTutorialIcon(org.bukkit.Material m, String name, String desc) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(m);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(java.util.Arrays.asList(desc, "", "§e▶ クリックして開始"));
        item.setItemMeta(meta);
        return item;
    }
    // ==========================================
    // ★ 追加: ガイダンス(案内エフェクト)の管理
    // ==========================================
    public static final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> activeGuidance = new java.util.HashMap<>();

    public static void startGuidance(org.bukkit.entity.Player player, Location target, sac.plugin.info.SACplugin.SACplugin plugin) {
        stopGuidance(player); // 既存の案内があれば消す

        org.bukkit.scheduler.BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !activeTutorial.containsKey(player.getUniqueId())) {
                    this.cancel();
                    activeGuidance.remove(player.getUniqueId());
                    return;
                }

                org.bukkit.Location current = player.getLocation().add(0, 1, 0); // 目の高さ
                org.bukkit.util.Vector dir = target.clone().subtract(current).toVector();
                double dist = dir.length();

                if (dist > 1.5) {
                    dir.normalize().multiply(0.5);
                    // プレイヤーの目の前からターゲットの方向へ、最大10ブロック分の光の点線を引く
                    for (double d = 0; d < Math.min(dist, 10.0); d += 0.5) {
                        current.add(dir);
                        player.spawnParticle(Particle.HAPPY_VILLAGER, current, 1, 0, 0, 0, 0);
                    }
                } else {
                    // 到着したら足元にキラキラを出す
                    player.spawnParticle(org.bukkit.Particle.END_ROD, player.getLocation().add(0, 0.5, 0), 3, 0.5, 0.5, 0.5, 0.05);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // 0.5秒おきに表示

        activeGuidance.put(player.getUniqueId(), task);
    }

    public static void stopGuidance(org.bukkit.entity.Player player) {
        if (activeGuidance.containsKey(player.getUniqueId())) {
            activeGuidance.get(player.getUniqueId()).cancel();
            activeGuidance.remove(player.getUniqueId());
        }
    }
}