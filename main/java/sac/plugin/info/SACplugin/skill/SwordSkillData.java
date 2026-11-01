package sac.plugin.info.SACplugin.skill;

import java.util.List;

public class SwordSkillData {
    public String id;
    public String name;
    public String description; // スキル説明用
    public int order;          // 表示順序用
    public String category;
    public String requiredType;
    public int requiredProficiency;
    public double damageMultiplier;
    public SkillRange range;
    public int cooldown;
    public int hits;
    public String color;
    // ★【追加】チャージ時間（Tick単位。20で1秒）
    public int chargeTime;
    //剣筋のリスト
    public List<SkillSlash> slashes;

    // ソードスキル発動時のプレイヤーモーション。未設定なら従来通りその場で発動する。
    public SkillMotion motion;

    public static class SkillRange {
        public double forward;
        public double backward;
        public double width;
        public double height;
    }
    //剣筋の定義用クラス
    public static class SkillMotion {
        // NONE / SIDESTEP / DASH / STEP / SPIN / COMBO
        public String type = "NONE";
        // LEFT / RIGHT for SIDESTEP or SPIN; FORWARD / BACKWARD for STEP
        public String direction = "FORWARD";
        // DASH distance in blocks; STEP and SIDESTEP always move 0.5 blocks
        public double distance = 0.0;
        // For COMBO, each entry corresponds to one hit (NONE is allowed).
        public List<SkillMotion> steps;
        // Optional delay before the first damage event (used by caller if wired).
        public int attackDelay = 0;
    }

    public static class SkillSlash {
        public String position;  // FRONT, RIGHT, BACK, LEFT
        public String direction; // RIGHT, LEFT, UP, DOWN, etc.
    }
}
