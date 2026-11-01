package sac.plugin.info.SACplugin.Tutorial;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.UUID;

public class TutorialCommand implements CommandExecutor {
    private final SACplugin plugin;
    // プレイヤーごとの進行状況を保存 (1:武器屋へ, 2:武器購入待ち, 3:飯屋購入待ち, 4:フィールドへ)
    public static final HashMap<UUID, Integer> tutorialStep = new HashMap<>();

    public TutorialCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length > 0 && args[0].equalsIgnoreCase("start")) {
            tutorialStep.put(player.getUniqueId(), 1);
            player.sendMessage("§e[GUIDE] §fチュートリアルを開始します！");
            player.sendMessage("§b[System] §r§fまずは装備を整えましょう！§aパーティクル§fに従って§e武器屋§fへ向かってください！");
            startParticleGuide(player, TutorialManager.WEAPON_SHOP, 1);
        }
        else if (args.length > 0 && args[0].equalsIgnoreCase("spawnchoice")) {
            // ★追加: 特設ゾーンに「受ける/受けない」ボタンを設置する(OP限定、1度だけ実行すればOK)
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED");
                return true;
            }
            TutorialLocation yes = new TutorialLocation();
            yes.world = TutorialManager.SPECIAL_ZONE.getWorld().getName();
            yes.x = TutorialManager.SPECIAL_ZONE.getX() - 1;
            yes.y = TutorialManager.SPECIAL_ZONE.getY();
            yes.z = TutorialManager.SPECIAL_ZONE.getZ();

            TutorialLocation no = new TutorialLocation();
            no.world = TutorialManager.SPECIAL_ZONE.getWorld().getName();
            no.x = TutorialManager.SPECIAL_ZONE.getX() + 1;
            no.y = TutorialManager.SPECIAL_ZONE.getY();
            no.z = TutorialManager.SPECIAL_ZONE.getZ();

            plugin.getTutorialFlowListener().spawnInitialChoiceButtons(yes, no);
            player.sendMessage("§a[System] 選択ボタンを設置しました！");
        }
        else if (args[0].equalsIgnoreCase("skip")) {
            tutorialStep.remove(player.getUniqueId()); // 進行状況を削除
            player.sendMessage("§e[System] §fチュートリアルをスキップしました！");

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.sendMessage("§b[System] §r§bSword Art Craftをお楽しみください！");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    }
                }
            }.runTaskLater(plugin, 40L); // 2秒後
        }
        return true;
    }

    // パーティクルで目的地まで誘導するメソッド
    public void startParticleGuide(Player player, Location target, int step) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // ステップが変わった、またはオフラインなら終了
                if (!tutorialStep.containsKey(player.getUniqueId()) || tutorialStep.get(player.getUniqueId()) != step) {
                    this.cancel();
                    return;
                }

                Location loc = player.getLocation();
                // 目的地までのベクトルを計算
                org.bukkit.util.Vector direction = target.toVector().subtract(loc.toVector()).normalize();

                // プレイヤーの目の前にパーティクルを表示
                for (double d = 1; d < 5; d += 0.5) {
                    Location pLoc = loc.clone().add(direction.clone().multiply(d)).add(0, 1.2, 0);

                    // ★ Particle.END_ROD に変更
                    // 速度を少し(0.02)つけると、フワッと動いて綺麗に見えます
                    player.spawnParticle(Particle.END_ROD, pLoc, 1, 0, 0, 0, 0.02);
                }

                // 3マス以内に入ったか判定
                if (loc.distance(target) <= 3.0) {
                    this.cancel();
                    handleArrival(player, step);
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void handleArrival(Player player, int step) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        if (step == 1) {
            tutorialStep.put(player.getUniqueId(), 2);
            player.sendMessage("§e[GUIDE] §f武器屋に到着しました！　右クリックで話しかけ、§b価格が200colの武器§fを購入してください！");
        } else if (step == 3) {
            tutorialStep.put(player.getUniqueId(), 4);
            player.sendMessage("§e[GUIDE] §f鍛冶台を使うと、武器生成、武器強化、武器修理を行うことができます！");
            player.sendMessage("§b[System] §r§f武器生成をすればするほど鍛冶スキルの熟練が上がります！");
            player.sendMessage("§b[System] §r§f次は雑貨屋へ食べ物を買いに行きましょう！");
            startParticleGuide(player, TutorialManager.FOOD_SHOP, 4); // 飯屋へ
        } else if (step == 4) {
            tutorialStep.put(player.getUniqueId(), 5);
            player.sendMessage("§e[GUIDE] §f雑貨屋に到着しました！§b硬めのパンを8個§f購入してください！");
        } else if (step == 6) {
            tutorialStep.put(player.getUniqueId(), 7);
            player.sendMessage("§e[GUIDE] §fこのボタンを押すとフィールド側の道までショートカットできます！");
            player.sendMessage("§b[System] §r§fそれでは調理台へ向かいましょう！");
            startParticleGuide(player, TutorialManager.COOKING_STATION, 7); // 調理台へ
        } else if (step == 7) {
            tutorialStep.put(player.getUniqueId(), 8);
            player.sendMessage("§e[GUIDE] §fここでは様々な食材を料理することができます！");
            player.sendMessage("§b[System] §r§f少し難しいです！ボアの肉を手に入れたら是非挑戦してみましょう！");
            player.sendMessage("§b[System] §r§f次はフィールドの前まで向かいましょう！");
            startParticleGuide(player, TutorialManager.FIELD_ENTRANCE, 8); // フィールド入口へ
        } else if (step == 8) {
            // 長文メッセージを順番に流す
            String[] messages = {
                    "§e[GUIDE] §fフィールドではモンスターと戦うことができます！",
                    "§e[GUIDE] §fモンスターを倒し、colを稼いだり、レベルを上げたり、素材を集めたり、様々なことができます！",
                    "§e[GUIDE] §f戦いでは”ソードスキル”が重要となってきます！",
                    "§e[GUIDE] §f武器を持った状態で”しゃがみ＋右クリック”をすることで発動できます！",
                    "§e[GUIDE] §fソードスキルには”クールタイム”があって連発できず、攻撃中は”硬直”するので気を付けましょう！",
                    "§e[GUIDE] §fソードスキルは《Menu》から変更可能です！戦闘前にセットしておきましょう！",
                    "§e[GUIDE] §f次はモンスターについてですが、モンスターの中には特殊攻撃を使うモンスターがいます！",
                    "§e[GUIDE] §f当たると大ダメージを受けてしまうため、攻撃予測範囲から速やかに逃げましょう！",
                    "§e[GUIDE] §f左右のボタンを押すことでフィールドへ出ることができます！",
                    "§e[GUIDE] §f持ち物は確認できましたか？",
                    "§e[GUIDE] §bそれではフィールドへ出てみましょう！"
            };
            for (int i = 0; i < messages.length; i++) {
                final int index = i;
                final String msg = messages[i];
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.sendMessage(msg);
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

                            // ★【追加】最後の「それではフィールドへ～」を言い終わった瞬間に、はじめてStepを9にする！
                            if (index == messages.length - 1) {
                                tutorialStep.put(player.getUniqueId(), 9);
                            }
                        }
                    }
                }.runTaskLater(plugin, i * 120L); // 3秒間隔で表示
            }
        }
    }
}