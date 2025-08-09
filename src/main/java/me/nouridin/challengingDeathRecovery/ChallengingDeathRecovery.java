package me.nouridin.challengingDeathRecovery;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public class ChallengingDeathRecovery extends JavaPlugin implements Listener {
    private double trashPercentage;
    private final String META_ID = "deathChestId";
    private final String META_PLAYER = "deathChestPlayer";
    // Store the remaining items per chest-ID
    private final Map<String, List<ItemStack>> chestItems = new HashMap<>();
    // Track hologram update tasks
    private final Map<String, BukkitRunnable> hologramTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration cfg = getConfig();
        trashPercentage = cfg.getDouble("percentage-of-trashed-items", 0) / 100.0;
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // cancel all running hologram timers
        hologramTasks.values().forEach(BukkitRunnable::cancel);
        hologramTasks.clear();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        World world = player.getWorld();

        // grab and clear the drops
        List<ItemStack> items = new ArrayList<>(e.getDrops());
        e.getDrops().clear();

        // trash a percentage
        Collections.shuffle(items);
        int toTrash = (int) Math.round(trashPercentage * items.size());
        for (int i = 0; i < toTrash && !items.isEmpty(); i++) {
            items.remove(0);
        }

        // place a real chest block for visual
        Block block = world.getBlockAt(player.getLocation());
        block.setType(Material.CHEST);

        // generate an ID and metadata
        String id = UUID.randomUUID().toString();
        block.setMetadata(META_ID, new FixedMetadataValue(this, id));
        block.setMetadata(META_PLAYER, new FixedMetadataValue(this, player.getName()));

        // store the items in memory
        chestItems.put(id, items);

        // spawn hologram above
        ArmorStand holo = (ArmorStand) world.spawnEntity(
                block.getLocation().clone().add(0.5, 1.2, 0.5),
                EntityType.ARMOR_STAND
        );
        holo.setVisible(false);
        holo.setCustomNameVisible(true);
        holo.setMetadata(META_ID, new FixedMetadataValue(this, id));

        // initial name & schedule updates
        long deathTime = Instant.now().getEpochSecond();
        holo.setCustomName(buildHoloText(player.getName(), deathTime));

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!holo.isValid()) {
                    cancel();
                    return;
                }
                holo.setCustomName(buildHoloText(player.getName(), deathTime));
            }
        };
        task.runTaskTimer(this, 0L, 1200L);
        hologramTasks.put(id, task);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !b.hasMetadata(META_ID)) return;

        e.setCancelled(true);
        String id = b.getMetadata(META_ID).get(0).asString();
        String owner = b.getMetadata(META_PLAYER).get(0).asString();
        List<ItemStack> items = chestItems.getOrDefault(id, Collections.emptyList());

        // create a virtual 54-slot chest GUI
        Inventory inv = Bukkit.createInventory(new VirtualHolder(id, b), 54,
                ChatColor.BLUE + owner + ChatColor.GRAY + "'s items");
        inv.setContents(items.toArray(new ItemStack[0]));
        e.getPlayer().openInventory(inv);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof VirtualHolder)) return;

        VirtualHolder vh = (VirtualHolder) holder;
        String id = vh.id;
        Inventory inv = e.getInventory();
        List<ItemStack> remaining = Arrays.asList(inv.getContents());

        if (inv.isEmpty() || remaining.stream().allMatch(Objects::isNull)) {
            // cleanup: cancel task, remove holo, break block, clear data
            BukkitRunnable task = hologramTasks.remove(id);
            if (task != null) task.cancel();

            // remove hologram entity
            vh.block.getWorld()
                    .getNearbyEntities(vh.block.getLocation().add(0.5,1.2,0.5), 0.1,0.1,0.1)
                    .stream()
                    .filter(ent -> ent.hasMetadata(META_ID)
                            && ent.getMetadata(META_ID).get(0).asString().equals(id))
                    .forEach(org.bukkit.entity.Entity::remove);

            vh.block.setType(Material.AIR);
            chestItems.remove(id);
        } else {
            // save whatever's left back into memory
            chestItems.put(id, new ArrayList<>(remaining));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.getBlock().hasMetadata(META_ID)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent e) {
        if (e.getEntity().hasMetadata(META_ID)) {
            e.setCancelled(true);
        }
    }

    // build a stripped-down cause + elapsed time
    private String buildHoloText(String name, long deathTime) {
        long diff = Instant.now().getEpochSecond() - deathTime;
        long mins = diff / 60;
        String ago = (mins <= 0 ? "just now" : mins + " minute(s) ago");

        // get last-damage cause if available
        String cause = "unknown";
        Player p = Bukkit.getPlayerExact(name);
        if (p != null && p.getLastDamageCause() != null) {
            cause = p.getLastDamageCause().getCause().name().toLowerCase();
        }

        return ChatColor.GOLD + "✦ " +
                ChatColor.RED + name +
                ChatColor.GRAY + " died via " +
                ChatColor.AQUA + cause +
                ChatColor.GRAY + " " + ago;
    }

    // our holder that actually returns its inventory
    private static class VirtualHolder implements InventoryHolder {
        final String id;
        final Block block;
        VirtualHolder(String id, Block block) {
            this.id = id;
            this.block = block;
        }
        @Override
        public Inventory getInventory() {
            // Bukkit will set this for us when opening
            return null;
        }
    }
}
