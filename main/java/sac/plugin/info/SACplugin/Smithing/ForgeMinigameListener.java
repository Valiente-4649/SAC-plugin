package sac.plugin.info.SACplugin.Smithing;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★新規: 生産・強化の結果を「金床を叩く回数」で演出するだけのミニゲーム。
 * ステータスやロール抽選には一切影響しない。結果(生産の成功段階、強化の成功/失敗)は
 * このミニゲームを開始する前にすでに決まっており、叩く回数はその結果を反映した
 * 見せ方(何回叩けたか)に過ぎない。
 *
 * 叩き終わる(残り0になる)と、渡されたonCompleteが実行され、そこで
 * 従来通りの処理(アイテム生成・ステ反映・チャットメッセージ)が行われる。
 */
public class ForgeMinigameListener implements Listener {
    private static final String GUI_TITLE = "§6《 鍛造 》";

    private static class Session {
        int remaining;
        Runnable onComplete;
        boolean finished = false;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    public ForgeMinigameListener(SACplugin plugin) {
        // イベント登録はSACplugin側で行う
    }

    /**
     * ミニゲームを開始する。targetHits回、GUI内の金床アイコンをクリックすると
     * onCompleteが実行される。
     */
    public void start(Player player, int targetHits, Runnable onComplete) {
        UUID uuid = player.getUniqueId();
        Session session = new Session();
        session.remaining = Math.max(1, targetHits);
        session.onComplete = onComplete;
        sessions.put(uuid, session);

        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
        for (int i = 0; i < 27; i++) {
            if (i != 13) inv.setItem(i, background());
        }
        inv.setItem(13, anvilItem());

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_PLACE, 1.0f, 1.0f);
    }

    private ItemStack anvilItem() {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e金床を叩け！");
        List<String> lore = new ArrayList<>();
        lore.add("§7クリックして打ち続けろ！");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack background() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() != 13) return;

        Player player = (Player) event.getWhoClicked();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.finished) return;

        session.remaining--;
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f + (float) Math.random() * 0.4f);
        player.getWorld().spawnParticle(org.bukkit.Particle.CRIT, player.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0.05);

        if (session.remaining <= 0) {
            finish(player, session);
        }
    }

    /** 叩き終わった時(または離脱時)の後始末。onCompleteは1回だけ確実に呼ぶ。 */
    private void finish(Player player, Session session) {
        if (session.finished) return;
        session.finished = true;
        sessions.remove(player.getUniqueId());
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f);
        if (session.onComplete != null) session.onComplete.run();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        Player player = (Player) event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        // ★注意: Col・素材は開始前にすでに消費済みのため、途中でGUIを閉じても
        // 結果自体は無かったことにできない。残りの演出を省略してそのまま結果を反映する。
        if (session != null && !session.finished) {
            finish(player, session);
        }
    }

    // ==========================================
    // ★追加: 目標叩き回数の計算(演出専用。ステータス計算には一切使わない)。
    // ==========================================

    /** 生産ミニゲームの目標回数。tierNameは"生産成功"/"大成功"/"奇跡的成功"のいずれか。 */
    public static int computeCraftHitTarget(String tierName, int baseHitCount) {
        if ("大成功".equals(tierName)) {
            return baseHitCount + 1 + (int) (Math.random() * 3); // +1〜3
        }
        if ("奇跡的成功".equals(tierName)) {
            return baseHitCount + 4 + (int) (Math.random() * 3); // +4〜6
        }
        return baseHitCount; // 生産成功: 基準回数そのまま
    }

    /**
     * 強化ミニゲームの目標回数。
     * 成功時: 生産時の回数 〜 +2回。失敗時: 生産時の回数未満(最低1回)。
     * originalHitCountが不明(-1、旧アイテム等)な場合は8を基準として扱う。
     */
    public static int computeUpgradeHitTarget(boolean success, int originalHitCount) {
        int base = originalHitCount > 0 ? originalHitCount : 8;
        if (success) {
            return base + (int) (Math.random() * 3); // 生産時と同じ〜+2回
        }
        int reduced = base - 1 - (int) (Math.random() * 4); // 生産時未満(1〜4回減)
        return Math.max(1, reduced);
    }
}
