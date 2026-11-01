package sac.plugin.info.SACplugin.Tutorial;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TutorialStepListener implements Listener {
    private final SACplugin plugin;

    // ステップ(2連打)のタイミング計測用
    private final Map<UUID, Long> lastSneakTime = new HashMap<>();

    public TutorialStepListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // 1. ショップ編: お店のメニューでアイテムを直接クリックしたかを判定
    // ==========================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onShopPurchase(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        String active = TutorialManager.activeTutorial.get(uuid);
        if (!"ショップ".equals(active)) return;

        int step = TutorialManager.tutorialProgress.getOrDefault(uuid, 1);
        if (step >= 12) return;

        if (event.getClickedInventory() == null || event.getClickedInventory().equals(event.getView().getBottomInventory())) return;

        org.bukkit.inventory.ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

        String firstLore = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0)).trim();
        String itemId = firstLore.startsWith("id:") ? firstLore.replace("id:", "").trim() : firstLore;

        if (itemId == null || itemId.isEmpty()) return;

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        // --- ステップごとの判定 ---
        if (step == 1) {
            SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
            if (weapon != null && weapon.price == 200) {
                if (stats.col >= weapon.price) {
                    TutorialManager.tutorialProgress.put(uuid, 2);

                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        player.sendMessage("§a[System] 武器の購入に成功しました！");
                        player.sendMessage("§e[System] 次は案内に従って飯屋へ行き、§f「硬めのパン」§eを §c10個 §e購入しましょう！");
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

                        TutorialManager.startGuidance(player, TutorialManager.FOOD_SHOP, plugin);
                    }, 5L);
                }
            } else if (weapon != null) {
                player.sendMessage("§c[System] チュートリアルでは 200 Col の武器を購入してください！");
            }
        } else if (step >= 2 && step <= 11) {
            SAOGeneralItemData general = plugin.getSaoItemManager().getGeneralItem(itemId);
            if (general != null && "食料".equals(general.category)) {
                if (stats.col >= general.price) {
                    int nextStep = step + 1;
                    TutorialManager.tutorialProgress.put(uuid, nextStep);

                    int currentCount = nextStep - 2;

                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (currentCount < 10) {
                            player.sendMessage("§a[System] 食料を購入しました！ (現在: " + currentCount + " / 10個)");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 1.5f);
                        } else {
                            TutorialManager.tutorialProgress.put(uuid, 12); // 完了
                            player.sendMessage("§a[System] 食料を10個購入できましたね！");
                            player.sendMessage("§e[System] 全ての買い物が終わりました！Tutorial NPCのところへ戻って報告しましょう！");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

                            // ★変更: NPCの正確な座標へ案内する！
                            TutorialManager.startGuidance(player, TutorialManager.TUTORIAL_NPC_LOC, plugin);
                        }
                    }, 5L);
                }
            }
        }
    }

    // ==========================================
    // 2. スキル編: スキルメニューを閉じた時の判定
    // ==========================================
    @EventHandler
    public void onSkillMenuClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!"スキル".equals(TutorialManager.activeTutorial.get(uuid)) || TutorialManager.tutorialProgress.getOrDefault(uuid, 1) != 1) return;
        if (!event.getView().getTitle().equals("§b《 Skill Menu 》")) return;

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        boolean hasWeaponSkill = false;
        boolean hasForgeSkill = false;

        for (String skill : stats.equippedSkills) {
            if (skill == null || skill.isEmpty()) continue;
            if (skill.equals("片手剣") || skill.equals("細剣") || skill.equals("短剣") || skill.equals("両手剣")) hasWeaponSkill = true;
            if (skill.equals("斬撃武器鍛造")) hasForgeSkill = true;
        }

        boolean hasSwordSkill = (stats.selectedSkillId != null && !stats.selectedSkillId.isEmpty());

        if (hasWeaponSkill && hasForgeSkill && hasSwordSkill) {
            TutorialManager.tutorialProgress.put(uuid, 2);
            player.sendMessage("§a[System] 素晴らしい！武器スキル、斬撃武器鍛造スキル、そしてソードスキルが正しくセットされました。");
            player.sendMessage("§e[System] Tutorial NPCのところへ戻って報告しましょう！");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

            // ★変更: NPCの正確な座標へ案内する！
            TutorialManager.startGuidance(player, TutorialManager.TUTORIAL_NPC_LOC, plugin);
        }
    }
    // ==========================================
    // ★ 3. 戦闘編: ステップ練習 (Step 1)
    // ==========================================
    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!"戦闘".equals(TutorialManager.activeTutorial.get(uuid))) return;
        if (TutorialManager.tutorialProgress.getOrDefault(uuid, 1) != 1) return;

        if (event.isSneaking()) {
            long now = System.currentTimeMillis();
            // 0.5秒以内に2回スニークしたらステップ成功
            if (lastSneakTime.containsKey(uuid) && (now - lastSneakTime.get(uuid)) < 500) {
                TutorialManager.tutorialProgress.put(uuid, 2);
                player.sendMessage("§a[System] ステップ成功です！回避に役立ちます。");
                player.sendMessage("§e[System] 次は「ソードスキル」です。武器を持った状態で §aスニーク(しゃがみ) ＋ 右クリック §eをして発動してみましょう！");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                lastSneakTime.remove(uuid); // リセット
            } else {
                lastSneakTime.put(uuid, now);
            }
        }
    }

    // ==========================================
    // ★ 3. 戦闘編: ソードスキル & パリィ練習 (Step 2, 3)
    // ==========================================
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!"戦闘".equals(TutorialManager.activeTutorial.get(uuid))) return;
        int step = TutorialManager.tutorialProgress.getOrDefault(uuid, 1);
        if (step != 2 && step != 3) return;

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // 武器を持っているか確認
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
        String firstLore = ChatColor.stripColor(item.getItemMeta().getLore().get(0)).trim();
        String itemId = firstLore.startsWith("id:") ? firstLore.replace("id:", "").trim() : firstLore;
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);

        if (weapon == null) return; // 武器を持っていない場合は無視

        if (step == 2 && player.isSneaking()) {
            // スニーク＋右クリック = ソードスキル発動成功
            TutorialManager.tutorialProgress.put(uuid, 3);
            player.sendMessage("§a[System] ソードスキルの発動成功です！");
            player.sendMessage("§e[System] 次は「パリィ」です。敵の特殊攻撃に合わせ、立った状態で武器を持って §a右クリック §eすると弾けます！");
            player.sendMessage("§e[System] 試しに今、右クリックでパリィの構えをとってみましょう！");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

        } else if (step == 3 && !player.isSneaking()) {
            // しゃがまずに右クリック = パリィの構え成功
            TutorialManager.tutorialProgress.put(uuid, 4);
            player.sendMessage("§a[System] パリィの構え成功です！");
            player.sendMessage("§e[System] 最後に実践です！光の案内に従ってフィールドへ出て、§cボアを1体倒して§eみましょう！");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);

            // フィールド入り口へナビゲート
            TutorialManager.startGuidance(player, TutorialManager.FIELD_ENTRANCE, plugin);
        }
    }

    // ==========================================
    // ★ 3. 戦闘編: ボア討伐 (Step 4)
    // ==========================================
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();
        UUID uuid = player.getUniqueId();

        if (!"戦闘".equals(TutorialManager.activeTutorial.get(uuid))) return;
        if (TutorialManager.tutorialProgress.getOrDefault(uuid, 1) != 4) return;

        // 倒した敵の名前やタグに「ボア」が含まれているか判定
        String mobName = ChatColor.stripColor(event.getEntity().getName()).toLowerCase();
        boolean isBoar = mobName.contains("boar") || mobName.contains("ボア") || event.getEntity().getScoreboardTags().contains("sao_boar");

        if (isBoar) {
            TutorialManager.tutorialProgress.put(uuid, 5); // 報告可能状態へ
            player.sendMessage("§a[System] 見事ボアを討伐しました！戦闘の基本は完璧です！");
            player.sendMessage("§e[System] 光の案内に従って Tutorial NPC に報告しましょう！");
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.5f);

            // NPCへナビゲート
            TutorialManager.startGuidance(player, TutorialManager.TUTORIAL_NPC_LOC, plugin);
        }
    }
}
