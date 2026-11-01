package sac.plugin.info.SACplugin.Event;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** ログイン時の一件だけの行動案内。既存データを更新しない。 */
public final class SACGuide {
    private SACGuide() {}

    /** ボス討伐履歴に基づく攻略目標。第2層は最終ボスの記録で攻略済みと判定。 */
    private static final List<FloorTarget> TARGETS = Arrays.asList(
            new FloorTarget(1, "illfang_the_kobold_lord"),
            new FloorTarget(2, "asterios_the_taurus_king"),
            new FloorTarget(3, "nerius_the_evil_treant")
    );

    public static void sendGuide(SACplugin plugin, Player player) {
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null || data.isDead) return;

        File file = new File(plugin.getDataFolder(), "sac_guide.yml");
        YamlConfiguration settings = YamlConfiguration.loadConfiguration(file);
        installDefaults(plugin, file, settings);
        if (!settings.getBoolean("enabled", true)) return;

        FloorTarget target = null;
        for (FloorTarget candidate : TARGETS) {
            if (data.defeatedBosses == null || !data.defeatedBosses.contains(candidate.bossId)) {
                target = candidate;
                break;
            }
        }
        if (target == null) return; // 未設定の階層について不正確な案内を出さない

        int floor = target.floor;
        String path = "floors." + floor + ".";
        int recommendedLevel = settings.getInt(path + "recommended-level", -1);
        double recommendedAttack = settings.getDouble(path + "recommended-weapon-attack", -1);
        if (recommendedLevel <= 0 || recommendedAttack <= 0) {
            plugin.getLogger().warning("SAC Guide: 第" + floor + "層の基準値が未設定です (sac_guide.yml)。");
            return;
        }

        String action;
        if (data.level < recommendedLevel) {
            action = "第" + floor + "層のボスに向けてレベルを上げよう！";
        } else if (getBestUsableWeaponAttack(plugin, player, data) < recommendedAttack) {
            action = "第" + floor + "層のボスに備えて武器を生産・強化しよう！";
        } else {
            action = "第" + floor + "層のボスを討伐しよう！";
        }

        player.sendMessage("§7------------------------------");
        player.sendMessage("§b[System] §fWelcome Back.");
        player.sendMessage("");
        player.sendMessage("§bSAC Guide §fーオススメのやることはこれです！ー");
        player.sendMessage("");
        player.sendMessage("§f・" + action);
        player.sendMessage("§7------------------------------");
    }

    /** インベントリ内で所持し、現在のレベルで使用可能な武器の最大攻撃力を確認。 */
    private static double getBestUsableWeaponAttack(SACplugin plugin, Player player, PlayerData data) {
        double best = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) continue;
            for (String line : meta.getLore()) {
                String id = ChatColor.stripColor(line).trim();
                SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(id);
                if (weapon == null || item.getType() != weapon.material) continue;
                if (!meta.hasCustomModelData() || meta.getCustomModelData() != weapon.customModelData) continue;
                if (data.level < weapon.requires - Math.max(0, data.accReqBonus)) continue;
                double attack = weapon.maxAttack + SAODamageListener.getWeaponBonus(item);
                best = Math.max(best, attack);
                break;
            }
        }
        return best;
    }

    /** 初期値は設定不要のままにする。管理者が攻略推奨値を決定するまで誤判定を避ける。 */
    private static void installDefaults(SACplugin plugin, File file, YamlConfiguration cfg) {
        if (file.exists()) return;
        cfg.set("enabled", true);
        cfg.set("floors.1.recommended-level", -1);
        cfg.set("floors.1.recommended-weapon-attack", -1);
        cfg.set("floors.2.recommended-level", -1);
        cfg.set("floors.2.recommended-weapon-attack", -1);
        cfg.set("floors.3.recommended-level", -1);
        cfg.set("floors.3.recommended-weapon-attack", -1);
        cfg.setComments("floors", Arrays.asList(
                "各層のボス攻略推奨レベルと武器最大攻撃力を設定してください。",
                "-1 は未設定です。未設定層の案内は出しません。",
                "武器の評価はインベントリ内の使用可能武器の最大攻撃力 + 鍛冶のAttackボーナスです。"));
        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("SAC Guide: 設定の保存に失敗: " + ex.getMessage());
        }
    }

    private static final class FloorTarget {
        final int floor;
        final String bossId;
        FloorTarget(int floor, String bossId) { this.floor = floor; this.bossId = bossId; }
    }
}