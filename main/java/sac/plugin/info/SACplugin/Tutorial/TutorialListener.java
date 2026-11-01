package sac.plugin.info.SACplugin.Tutorial;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import sac.plugin.info.SACplugin.SACplugin;

public class TutorialListener implements Listener {

    private final SACplugin plugin;

    public TutorialListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUseNerveGear(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // 1. 右クリック判定（空気クリックでもブロッククリックでもOKにする）
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;

        // 2. 手のアイテムがナーヴギア(CAULDRON:1)か確認
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.PRISMARINE_SHARD) return;
        if (!item.hasItemMeta() || !item.getItemMeta().hasCustomModelData() ||
                item.getItemMeta().getCustomModelData() != 1) return;

        // 3. プレイヤーが今立っている場所、またはクリックした場所が「建築ベッド」の範囲内かチェック
        // 建築物の上で使うので、player.getLocation() で判定するのが一番正確です
        if (!TutorialManager.isWithinRange(player.getLocation(), TutorialManager.BED_CENTER)) {
            // 範囲外で右クリックした時だけメッセージを出す
            player.sendMessage("§b[System] §r§cここでは使用できないようだ。 (ベッドの上で起動してください)");
            return;
        }

        // --- 条件クリア！ ---
        event.setCancelled(true);

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        startLinkStart(player);
    }

    private void startLinkStart(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
        player.sendTitle("§bLink Start!!", "", 5, 30, 5);

        // 1.5秒後：ロード空間へ（落下開始）
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 400, 5, false, false));
                player.teleport(TutorialManager.LOADING_ZONE);

                // ==========================================
                // ★変更: 4秒間落下後は「特設ゾーン」へ転移し、
                // 常設の「受ける/受けない」ボタン(TutorialFlowListener管理)で選ばせる。
                // アイテム付与・街への転移は選択後(受けない場合)またはチュートリアル終了後に行われる。
                // ==========================================
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        player.teleport(TutorialManager.SPECIAL_ZONE);
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        player.sendTitle("§b-Welcome to Sword Art Craft-", "", 20, 80, 20);
                        player.sendMessage("§e[System] チュートリアルを受けますか？目の前のボタンから選んでください。");
                    }
                }.runTaskLater(plugin, 100L);
            }
        }.runTaskLater(plugin, 30L);
    }
    // ==========================================
    // ★ 修正: NPCをクリックした時の処理（報告・中断・開始）
    // ==========================================
    @EventHandler
    public void onTutorialNPCClick(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Villager)) return;
        org.bukkit.entity.Villager npc = (org.bukkit.entity.Villager) event.getRightClicked();

        if (npc.getScoreboardTags().contains("sao_tutorial_npc")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            java.util.UUID uuid = player.getUniqueId();

            // すでにチュートリアルを進行中の場合(新エンジンでは完了と同時にactiveTutorialが消えるため、
            // ここに来る=まだ終わっていない、で判定できる)
            if (TutorialManager.activeTutorial.containsKey(uuid)) {
                TutorialManager.openTutorialCancelMenu(player);
            } else {
                // 何も受けていなければ選択メニューを開く
                TutorialManager.openTutorialSelectMenu(player);
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.3f, 1.2f);
            }
        }
    }

    // ==========================================
    // ★ 追加: メニューをクリックした時の処理
    // ==========================================
    @EventHandler
    public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        java.util.UUID uuid = player.getUniqueId();

        if (title.equals("§b《 Tutorial Menu 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
            String tutId = null;
            if (slot == 9) tutId = "SACの基礎";
            else if (slot == 10) tutId = "ショップ";
            else if (slot == 11) tutId = "スキル";
            else if (slot == 12) tutId = "戦闘";
            else if (slot == 13) tutId = "鍛造";
            else if (slot == 14) tutId = "料理";
            else if (slot == 15) tutId = "商業";
            else if (slot == 16) tutId = "釣り";
            else if (slot == 17) tutId = "農業";

            if (tutId == null) return;

            if (stats != null && stats.completedTutorials.contains(tutId)) {
                player.sendMessage("§c[System] このチュートリアルは既にクリア済みです！(もう一度受けることもできます)");
            }

            player.closeInventory();
            // ★変更: NPCメニューからの受講(再受講含む)はアイテム付与・モブスポーンを行わない。
            // リンクスタート直後の選択ボタン経由(startTutorial(player, tutorialId)のみ呼ぶ方)だけ付与する。
            plugin.getTutorialFlowListener().startTutorial(player, tutId, false);

        } else if (title.equals("§c《 チュートリアル中断 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 2) { // 続ける
                player.closeInventory();
                player.sendMessage("§a[System] チュートリアルを継続します。");
            } else if (slot == 6) { // 中断する
                plugin.getTutorialFlowListener().cancelTutorial(player);
                player.closeInventory();
                player.sendMessage("§c[System] チュートリアルを中断しました。");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
            }
        }
    }
}