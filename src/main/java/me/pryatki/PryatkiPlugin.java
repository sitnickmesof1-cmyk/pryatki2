package me.pryatki;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import java.time.Duration;
import java.util.*;

public class PryatkiPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private UUID seekerId = null;
    private final Set<UUID> hiders = new HashSet<>();
    private boolean gameRunning = false;
    private final Map<UUID, Location> saved = new HashMap<>();
    private final Map<UUID, ArmorStand> decoys = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("pryatki").setExecutor(this);
        getCommand("nablydat").setExecutor(this);
        new BukkitRunnable() { public void run() { checkCollisions(); } }.runTaskTimer(this, 0L, 5L);
    }

    @Override
    public void onDisable() { cleanup(); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("pryatki")) startGame(s);
        else if (c.getName().equalsIgnoreCase("nablydat") && s instanceof Player) toggleSpectate((Player) s);
        return true;
    }

    private void startGame(CommandSender s) {
        List<Player> pl = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (pl.size() < 2) { s.sendMessage(Component.text("Нужно минимум 2 игрока!", NamedTextColor.RED)); return; }
        cleanup(); hiders.clear(); seekerId = null; gameRunning = false;
        Title t = Title.title(Component.text("Выбор ролей...", NamedTextColor.GRAY), Component.text("3 секунды", NamedTextColor.DARK_GRAY), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(200)));
        for (Player p : pl) {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 1, false, false));
            p.showTitle(t);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<Player> act = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (act.size() < 2) return;
            Collections.shuffle(act);
            seekerId = act.get(0).getUniqueId();
            setup(act.get(0), Color.RED, "ТЫ ИСКАТЕЛЬ", NamedTextColor.RED);
            for (int i = 1; i < act.size(); i++) {
                hiders.add(act.get(i).getUniqueId());
                setup(act.get(i), Color.BLACK, "ТЫ СПРЯТАТЕЛЬ", NamedTextColor.GREEN);
            }
            gameRunning = true;
        }, 60L);
    }

    private void setup(Player p, Color c, String txt, NamedTextColor tc) {
        p.setGameMode(GameMode.CREATIVE);
        p.getInventory().setHelmet(armor(Material.LEATHER_HELMET, c));
        p.getInventory().setChestplate(armor(Material.LEATHER_CHESTPLATE, c));
        p.getInventory().setLeggings(armor(Material.LEATHER_LEGGINGS, c));
        p.getInventory().setBoots(armor(Material.LEATHER_BOOTS, c));
        p.showTitle(Title.title(Component.text(txt, tc), Component.empty(), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
    }

    private ItemStack armor(Material m, Color c) {
        ItemStack i = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) i.getItemMeta();
        if (meta != null) { meta.setColor(c); i.setItemMeta(meta); }
        return i;
    }

    private void checkCollisions() {
        if (!gameRunning || seekerId == null) return;
        Player sk = Bukkit.getPlayer(seekerId);
        if (sk == null || !sk.isOnline()) return;
        for (UUID id : new HashSet<>(hiders)) {
            Player h = Bukkit.getPlayer(id);
            if (h == null || !h.isOnline()) continue;
            Entity target = saved.containsKey(id) ? decoys.get(id) : h;
            if (target != null && target.getWorld().equals(sk.getWorld()) && target.getBoundingBox().overlaps(sk.getBoundingBox().expand(0.3))) catchHider(sk, h);
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!gameRunning || !(e.getDamager() instanceof Player)) return;
        Player sk = (Player) e.getDamager();
        if (!sk.getUniqueId().equals(seekerId)) return;
        if (e.getEntity() instanceof Player && hiders.contains(e.getEntity().getUniqueId())) { e.setCancelled(true); catchHider(sk, (Player) e.getEntity()); }
        else if (e.getEntity() instanceof ArmorStand) {
            for (Map.Entry<UUID, ArmorStand> en : decoys.entrySet()) {
                if (en.getValue().equals(e.getEntity())) { e.setCancelled(true); Player h = Bukkit.getPlayer(en.getKey()); if (h != null) catchHider(sk, h); break; }
            }
        }
    }

    private void catchHider(Player sk, Player h) {
        if (!hiders.remove(h.getUniqueId())) return;
        if (saved.containsKey(h.getUniqueId())) stopSpec(h);
        sk.performCommand("spawn");
        Title t = Title.title(Component.text(h.getName() + " найден!", NamedTextColor.GOLD), Component.text("Осталось: " + hiders.size(), NamedTextColor.YELLOW), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
        if (hiders.isEmpty()) { gameRunning = false; Bukkit.broadcast(Component.text("Все найдены! Искатель победил!", NamedTextColor.GREEN)); }
    }

    private void toggleSpectate(Player p) {
        if (!gameRunning || !hiders.contains(p.getUniqueId())) return;
        if (saved.containsKey(p.getUniqueId())) { stopSpec(p); p.sendMessage(Component.text("Вы в нычке!", NamedTextColor.GREEN)); }
        else {
            Player sk = Bukkit.getPlayer(seekerId);
            if (sk == null || !sk.isOnline()) return;
            Location loc = p.getLocation().clone(); saved.put(p.getUniqueId(), loc);
            ArmorStand st = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
            st.setBasePlate(false); st.setGravity(false); st.customName(Component.text(p.getName())); st.setCustomNameVisible(true);
            EntityEquipment eq = st.getEquipment();
            if (eq != null) { eq.setHelmet(armor(Material.LEATHER_HELMET, Color.BLACK)); eq.setChestplate(armor(Material.LEATHER_CHESTPLATE, Color.BLACK)); eq.setLeggings(armor(Material.LEATHER_LEGGINGS, Color.BLACK)); eq.setBoots(armor(Material.LEATHER_BOOTS, Color.BLACK)); }
            decoys.put(p.getUniqueId(), st);
            p.setGameMode(GameMode.SPECTATOR); p.teleport(sk.getLocation());
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.getGameMode() == GameMode.SPECTATOR && sk.isOnline()) p.setSpectatorTarget(sk); }, 2L);
        }
    }

    private void stopSpec(Player p) {
        Location loc = saved.remove(p.getUniqueId()); ArmorStand st = decoys.remove(p.getUniqueId());
        if (st != null && !st.isDead()) st.remove();
        p.setSpectatorTarget(null); if (loc != null) p.teleport(loc); p.setGameMode(GameMode.CREATIVE);
    }

    @EventHandler public void onSneak(PlayerToggleSneakEvent e) { if (saved.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onMove(PlayerMoveEvent e) { if (saved.containsKey(e.getPlayer().getUniqueId()) && seekerId != null && e.getPlayer().getSpectatorTarget() == null) { Player sk = Bukkit.getPlayer(seekerId); if (sk != null) e.getPlayer().setSpectatorTarget(sk); } }
    @EventHandler public void onQuit(PlayerQuitEvent e) { if (saved.containsKey(e.getPlayer().getUniqueId())) stopSpec(e.getPlayer()); hiders.remove(e.getPlayer().getUniqueId()); }
    private void cleanup() { for (ArmorStand s : decoys.values()) if (s != null) s.remove(); decoys.clear(); for (UUID id : new HashSet<>(saved.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) stopSpec(p); } }
}
// КОНЕЦ КОДА
