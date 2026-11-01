package sac.plugin.info.SACplugin.boss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashSet;
import java.util.Set;

public class BossTeleportListener implements Listener {
    private final SACplugin plugin;

    public BossTeleportListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★変更: レイド化対応。ホログラムを右クリックしても即転移はしない。
    // 代わりに、そのフロアボスのレイドへ「参加登録」する。
    // 実際の転移は RaidManager が開始時刻ちょうどに行う。
    // ==========================================
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Player clicker = event.getPlayer();
        String bossId = null;

        for (Entity e : clicker.getNearbyEntities(3, 3, 3)) {
            if (!(e instanceof ArmorStand)) continue;
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith("sao_boss_tp_")) {
                    bossId = tag.replace("sao_boss_tp_", "");
                    break;
                }
            }
            if (bossId != null) break;
        }
        if (bossId == null) return;

        BossData bData = plugin.getBossManager().getBossData(bossId);
        if (bData == null) {
            clicker.sendMessage("§b[System] §r§cこのボスの設定がboss.jsonに見つかりません！");
            return;
        }

        String resultMessage = plugin.getRaidManager().tryRegister(clicker, bossId);
        clicker.sendMessage(resultMessage);
        clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
    }
}