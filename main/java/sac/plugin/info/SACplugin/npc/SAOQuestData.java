package sac.plugin.info.SACplugin.npc;

import java.util.List;

public class SAOQuestData {
    public String id;
    public int floor; // ★追加: クエストの階層
    public String questName;
    public String npcName;
    public List<String> descriptions;
    public List<String> completedDescriptions;
    public String type; // "MOB" or "ITEM"
    public String targetId;
    public int requiredAmount;

    public long rewardCol;
    public int rewardExp;
    public String rewardItemId;
    public int rewardItemAmount;
    public List<String> rewardChoices;
    // ==========================================
    // ★追加: このクエストをクリアすると解放される武器種(体術など)のカテゴリ名。
    // weapon_types.jsonのcategoryと一致させる。未設定(null/空)なら何も解放しない。
    // ==========================================
    public String rewardSkillUnlock;
}
