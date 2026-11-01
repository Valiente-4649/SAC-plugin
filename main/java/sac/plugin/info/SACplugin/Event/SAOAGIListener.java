package sac.plugin.info.SACplugin.Event;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.UUID;

public class SAOAGIListener implements Listener {
    private final SACplugin plugin;
    // AGIによる速度加算用の固定UUID
    private final UUID MOVEMENT_SPEED_UUID = UUID.fromString("d379766d-317d-4a1e-b9b2-08f3357672a8");
    // 防具重量による速度減算用の固定UUID
    private final UUID WEIGHT_SPEED_UUID = UUID.fromString("e48a17df-1234-4a1e-b9b2-08f3357672a9");
    // ★追加: 体術など「素手武器種」をセットしている間の速度加算用の固定UUID
    private final UUID UNARMED_STYLE_SPEED_UUID = UUID.fromString("f5c8a2b1-9876-4a1e-b9b2-08f3357672aa");
    // ★追加: 満腹ゲージ低下による速度低下用の固定UUID
    private final UUID HUNGER_SPEED_UUID = UUID.fromString("a1b2c3d4-5e6f-4a1e-b9b2-08f3357672ab");

    private final UUID VITALITY_SPEED_UUID = UUID.fromString("bd90f5ad-5f68-455f-8141-47d2187fc7ab");

    public SAOAGIListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateMovementSpeed(event.getPlayer());
    }

    public void updateMovementSpeed(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        AttributeInstance ai = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (ai == null) return;

        // ★修正: 名前判定での削除がうまく機能しないケース（1.20.2からの持ち越しデータ等）があるため、
        // UUIDベースで確実に既存Modifierを削除する。削除に失敗しても処理を止めないようtry-catchで保護。
        try {
            new ArrayList<>(ai.getModifiers()).forEach(mod -> {
                try {
                    if (mod.getUniqueId().equals(MOVEMENT_SPEED_UUID)
                            || mod.getUniqueId().equals(WEIGHT_SPEED_UUID)
                            || mod.getUniqueId().equals(UNARMED_STYLE_SPEED_UUID)
                            || mod.getUniqueId().equals(HUNGER_SPEED_UUID)
                            || mod.getUniqueId().equals(VITALITY_SPEED_UUID)
                            || "SAO_AGI_SPEED".equals(mod.getName())
                            || "SAO_WEIGHT_SPEED".equals(mod.getName())
                            || "SAO_UNARMED_STYLE_SPEED".equals(mod.getName())
                            || "SAO_HUNGER_SPEED".equals(mod.getName())
                            || "SAC_VITALITY_SPEED".equals(mod.getName())) {
                        ai.removeModifier(mod);
                    }
                } catch (Exception ignored) {
                    // 個別のModifier削除失敗は無視して続行
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[SAO] " + player.getName() + " のAttributeModifier削除中に例外: " + e.getMessage());
        }

        // ==========================================
        // 1. AGIによる速度の「加算」 (ADD_NUMBER)
        // ★変更: 防具のAGIボーナス(金属防具鍛造)も合算する
        // ==========================================
        double speedBoost = (stats.agi + stats.armorAgiBonus) * 0.0015;

        AttributeModifier agiModifier = new AttributeModifier(
                MOVEMENT_SPEED_UUID, "SAO_AGI_SPEED", speedBoost, AttributeModifier.Operation.ADD_NUMBER
        );

        // ★修正: addModifierが「既に同じものがある」例外を投げても、呼び出し元(ダメージ処理など)を巻き込んで
        // 処理全体を止めないようtry-catchで保護する
        try {
            ai.addModifier(agiModifier);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[SAO] " + player.getName() + " のAGIモディファイア追加で重複検知。再試行します。");
            try {
                // 念のため再度同じUUIDのModifierを探して削除してから再追加
                ai.getModifiers().stream()
                        .filter(m -> m.getUniqueId().equals(MOVEMENT_SPEED_UUID))
                        .findFirst()
                        .ifPresent(ai::removeModifier);
                ai.addModifier(agiModifier);
            } catch (Exception retryEx) {
                plugin.getLogger().severe("[SAO] " + player.getName() + " のAGIモディファイア再試行も失敗。スキップします。");
            }
        }

        // ==========================================
        // 2. 防具重量による速度の「乗算」 (ADD_SCALAR)
        // ==========================================
        int effectiveWeight = 0;
        if (stats.armorWeight > 0) {
            // STR値1につき、重量を2軽減する (最低1残る)
            // ★変更: 防具のSTRボーナス(金属防具鍛造)も合算する
            effectiveWeight = Math.max(1, stats.armorWeight - ((stats.str + stats.armorStrBonus) * 2));
        }

        // 重量1につき0.98倍。0重量なら1.0倍
        double weightMultiplier = Math.pow(0.98, effectiveWeight);
        if (stats.armorWeight == 0) weightMultiplier = 1.0;

        // ADD_SCALARは基準値に対して「1.0 + X」を掛けるため、0.98倍にしたいなら -0.02 を指定する
        double scalarValue = weightMultiplier - 1.0;

        AttributeModifier weightModifier = new AttributeModifier(
                WEIGHT_SPEED_UUID, "SAO_WEIGHT_SPEED", scalarValue, AttributeModifier.Operation.ADD_SCALAR
        );

        try {
            ai.addModifier(weightModifier);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[SAO] " + player.getName() + " のWEIGHTモディファイア追加で重複検知。再試行します。");
            try {
                ai.getModifiers().stream()
                        .filter(m -> m.getUniqueId().equals(WEIGHT_SPEED_UUID))
                        .findFirst()
                        .ifPresent(ai::removeModifier);
                ai.addModifier(weightModifier);
            } catch (Exception retryEx) {
                plugin.getLogger().severe("[SAO] " + player.getName() + " のWEIGHTモディファイア再試行も失敗。スキップします。");
            }
        }

        // ==========================================
        // ★追加: 3. 体術など「素手武器種」の技を選択中の間の速度加算 (ADD_SCALAR)
        // ==========================================
        // ==========================================
        // ★修正: 「セット」の判定を、既存の武器種ソードスキルと同じ仕組みに統一する。
        // equippedSkills(6枠のどれかに入っているか)ではなく、
        // 現在選択中のソードスキル(selectedSkillId)のカテゴリで判定する。
        // これにより「体術の技を選択している間だけ」効果が発生し、
        // 他の武器スキルを選んでいる間は(体術が6枠のどこかにあっても)効果が乗らない。
        // ==========================================
        double unarmedBonusPercent = 0;
        if (stats.selectedSkillId != null && !stats.selectedSkillId.isEmpty()) {
            // ==========================================
            // ★変更: 疾走スキル発動中(selectedSkillId="dash")は、移動速度を2倍(+100%)にする。
            // "dash"はSwordSkillManagerに実在しないIDなので、ソードスキル実行ロジック側は
            // 何も見つからず不発になる(=戦闘中の誤発動防止)。速度ボーナスだけここで特別扱いする。
            // ==========================================
            if ("dash".equals(stats.selectedSkillId)) {
                unarmedBonusPercent = 100;
            } else {
                sac.plugin.info.SACplugin.skill.SwordSkillData selectedSkill = plugin.getSwordSkillManager().getSkill(stats.selectedSkillId);
                if (selectedSkill != null) {
                    unarmedBonusPercent = plugin.getWeaponTypeManager().getMovementSpeedBonusPercent(selectedSkill.category);
                }
            }
        }

        if (unarmedBonusPercent != 0) {
            double unarmedScalar = unarmedBonusPercent / 100.0;
            AttributeModifier unarmedStyleModifier = new AttributeModifier(
                    UNARMED_STYLE_SPEED_UUID, "SAO_UNARMED_STYLE_SPEED", unarmedScalar, AttributeModifier.Operation.ADD_SCALAR
            );
            try {
                ai.addModifier(unarmedStyleModifier);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[SAO] " + player.getName() + " の素手武器種速度モディファイア追加で重複検知。再試行します。");
                try {
                    ai.getModifiers().stream()
                            .filter(m -> m.getUniqueId().equals(UNARMED_STYLE_SPEED_UUID))
                            .findFirst()
                            .ifPresent(ai::removeModifier);
                    ai.addModifier(unarmedStyleModifier);
                } catch (Exception retryEx) {
                    plugin.getLogger().severe("[SAO] " + player.getName() + " の素手武器種速度モディファイア再試行も失敗。スキップします。");
                }
            }
        }

        // ==========================================
        // ★追加: 4. 満腹ゲージ低下による速度低下 (ADD_SCALAR)
        // 30%未満で-20%、20%未満で-40%、10%未満で-60%。バニラ空腹システムの代替。
        // ==========================================
        double hungerScalar = 0.0;
        if (stats.hunger < 10.0) {
            hungerScalar = -0.60;
        } else if (stats.hunger < 20.0) {
            hungerScalar = -0.40;
        } else if (stats.hunger < 30.0) {
            hungerScalar = -0.20;
        }

        if (hungerScalar != 0.0) {
            AttributeModifier hungerModifier = new AttributeModifier(
                    HUNGER_SPEED_UUID, "SAO_HUNGER_SPEED", hungerScalar, AttributeModifier.Operation.ADD_SCALAR
            );
            try {
                ai.addModifier(hungerModifier);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[SAO] " + player.getName() + " の満腹度速度モディファイア追加で重複検知。再試行します。");
                try {
                    ai.getModifiers().stream()
                            .filter(m -> m.getUniqueId().equals(HUNGER_SPEED_UUID))
                            .findFirst()
                            .ifPresent(ai::removeModifier);
                    ai.addModifier(hungerModifier);
                } catch (Exception retryEx) {
                    plugin.getLogger().severe("[SAO] " + player.getName() + " の満腹度速度モディファイア再試行も失敗。スキップします。");
                }
            }
        }
        // 既存AGI・防具重量・体術・満腹度を含めた最終速度に乗算する。
        double factor = sac.plugin.info.SACplugin.life.VitalityManager.speedFactor(stats);
        if (factor < 1.0) {
            ai.addModifier(new AttributeModifier(VITALITY_SPEED_UUID, "SAC_VITALITY_SPEED",
                    factor - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }

    }
}