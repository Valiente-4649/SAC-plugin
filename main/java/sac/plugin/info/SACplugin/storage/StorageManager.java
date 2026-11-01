package sac.plugin.info.SACplugin.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.SACplugin;

public class StorageManager {
    private final SACplugin plugin;

    public StorageManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ストレージの設置
    public void spawnStorageStation(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setY(loc.getBlockY());
        loc.setZ(loc.getBlockZ() + 0.5);
        // ★追加: 元のlocationが持つyaw/pitchが意図せず影響している可能性があるため、明示的に0にリセットする
        loc.setYaw(0f);
        loc.setPitch(0f);

        // チェストの見た目
        BlockDisplay display = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);

        // ==========================================
        // ★追加: プレイヤーの視点(Yaw)から方角を計算してチェストの向き(正面)を決める！
        // ==========================================
        org.bukkit.block.data.BlockData chestData = Bukkit.createBlockData(org.bukkit.Material.CHEST);
        if (chestData instanceof org.bukkit.block.data.Directional) {
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) chestData;

            float yaw = location.getYaw();
            // 0~360度の角度にきれいに直す
            yaw = (yaw % 360 + 360) % 360;

            org.bukkit.block.BlockFace face;
            if (yaw >= 45 && yaw < 135) face = org.bukkit.block.BlockFace.WEST;
            else if (yaw >= 135 && yaw < 225) face = org.bukkit.block.BlockFace.NORTH;
            else if (yaw >= 225 && yaw < 315) face = org.bukkit.block.BlockFace.EAST;
            else face = org.bukkit.block.BlockFace.SOUTH;

            // バニラのチェスト設置と同じように「プレイヤーの方を向く」ように反対の方角をセット
            directional.setFacing(face.getOppositeFace());
            display.setBlock(directional);
        } else {
            display.setBlock(chestData);
        }

        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, -0.5f), new org.joml.Quaternionf(),
                new Vector3f(1, 1, 1), new org.joml.Quaternionf()
        ));
        display.addScoreboardTag("sao_storage_display");

        // 「ストレージ」の浮き文字
        Location textLoc = loc.clone().add(0, 1.2, 0);
        TextDisplay textDisplay = (TextDisplay) loc.getWorld().spawnEntity(textLoc, EntityType.TEXT_DISPLAY);
        textDisplay.setText("§eストレージ");
        textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        textDisplay.addScoreboardTag("sao_storage_display");

        // クリック判定
        Interaction interaction = (Interaction) loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionHeight(1.0f);
        interaction.setInteractionWidth(1.0f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag("sao_storage_station");
    }

    public void removeStorageStation(Location location) {
        for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location, 3, 3, 3)) {
            if (entity.getScoreboardTags().contains("sao_storage_display") ||
                    entity.getScoreboardTags().contains("sao_storage_station")) {
                entity.remove();
                entity.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, entity.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, 0.05);
            }
        }
    }
}