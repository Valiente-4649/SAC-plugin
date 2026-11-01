package sac.plugin.info.SACplugin.Tutorial;

public class TutorialLocation {
    public String world;
    public double x, y, z;
    public float yaw = 0f;
    public float pitch = 0f;

    public org.bukkit.Location toBukkitLocation() {
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(world != null ? world : "world");
        if (w == null) return null;
        return new org.bukkit.Location(w, x, y, z, yaw, pitch);
    }
}
