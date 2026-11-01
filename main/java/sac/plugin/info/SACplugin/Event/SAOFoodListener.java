package sac.plugin.info.SACplugin.Event;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;

public class SAOFoodListener implements Listener {
    private final SACplugin plugin;

    public SAOFoodListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★修正: バニラの空腹バーの「独自の増減」は常にキャンセルする(ダッシュ/ジャンプ等での
    // バニラ側だけの勝手な減少や、バニラ食事による勝手な回復を防ぐ)。
    // 表示上のバーは、満腹ゲージ(hunger)が変化するたびにPlayerManager.syncFoodLevelBar()で
    // 明示的にミラーする(SurvivalManagerを主とし、バニラバーは見た目だけ追従する形)。
    // ==========================================
    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        event.setCancelled(true);
    }

    // ==========================================
    // ★追加: バニラの満腹度による自然回復(REGEN/SATIATED)も無効化する。
    // HP回復は独自システム(SurvivalManagerのセーフゾーン回復・ポーション・回復結晶)のみで行う。
    // ==========================================
    @EventHandler
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        String itemId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));

        sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(itemId);
        if (fishData != null) {
            event.setCancelled(true);
            // 注意メッセージを出す (右クリック連打で連続表示されるのを防ぐため、アクションバーに出すのがおすすめです)
            event.getPlayer().spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent("§c[System] 生魚は使用不可"));
            return;
        }

        SAOGeneralItemData data = plugin.getSaoItemManager().getGeneralItem(itemId);

        if (data != null) {

            // ★ 追加: 左手(オフハンド)で「食料」や「回復」「解毒」を使おうとしたら完全にブロック
            if (event.getHand() == EquipmentSlot.OFF_HAND) {
                if ("食料".equals(data.category) || "回復".equals(data.category) || "解毒".equals(data.category)) {
                    event.setCancelled(true);
                    return; // ここで処理を終わらせる
                }
            }

            // 食料・回復・解毒アイテム以外は「構え」をさせない
            if (!"食料".equals(data.category) && !"回復".equals(data.category) && !"解毒".equals(data.category)) {
                event.setCancelled(true);
            }

            // ==========================================
            // ★修正: バニラのfoodLevelではなく、独自の満腹ゲージ(hunger)で満腹判定する
            // ==========================================
            if ("食料".equals(data.category)) {
                PlayerData stats = plugin.getPlayerManager().getStats(event.getPlayer());
                if (stats != null && stats.hunger >= 100.0) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        String itemId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));

        sac.plugin.info.SACplugin.fishing.FishData fishData = plugin.getFishingManager().getFish(itemId);
        if (fishData != null) {
            event.setCancelled(true);
            return;
        }

        SAOGeneralItemData data = plugin.getSaoItemManager().getGeneralItem(itemId);
        if (data == null) return;
        if (!"食料".equals(data.category) && !"回復".equals(data.category) && !"解毒".equals(data.category)) return;

        // バニラの消費処理(満腹度加算・アイテム消費)をキャンセルし、独自処理に置き換える
        event.setCancelled(true);

        // ==========================================
        // ★修正: 武器が消えるバグの完全対策！(既存ロジックを維持)
        // ==========================================
        ItemStack consumedItem = event.getItem(); // 今まさに食べた/使ったアイテムの情報
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand != null && itemInHand.isSimilar(consumedItem)) {
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        } else {
            ItemStack clone = consumedItem.clone();
            clone.setAmount(1);
            player.getInventory().removeItem(clone);
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        if ("食料".equals(data.category)) {
            // --- 満腹ゲージの回復(value1 = 満腹ゲージ回復%) ---
            stats.hunger = Math.min(100.0, stats.hunger + data.value1);

            // ==========================================
            // ★追加: value2 = 腹持ち%(隠しバッファ)。
            // 既にバッファが残っている(0より大きい)間は上書き・加算しない。
            // バッファが0になっている時だけ、この料理の腹持ちが新たに乗る。
            // ==========================================
            boolean gainedSatiety = false;
            if (stats.satietyBuffer <= 0.0 && data.value2 > 0.0) {
                stats.satietyBuffer = data.value2;
                gainedSatiety = true;
            }

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);
            String msg = "§a" + data.name + "§fを食べた §7(満腹度 +" + (int) data.value1 + "%)";
            if (gainedSatiety) {
                msg += " §6[腹持ち]";
            }
            player.sendMessage(msg);

            plugin.getPlayerManager().savePlayerData(player, stats);
            plugin.getPlayerManager().applyVisualHealth(player, stats);
            plugin.getPlayerManager().syncFoodLevelBar(player, stats);
            if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

            // 満腹度の閾値を跨いでいる可能性があるので移動速度を再計算
            plugin.getAgiListener().updateMovementSpeed(player);

        } else if ("回復".equals(data.category)) {
            // --- HP回復(ポーション: gradual / 回復結晶等: instant) ---
            if ("instant".equalsIgnoreCase(data.healType)) {
                stats.currentHP = stats.maxHP;
                plugin.getPlayerManager().applyVisualHealth(player, stats);
                plugin.getPlayerManager().savePlayerData(player, stats);
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                player.sendMessage("§a" + data.name + "§fを使用した §7(HP全回復)");
            } else {
                // "gradual"(既定): value1の固定量をvalue2秒かけてゆっくり回復
                plugin.getSurvivalManager().startGradualHeal(player, data.value1, data.value2);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                player.sendMessage("§a" + data.name + "§fを使用した §7(HP " + (int) data.value1 + " を " + (int) data.value2 + "秒かけて回復)");
            }
        } else if ("解毒".equals(data.category)) {
            // --- 麻痺(新)と毒の両方を解除する ---
            boolean curedParalysis = plugin.getStatusEffectManager().cureParalysis(player);
            boolean curedPoison = plugin.getStatusEffectManager().curePoison(player);
            if (!curedParalysis && !curedPoison) {
                player.sendMessage("§b[System] §r§7麻痺・毒のどちらの状態でもないため、" + data.name + "§7は効果がありませんでした。");
            }
        }

        // ★ クライアントの画面のズレを防ぐため、1Tick後に表示だけ強制更新する
        org.bukkit.Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}