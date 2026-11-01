package sac.plugin.info.SACplugin.boss;


import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★新規: フロアボスのHPを、通常モブと同じ「胸装備テクスチャ+名前バー」ではなく
 * バニラのボスバー(画面上部)で表示する。
 * HP割合に応じて緑(通常)→黄(30%以下)→赤(10%以下)に自動で変色する。
 */
public class BossBarManager {
    private final SACplugin plugin;
    // ボスエンティティUUID → ボスバー
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public BossBarManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** フロアボスのHP割合に応じてボスバーを更新する(なければ新規作成する) */
    public void updateBossBar(LivingEntity entity, String mobId, double current, double max) {
        if (entity == null || !entity.isValid()) return;
        double ratio = max > 0 ? Math.max(0.0, Math.min(1.0, current / max)) : 0.0;

        BossBar bar = bars.get(entity.getUniqueId());
        if (bar == null) {
            String title = stripLegacyBar(entity.getCustomName() != null ? entity.getCustomName() : mobId);
            bar = Bukkit.createBossBar(title, colorFor(ratio), BarStyle.SOLID);
            bars.put(entity.getUniqueId(), bar);
        }

        bar.setProgress(ratio);
        bar.setColor(colorFor(ratio));

        syncViewers(bar, mobId);
    }

    /** HP割合(0.0〜1.0)に応じた色。30%以下で黄、10%以下で赤、それ以外は緑。 */
    private BarColor colorFor(double ratio) {
        if (ratio <= 0.10) return BarColor.RED;
        if (ratio <= 0.30) return BarColor.YELLOW;
        return BarColor.GREEN;
    }

    /** 通常モブ用の旧HPバー文字列(名前の後ろの||||部分)が紛れ込んでいれば取り除く */
    private String stripLegacyBar(String customName) {
        if (customName == null) return "";
        String separator = "  ";
        if (customName.contains(separator)) {
            return customName.split(separator)[0];
        }
        return customName;
    }

    /** ボス部屋にいるプレイヤーだけにボスバーを表示する */
    private void syncViewers(BossBar bar, String mobId) {
        List<Player> inRoom = plugin.getBossManager().getPlayersInBossRoom(mobId);
        Set<UUID> shouldSee = new HashSet<>();
        for (Player p : inRoom) shouldSee.add(p.getUniqueId());

        for (Player viewer : new ArrayList<>(bar.getPlayers())) {
            if (!shouldSee.contains(viewer.getUniqueId())) {
                bar.removePlayer(viewer);
            }
        }
        for (Player p : inRoom) {
            bar.addPlayer(p);
        }
    }

    /** ボスが死亡/デスポーンした時に呼ぶ。表示を消して管理から外す(メモリリーク防止)。 */
    public void removeBossBar(UUID entityUuid) {
        BossBar bar = bars.remove(entityUuid);
        if (bar != null) {
            bar.removeAll();
        }
    }
}
