package sac.plugin.info.SACplugin.treasure;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.SACplugin;

public class TreasureCommand implements CommandExecutor {
    private final SACplugin plugin;

    public TreasureCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[System] §r§cプレイヤーのみ実行できます。");
            return true;
        }
        Player player = (Player) sender;
        if (!player.isOp()) {
            player.sendMessage("§b[System] §r§cPERMISSION DENIED");
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§b[System] §r§c使用法: /treasure <宝箱名> <報酬Col> <報酬EXP>");
            return true;
        }

        String name = args[0];
        long rewardCol;
        int rewardExp;
        try {
            rewardCol = Long.parseLong(args[1]);
            rewardExp = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§b[System] §r§c報酬Colと報酬EXPは数値で入力してください。");
            return true;
        }

        // 実行者の正面1ブロック前に設置
        Location eyeLoc = player.getEyeLocation();
        org.bukkit.util.Vector dir = eyeLoc.getDirection().setY(0).normalize();
        Location spawnLoc = player.getLocation().clone().add(dir).getBlock().getLocation().add(0.5, 0, 0.5);
        spawnLoc.setYaw(player.getLocation().getYaw());

        String id = TreasureManager.toTreasureId(spawnLoc);
        if (plugin.getTreasureManager().getTreasure(id) != null) {
            player.sendMessage("§b[System] §r§cこの座標には既に宝箱が設置されています！");
            return true;
        }

        // ★ チェストのBlockDisplayを生成
        BlockDisplay chestDisplay = (BlockDisplay) spawnLoc.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.BLOCK_DISPLAY);
        // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
        chestDisplay.setInterpolationDuration(0);
        chestDisplay.setTeleportDuration(0);

        // ★修正: チェストの正面(口を開ける向き)がプレイヤー側を向くよう、
        // ブロックデータのfacingプロパティをプレイヤーがいる方角に設定する
        org.bukkit.block.data.type.Chest chestData = (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
        chestData.setFacing(yawToBlockFace(player.getLocation().getYaw()));
        chestDisplay.setBlock(chestData);

        chestDisplay.addScoreboardTag("sao_treasure_chest");
        chestDisplay.addScoreboardTag("sao_treasure_" + id);

        // BlockDisplayの原点をブロック中心に合わせる（回転は行わない）
        Transformation tf = chestDisplay.getTransformation();
        chestDisplay.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0f, -0.5f),
                new org.joml.Quaternionf(),
                new Vector3f(1f, 1f, 1f),
                new org.joml.Quaternionf()
        ));

        // ★ 名前を表示するTextDisplay
        Location nameLoc = spawnLoc.clone().add(0, 1.3, 0);
        TextDisplay nameDisplay = (TextDisplay) spawnLoc.getWorld().spawnEntity(nameLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
        nameDisplay.setText("§b" + name);
        nameDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        nameDisplay.setSeeThrough(false);
        nameDisplay.setShadowed(true);
        nameDisplay.addScoreboardTag("sao_treasure_label");
        nameDisplay.addScoreboardTag("sao_treasure_" + id);

        // ★ クリック判定用のInteractionエンティティ
        // BlockDisplay/TextDisplayは当たり判定を持たないため、別途Interactionを重ねて配置する
        org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction)
                spawnLoc.getWorld().spawnEntity(spawnLoc.clone().add(0, 0.5, 0), org.bukkit.entity.EntityType.INTERACTION);
        interaction.setInteractionWidth(1.0f);
        interaction.setInteractionHeight(1.0f);
        interaction.addScoreboardTag("sao_treasure_chest");
        interaction.addScoreboardTag("sao_treasure_" + id);

        // ★ データ登録
        plugin.getTreasureManager().createTreasure(id, name, rewardCol, rewardExp, spawnLoc);

        player.sendMessage("§a[System] 宝箱「" + name + "」を設置しました！ §7(報酬: " + rewardCol + " Col, " + rewardExp + " EXP)");
        player.sendMessage("§b[System] §r§7左クリックで中身を編集、右クリックで報酬を獲得できます。");
        return true;
    }

    /**
     * プレイヤーのyaw(向いている方角)から、チェストの正面が「プレイヤー側」を向くBlockFaceを返す。
     * 宝箱はプレイヤーの正面1ブロック先に設置されるため、チェストの口はプレイヤーの背中方向、
     * つまりプレイヤーのyawの逆方向(+180度)を向かせる。
     */
    private org.bukkit.block.BlockFace yawToBlockFace(float playerYaw) {
        float facingYaw = (playerYaw + 180f) % 360f;
        if (facingYaw < 0) facingYaw += 360f;

        // Minecraftのyaw基準: 0=南(+Z), 90=西(-X), 180=北(-Z), 270=東(+X)
        if (facingYaw >= 45 && facingYaw < 135) return org.bukkit.block.BlockFace.WEST;
        if (facingYaw >= 135 && facingYaw < 225) return org.bukkit.block.BlockFace.NORTH;
        if (facingYaw >= 225 && facingYaw < 315) return org.bukkit.block.BlockFace.EAST;
        return org.bukkit.block.BlockFace.SOUTH;
    }
}