package me.pryatki;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.*;

public class PryatkiPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private UUID seekerId = null;
    private final Set<UUID> hiders = new HashSet<>();
    private boolean gameRunning = false;
    private final Map<UUID, Location> savedLocations = new HashMap<>();
    private final Map<UUID, ArmorStand> decoys = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("pryatki") != null) getCommand("pryatki").setExecutor(this);
        if (getCommand("nablydat") != null) getCommand("nablydat").setExecutor(this);

        new BukkitRunnable() {
            @Override
            public void run() { checkCollisions(); }
        }.runTaskTimer(this, 0L, 5L);
    }

    @Override
    public void onDisable() { cleanupDecoys(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("pryatki")) {
            startGame(sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("nablydat")) {
            if (sender instanceof Player) toggleSpectate((Player) sender);
            return true;
        }
        return false;
    }

    private void startGame(CommandSender sender) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.size() < 2) {
            sender.sendMessage(Component.text("Нужно минимум 2 игрока для старта пряток!", NamedTextColor.RED));
            return;
        }
        cleanupDecoys();
        savedLocations.clear();
        hiders.clear();
        seekerId = null;
        gameRunning = false;

        Title choosingTitle = Title.title(
                Component.text("Выбор ролей...", NamedTextColor.GRAY),
                Component.text("Подождите 3 секунды", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(200))
        );

        for (Player p : players) {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 1, false, false));
            p.showTitle(choosingTitle);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<Player> activePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (activePlayers.size() < 2) return;
            Collections.shuffle(activePlayers);
            Player seeker = activePlayers.get(0);
            seekerId = seeker.getUniqueId();
            setupPlayer(seeker, Color.RED, "ТЫ ИСКАТЕЛЬ", NamedTextColor.RED);
            for (int i = 1; i < activePlayers.size(); i++) {
                Player hider = activePlayers.get(i);
                hiders.add(hider.getUniqueId());
                setupPlayer(hider, Color.BLACK, "ТЫ СПРЯТАТЕЛЬ", NamedTextColor.GREEN);
            }
            gameRunning = true;
        }, 60L);
    }

    private void setupPlayer(Player player, Color armorColor, String titleText, NamedTextColor textColor) {
        player.setGameMode(GameMode.CREATIVE);
        equipColoredArmor(player, armorColor);
        Title roleTitle = Title.title(
                Component.text(titleText, textColor),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        player.showTitle(roleTitle);
    }

    private void equipColoredArmor(Player player, Color color) {
        player.getInventory().setHelmet(createArmorItem(Material.LEATHER_HELMET, color));
        player.getInventory().setChestplate(createArmorItem(Material.LEATHER_CHESTPLATE, color));
        player.getInventory().setLeggings(createArmorItem(Material.LEATHER_LEGGINGS, color));
        player.getInventory().setBoots(createArmorItem(Material.LEATHER_BOOTS, color));
    }

    private ItemStack createArmorItem(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta != null) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void checkCollisions() {
        if (!gameRunning || seekerId == null) return;
        Player seeker = Bukkit.getPlayer(seekerId);
        if (seeker == null || !seeker.isOnline()) return;

        for (UUID hiderUuid : new HashSet<>(hiders)) {
            Player hider = Bukkit.getPlayer(hiderUuid);
            if (hider == null || !hider.isOnline()) continue;

            if (savedLocations.containsKey(hiderUuid)) {
                ArmorStand decoy = decoys.get(hiderUuid);
                if (decoy != null && decoy.getWorld().equals(seeker.getWorld())) {
                    if (decoy.getBoundingBox().overlaps(seeker.getBoundingBox().expand(0.3))) {
                        catchHider(seeker, hider);
                    }
                }
            } else if (hider.getWorld().equals(seeker.getWorld())) {
                if (hider.getBoundingBox().overlaps(seeker.getBoundingBox().expand(0.3))) {
                    catchHider(seeker, hider);
                }
            }
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!gameRunning || seekerId == null) return;
        if (!(event.getDamager() instanceof Player)) return;
        Player seeker = (Player) event.getDamager();
        if (!seeker.getUniqueId().equals(seekerId)) return;

        if (event.getEntity() instanceof Player) {
            Player hider = (Player) event.getEntity();
            if (hiders.contains(hider.getUniqueId())) {
                event.setCancelled(true);
                catchHider(seeker, hider);
            }
        } else if (event.getEntity() instanceof ArmorStand) {
            ArmorStand stand = (ArmorStand) event.getEntity();
            for (Map.Entry<UUID, ArmorStand> entry : decoys.entrySet()) {
                if (entry.getValue().equals(stand)) {
                    event.setCancelled(true);
                    Player hider = Bukkit.getPlayer(entry.getKey());
                    if (hider != null) catchHider(seeker, hider);
                    break;
                }
            }
        }
    }

    private void catchHider(Player seeker, Player hider) {
        UUID hiderUuid = hider.getUniqueId();
        if (!hiders.remove(hiderUuid)) return;
        if (savedLocations.containsKey(hiderUuid)) stopSpectating(hider);
        seeker.performCommand("spawn");

        Title foundTitle = Title.title(
                Component.text(hider.getName() + " найден!", NamedTextColor.GOLD),
                Component.text("Осталось спрятателей: " + hiders.size(), NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))
        );
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(foundTitle);

        if (hiders.isEmpty()) {
            gameRunning = false;
            Bukkit.broadcast(Component.text("Все спрятатели найдены! Искатель победил!", NamedTextColor.GREEN));
        }
    }

    private void toggleSpectate(Player player) {
        if (!gameRunning) {
            player.sendMessage(Component.text("Игра сейчас не идет!", NamedTextColor.RED));
            return;
        }
        if (!hiders.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Наблюдать могут только активные спрятатели!", NamedTextColor.RED));
            return;
        }
        if (savedLocations.containsKey(player.getUniqueId())) {
            stopSpectating(player);
            player.sendMessage(Component.text("Вы вернулись в свою нычку!", NamedTextColor.GREEN));
        } else {
            Player seeker = Bukkit.getPlayer(seekerId);
            if (seeker == null || !seeker.isOnline()) {
                player.sendMessage(Component.text("Искатель не в сети!", NamedTextColor.RED));
                return;
            }
            Location hideSpot = player.getLocation().clone();
            savedLocations.put(player.getUniqueId(), hideSpot);

            ArmorStand decoy = (ArmorStand) hideSpot.getWorld().spawnEntity(hideSpot, EntityType.ARMOR_STAND);
            decoy.setBasePlate(false);
            decoy.setArms(true);
            decoy.setGravity(false);
            decoy.customName(Component.text(player.getName(), NamedTextColor.DARK_GRAY));
            decoy.setCustomNameVisible(true);

            EntityEquipment equip = decoy.getEquipment();
            if (equip != null) {
                equip.setHelmet(createArmorItem(Material.LEATHER_HELMET, Color.BLACK));
                equip.setChestplate(createArmorItem(Material.LEATHER_CHESTPLATE, Color.BLACK));
