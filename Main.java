package net.mindoverflow.hubthat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class Main extends JavaPlugin implements Listener {

    /** Players who are currently waiting for a teleport. */
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private final Map<UUID, Location> startPos = new HashMap<>();

    private File hubFile;
    private File spawnFile;
    private YamlConfiguration hubData;
    private YamlConfiguration spawnData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hubFile = new File(getDataFolder(), "hub.yml");
        spawnFile = new File(getDataFolder(), "spawn.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        cmd("hub", (s, c, l, a) -> hub(s));
        cmd("sethub", (s, c, l, a) -> setHub(s));
        cmd("spawn", (s, c, l, a) -> spawn(s));
        cmd("setspawn", (s, c, l, a) -> setSpawn(s));
        cmd("hubthat", (s, c, l, a) -> main(s, a));

        var wtp = getCommand("worldtp");
        if (wtp != null) {
            wtp.setExecutor(new TabExecutor() {
                @Override
                public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
                    return worldTp(s, a);
                }

                @Override
                public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
                    List<String> out = new ArrayList<>();
                    if (a.length == 1) {
                        for (World w : Bukkit.getWorlds()) {
                            if (w.getName().toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) {
                                out.add(w.getName());
                            }
                        }
                    }
                    return out;
                }
            });
        }
        cmd("worldlist", (s, c, l, a) -> worldList(s));
    }

    @Override
    public void onDisable() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
        startPos.clear();
    }

    private void cmd(String name, CommandExecutor ex) {
        var pc = getCommand(name);
        if (pc != null) pc.setExecutor(ex);
    }

    private void loadData() {
        hubData = YamlConfiguration.loadConfiguration(hubFile);
        spawnData = YamlConfiguration.loadConfiguration(spawnFile);
    }

    // ---------- helpers ----------

    private String msg(String path) {
        String prefix = getConfig().getString("global.PREFIX", "");
        String text = getConfig().getString(path, path);
        return color(prefix + " " + text);
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static Location readLoc(YamlConfiguration y, String base, String worldPath) {
        String worldName = y.getString(worldPath);
        if (worldName == null) return null;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w,
                y.getDouble(base + "x"), y.getDouble(base + "y"), y.getDouble(base + "z"),
                (float) y.getDouble(base + "yaw"), (float) y.getDouble(base + "pitch"));
    }

    private Location hubLocation() {
        return readLoc(hubData, "hub.", "hub.world");
    }

    private Location spawnLocation(World world) {
        String n = world.getName();
        if (!spawnData.contains("spawn.world." + n)) return null;
        World w = Bukkit.getWorld(spawnData.getString("spawn.world." + n, n));
        if (w == null) return null;
        return new Location(w,
                spawnData.getDouble("spawn.x." + n), spawnData.getDouble("spawn.y." + n),
                spawnData.getDouble("spawn.z." + n),
                (float) spawnData.getDouble("spawn.yaw." + n), (float) spawnData.getDouble("spawn.pitch." + n));
    }

    private boolean saveYaml(YamlConfiguration y, File f) {
        try {
            y.save(f);
            return true;
        } catch (IOException e) {
            getLogger().warning("Could not save " + f.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Teleport after delay; cancelled if the player moves (when move-detect is on). */
    private void delayedTeleport(Player p, Location target, long delay, String waitPath, String donePath) {
        UUID id = p.getUniqueId();
        if (pending.containsKey(id)) {
            p.sendMessage(msg("global.ALREADY-TELEPORTING"));
            return;
        }
        if (delay <= 0) {
            p.teleport(target);
            p.sendMessage(msg(donePath));
            return;
        }
        p.sendMessage(msg(waitPath).replace("%sec%", String.valueOf(delay)));
        startPos.put(id, p.getLocation());
        BukkitTask t = Bukkit.getScheduler().runTaskLater(this, () -> {
            pending.remove(id);
            startPos.remove(id);
            if (p.isOnline()) {
                p.teleport(target);
                p.sendMessage(msg(donePath));
            }
        }, delay * 20L);
        pending.put(id, t);
    }

    // ---------- commands ----------

    private boolean hub(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(ChatColor.DARK_RED + color(getConfig().getString("hub.ONLY_PLAYERS", "")));
            return true;
        }
        if (!p.hasPermission("hubthat.hub")) {
            p.sendMessage(msg("hub.NO_PERMISSIONS"));
            return true;
        }
        Location hub = hubLocation();
        if (hub == null) {
            p.sendMessage(msg("hub.HUB_NOT_SET"));
            return true;
        }
        long delay = p.hasPermission("hubthat.nohubdelay") ? 0 : getConfig().getLong("hub.delay", 5);
        delayedTeleport(p, hub, delay, "hub.DELAY_TEXT_WAIT", "hub.TELEPORTED");
        return true;
    }

    private boolean setHub(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(color(getConfig().getString("sethub.ONLY_PLAYERS", "")));
            return true;
        }
        if (!p.hasPermission("hubthat.sethub")) {
            p.sendMessage(msg("sethub.NO_PERMISSIONS"));
            return true;
        }
        Location l = p.getLocation();
        hubData.set("hub.world", l.getWorld().getName());
        hubData.set("hub.x", l.getX());
        hubData.set("hub.y", l.getY());
        hubData.set("hub.z", l.getZ());
        hubData.set("hub.yaw", l.getYaw());
        hubData.set("hub.pitch", l.getPitch());
        if (saveYaml(hubData, hubFile)) {
            p.sendMessage(msg("sethub.HUB_SUCCESS_1").replace("%world%", l.getWorld().getName()));
        } else {
            p.sendMessage(msg("sethub.SET_ERROR"));
        }
        return true;
    }

    private boolean spawn(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(color(getConfig().getString("spawn.ONLY_PLAYERS", "")));
            return true;
        }
        if (!p.hasPermission("hubthat.spawn")) {
            p.sendMessage(msg("spawn.NO_PERMISSIONS"));
            return true;
        }
        Location target = spawnLocation(p.getWorld());
        if (target == null) {
            p.sendMessage(msg("spawn.SPAWN_NOT_SET"));
            return true;
        }
        long delay = p.hasPermission("hubthat.nospawndelay") ? 0 : getConfig().getLong("spawn.delay", 5);
        delayedTeleport(p, target, delay, "spawn.DELAY_TEXT_WAIT", "spawn.TELEPORTED");
        return true;
    }

    private boolean setSpawn(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(color(getConfig().getString("setspawn.ONLY_PLAYERS", "")));
            return true;
        }
        if (!p.hasPermission("hubthat.setspawn")) {
            p.sendMessage(msg("setspawn.NO_PERMISSIONS"));
            return true;
        }
        Location l = p.getLocation();
        World w = l.getWorld();
        String n = w.getName();
        w.setSpawnLocation(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        spawnData.set("spawn.world." + n, n);
        spawnData.set("spawn.x." + n, l.getX());
        spawnData.set("spawn.y." + n, l.getY());
        spawnData.set("spawn.z." + n, l.getZ());
        spawnData.set("spawn.yaw." + n, l.getYaw());
        spawnData.set("spawn.pitch." + n, l.getPitch());
        if (saveYaml(spawnData, spawnFile)) {
            p.sendMessage(msg("setspawn.SPAWN_SUCCESS_1").replace("%world%", n));
        } else {
            p.sendMessage(msg("setspawn.SET_ERROR"));
        }
        return true;
    }

    private boolean worldTp(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(color(getConfig().getString("worldtp.ONLY_PLAYERS", "")));
            return true;
        }
        if (!p.hasPermission("hubthat.gotoworld")) {
            p.sendMessage(msg("worldtp.NO_PERMISSIONS"));
            return true;
        }
        if (a.length < 1) {
            p.sendMessage(msg("worldtp.NEEDED_ARGS"));
            return true;
        }
        World w = Bukkit.getWorld(a[0]);
        if (w == null) {
            p.sendMessage(msg("worldtp.UNKNOWN_WORLD"));
            return true;
        }
        p.teleport(w.getSpawnLocation());
        p.sendMessage(msg("worldtp.TELEPORTED").replace("%world%", w.getName()));
        return true;
    }

    private boolean worldList(CommandSender s) {
        if (!s.hasPermission("hubthat.listworlds")) {
            s.sendMessage(msg("worldlist.NO_PERMISSIONS"));
            return true;
        }
        s.sendMessage(ChatColor.GOLD + "Worlds List:");
        s.sendMessage(ChatColor.GRAY + "---------");
        for (World w : Bukkit.getWorlds()) {
            s.sendMessage(ChatColor.GREEN + w.getName() + ChatColor.GRAY + ": "
                    + ChatColor.WHITE + w.getEnvironment().name().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    private boolean main(CommandSender s, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("hubthat.reloadconfig")) {
                s.sendMessage(msg("hub.NO_PERMISSIONS"));
                return true;
            }
            reloadConfig();
            loadData();
            s.sendMessage(color("&aHubThat config reloaded."));
            return true;
        }
        s.sendMessage(color(getConfig().getString("global.PREFIX", "") + " &7This server is running &3HubThat&7 v.&3"
                + getDescription().getVersion() + "&7!"));
        return true;
    }

    // ---------- events ----------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("global.move-detect", true)) return;
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask t = pending.get(id);
        if (t == null) return;
        Location from = startPos.get(id);
        Location to = e.getTo();
        if (from == null || to == null) return;
        // only real movement (block change), not head rotation
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.04) {
            t.cancel();
            pending.remove(id);
            startPos.remove(id);
            e.getPlayer().sendMessage(msg("global.MOVED"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask t = pending.remove(id);
        if (t != null) t.cancel();
        startPos.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("global.tp-hub-on-join", true)) return;
        Location hub = hubLocation();
        if (hub != null) {
            Player p = e.getPlayer();
            // one tick later so it works reliably on join
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) p.teleport(hub);
            });
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!getConfig().getBoolean("global.respawn-at-spawn", true)) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        Location l = spawnLocation(e.getPlayer().getWorld());
        if (l != null) e.setRespawnLocation(l);
    }
}
