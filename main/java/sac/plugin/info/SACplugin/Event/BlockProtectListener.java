package sac.plugin.info.SACplugin.Event;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import sac.plugin.info.SACplugin.SACplugin;

public class BlockProtectListener implements Listener {
    private final SACplugin plugin;

    public BlockProtectListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // 1. ブロックの破壊をキャンセル
    // ==========================================
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // OP権限を持っていなければキャンセル
        if (!player.isOp()) {
            event.setCancelled(true);
            player.sendMessage("§b[System] §r§cここではブロックを破壊できません。");
        }
    }

    // ==========================================
    // 2. ブロックの設置（置き換え含む）をキャンセル
    // ==========================================
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) {
            event.setCancelled(true);
            player.sendMessage("§b[System] §r§cここではブロックを設置できません。");
        }
    }

    // ==========================================
    // 3. 溶岩や水の設置（バケツを使う行為）をキャンセル
    // ==========================================
    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) {
            event.setCancelled(true);
            player.sendMessage("§b[System] §r§cここではバケツを使用できません。");
        }
    }

    // ==========================================
    // 4. 溶岩や水をすくう行為をキャンセル
    // ==========================================
    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) {
            event.setCancelled(true);
        }
    }
    // ==========================================
    // 5. 危険物（スポーンエッグ、TNTトロッコなど）の使用をキャンセル
    // ==========================================
    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // OP権限を持っていなければチェックする
        if (!player.isOp()) {
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                org.bukkit.inventory.ItemStack item = event.getItem();
                if (item != null) {
                    // クリーパーの卵、TNTブロック、TNTトロッコを持っているかチェック
                    // (※TNTブロックはonBlockPlaceでも防げますが、念のための二重ロックです！)
                    if (item.getType().name().endsWith("_SPAWN_EGG") ||
                            item.getType() == org.bukkit.Material.TNT ||
                            item.getType() == org.bukkit.Material.TNT_MINECART) {

                        event.setCancelled(true);
                        player.sendMessage("§b[System] §r§cこの危険なアイテムは使用が禁止されています！");
                    }
                }
            }
        }
    }
}
