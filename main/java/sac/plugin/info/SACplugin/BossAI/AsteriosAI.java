package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

public class AsteriosAI extends FloorBossAI {

    private static final double RANGE_BONUS = 3.0;

    public AsteriosAI(SACplugin plugin, LivingEntity boss, SAOMobData mobData, BossData bossData) {
        super(plugin, boss, mobData, bossData);
        cooldownTick = 80;
    }

    @Override
    public void tick() {
        if (!isValid()) return;
        if (isCasting) return;
        if (getPlayersInRoom().isEmpty()) return;

        if (cooldownTick > 0) { cooldownTick--; return; }

        int skill = (int)(Math.random() * 4);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.6f);

        if (skill == 0) {
            castCircleAttack(10 + RANGE_BONUS, 35, 1.0, null, findSkill("circle"));
        } else if (skill == 1) {
            castJumpAttack(5, 5 + RANGE_BONUS, 20, 1.5, null, findSkill("jump"));
        } else if (skill == 2) {
            Player nearest = getNearestPlayer();
            if (nearest != null) {
                castJumpToPlayer(nearest, 7 + RANGE_BONUS, 25, 1.5, null, findSkill("jump_to_player"));
            } else {
                castCircleAttack(10 + RANGE_BONUS, 35, 2.0, null, findSkill("circle"));
            }
        } else {
            castTripleBombard(3, 1.5, null, findSkill("bombard"));
        }
    }

    public void onDeath() {
        broadcastToRoom("§b[BOSS CLEAR] Congratulations‼ ");
        broadcastToRoom("§b[BOSS CLEAR] Asterios the Taurus King を討伐した！");
    }

    private void broadcastToRoom(String msg) {
        for (Player p : getPlayersInRoom()) p.sendMessage(msg);
    }
}