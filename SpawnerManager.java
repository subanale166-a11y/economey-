package com.example.economysmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DonutSMP-style spawners: Silk Touch to mine, they stack, and instead of spawning mobs
 * they store drops + XP that you manage in a menu (right-click with an empty hand).
 * The menu can sell, drop all, collect XP, and filter which drops are generated.
 */
public class SpawnerManager implements Listener {
    private final EconomySMP plugin;
    private final File file;
    private final Map<String, Spawner> spawners = new HashMap<>();
    private final Map<String, Loot> loot = new HashMap<>();
    private int cycleSeconds = 15;
    private long maxStored = 100000;
    private int secondsCounter = 0;

    private static class Spawner {
        String world;
        int x, y, z;
        String type;
        int count = 1;
        double xp = 0;
        final Map<Material, Long> storage = new EnumMap<>(Material.class);
        final Set<Material> blocked = EnumSet.noneOf(Material.class); // drops this spawner will not generate
    }

    private record Drop(Material material, int min, int max) {}
    private record Loot(double xp, List<Drop> drops) {}

    public static class SpawnerHolder implements InventoryHolder {
        final String key;
        Inventory inv;
        SpawnerHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    public static class FilterHolder implements InventoryHolder {
        final String key;
        final List<Material> materials = new ArrayList<>();
        Inventory inv;
        FilterHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    public SpawnerManager(EconomySMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
        reload();
        load();
    }

    // ---------- config + persistence ----------

    public void reload() {
        loot.clear();
        File f = Util.resource(plugin, "spawnerloot.yml", 1);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        cycleSeconds = Math.max(1, yaml.getInt("cycle-seconds", 15));
        maxStored = yaml.getLong("max-stored-per-item", 100000);
        ConfigurationSection mobs = yaml.getConfigurationSection("mobs");
        if (mobs == null) return;
        for (String type : mobs.getKeys(false)) {
            ConfigurationSection s = mobs.getConfigurationSection(type);
            if (s == null) continue;
            List<Drop> drops = new ArrayList<>();
            for (String line : s.getStringList("drops")) {
                String[] p = line.split("\\|");
                if (p.length < 3) continue;
                Material m = Material.matchMaterial(p[0].trim());
                if (m == null) {
                    plugin.getLogger().warning("Unknown material in spawnerloot.yml: " + p[0]);
                    continue;
                }
                try {
                    int min = Integer.parseInt(p[1].trim());
                    int max = Integer.parseInt(p[2].trim());
                    drops.add(new Drop(m, Math.min(min, max), Math.max(min, max)));
                } catch (NumberFormatException ignored) {}
            }
            loot.put(type.toUpperCase(), new Loot(s.getDouble("xp", 0), drops));
        }
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private static String key(Spawner s) {
        return s.world + ";" + s.x + ";" + s.y + ";" + s.z;
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("spawners");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Spawner sp = new Spawner();
            sp.world = s.getString("world", "world");
            sp.x = s.getInt("x");
            sp.y = s.getInt("y");
            sp.z = s.getInt("z");
            sp.type = s.getString("type", "PIG");
            sp.count = Math.max(1, s.getInt("count", 1));
            sp.xp = s.getDouble("xp", 0);
            ConfigurationSection st = s.getConfigurationSection("storage");
            if (st != null) {
                for (String mat : st.getKeys(false)) {
                    Material m = Material.matchMaterial(mat);
                    if (m != null) sp.storage.put(m, st.getLong(mat));
                }
            }
            for (String name : s.getStringList("blocked")) {
                Material bm = Material.matchMaterial(name);
                if (bm != null) sp.blocked.add(bm);
            }
            spawners.put(key(sp), sp);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        int n = 0;
        for (Spawner s : spawners.values()) {
            String path = "spawners." + (n++);
            yaml.set(path + ".world", s.world);
            yaml.set(path + ".x", s.x);
            yaml.set(path + ".y", s.y);
            yaml.set(path + ".z", s.z);
            yaml.set(path + ".type", s.type);
            yaml.set(path + ".count", s.count);
            yaml.set(path + ".xp", s.xp);
            if (!s.blocked.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Material bm : s.blocked) names.add(bm.name());
                yaml.set(path + ".blocked", names);
            }
            s.storage.forEach((m, amt) -> {
                if (amt > 0) yaml.set(path + ".storage." + m.name(), amt);
            });
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save spawners.yml: " + e.getMessage());
        }
    }

    // ---------- production (called once per second) ----------

    public void tick() {
        if (++secondsCounter < cycleSeconds) return;
        secondsCounter = 0;
        for (Spawner s : spawners.values()) {
            Loot l = loot.get(s.type);
            if (l == null) continue;
            s.xp += l.xp() * s.count;
            for (Drop d : l.drops()) {
                if (s.blocked.contains(d.material())) continue;
                long amount = (long) s.count * ThreadLocalRandom.current().nextInt(d.min(), d.max() + 1);
                if (amount > 0) {
                    s.storage.merge(d.material(), amount, (a, b) -> Math.min(a + b, maxStored));
                }
            }
        }
    }

    // ---------- helpers ----------

    private boolean hasSilk(ItemStack tool) {
        Enchantment silk = Items.find("silk_touch");
        return silk != null && tool.containsEnchantment(silk);
    }

    private void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
    }

    private double valueOf(Spawner s) {
        double total = 0;
        for (Map.Entry<Material, Long> en : s.storage.entrySet()) {
            Double price = plugin.sell().priceFor(en.getKey());
            if (price != null) total += price * en.getValue();
        }
        return total;
    }

    /** Sells everything sellable in the spawner's storage. Returns money earned. */
    private double sellAll(Player p, Spawner s) {
        double total = 0;
        List<Material> sold = new ArrayList<>();
        for (Map.Entry<Material, Long> en : s.storage.entrySet()) {
            Double price = plugin.sell().priceFor(en.getKey());
            if (price != null) {
                total += price * en.getValue();
                sold.add(en.getKey());
            }
        }
        sold.forEach(s.storage::remove);
        if (total > 0) {
            plugin.data().get(p.getUniqueId()).money += total;
            plugin.sidebar().update(p);
        }
        return total;
    }

    // ---------- events ----------

    @EventHandler
    public void onSpawn(SpawnerSpawnEvent e) {
        // registered spawners never spawn real mobs; they only fill their storage
        if (e.getSpawner() != null && spawners.containsKey(key(e.getSpawner().getLocation()))) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.SPAWNER) return;
        String type = Items.spawnerType(e.getItemInHand());
        if (b.getState() instanceof CreatureSpawner cs && Items.entity(type) != null) {
            cs.setSpawnedType(Items.entity(type));
            cs.update();
        }
        Spawner s = new Spawner();
        s.world = b.getWorld().getName();
        s.x = b.getX();
        s.y = b.getY();
        s.z = b.getZ();
        s.type = type;
        spawners.put(key(s), s);
        save();
        plugin.msg(e.getPlayer(), "Placed a &6" + Util.pretty(type) + " Spawner&7. Right-click it with an empty hand to open it.");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        Spawner s = spawners.get(key(b.getLocation()));
        if (s == null) return;

        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();

        if (held.getType() == Material.SPAWNER) {
            e.setCancelled(true);
            String heldType = Items.spawnerType(held);
            if (!heldType.equals(s.type)) {
                plugin.msg(p, "&cThat is a different spawner type.");
                return;
            }
            int add = p.isSneaking() ? held.getAmount() : 1;
            held.setAmount(held.getAmount() - add);
            s.count += add;
            save();
            plugin.msg(p, "Stacked! This spawner is now &fx" + s.count + "&7.");
            return;
        }
        if (held.getType().isAir() && !p.isSneaking()) {
            e.setCancelled(true);
            open(p, s);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean silk = tool.getType().name().endsWith("_PICKAXE") && hasSilk(tool);
        if (!silk && !p.hasPermission("economysmp.admin")) {
            e.setCancelled(true);
            plugin.msg(p, "&cYou need a Silk Touch pickaxe to mine spawners.");
            return;
        }

        e.setExpToDrop(0);
        e.setDropItems(false);
        Spawner s = spawners.get(key(b.getLocation()));

        if (s == null) { // natural dungeon spawner
            if (b.getState() instanceof CreatureSpawner cs) {
                give(p, Items.spawner(Items.typeName(cs.getSpawnedType()), 1));
            }
            return;
        }

        int take = Math.min(p.isSneaking() ? 64 : 1, s.count);
        give(p, Items.spawner(s.type, take));
        s.count -= take;
        if (s.count > 0) {
            e.setCancelled(true); // the rest of the stack stays in the world
            save();
            plugin.msg(p, "Took &f" + take + " &7spawner(s). &f" + s.count + " &7left in the stack.");
            return;
        }

        double earned = sellAll(p, s); // don't lose stored loot
        if (s.xp >= 1) p.giveExp((int) s.xp);
        spawners.remove(key(s));
        save();
        if (earned > 0) plugin.msg(p, "Sold the stored drops for &a$" + Util.money(earned) + "&7.");
    }

    // ---------- storage menu ----------

    private void open(Player p, Spawner s) {
        SpawnerHolder holder = new SpawnerHolder(key(s));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Util.color("&8" + Util.pretty(s.type) + " Spawner &7x" + s.count));
        holder.inv = inv;

        int slot = 0;
        for (Map.Entry<Material, Long> en : s.storage.entrySet()) {
            if (en.getValue() <= 0 || slot >= 45) continue;
            Double price = plugin.sell().priceFor(en.getKey());
            ItemStack icon = Util.icon(en.getKey(), null,
                    "&7Stored: &f" + en.getValue(),
                    price == null ? "&7Not sellable" : "&7Worth: &a$" + Util.money(price * en.getValue()),
                    "",
                    "&eClick: &ftake 64",
                    "&eShift-click: &ftake up to 36 stacks");
            inv.setItem(slot++, icon);
        }

        ItemStack filler = Util.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(47, Util.icon(Material.EMERALD, "&aSell all drops",
                "&7Worth: &a$" + Util.money(valueOf(s)),
                "", "&eClick to sell"));
        inv.setItem(48, Util.icon(Material.HOPPER, "&eDrop all items",
                "&7Takes everything stored (up to 36 stacks",
                "&7per click). If your inventory is full,",
                "&7the rest drops on the ground.",
                "", "&eClick to drop all"));
        inv.setItem(49, Util.icon(Material.SPAWNER, "&6" + Util.pretty(s.type) + " Spawner",
                "&7Stack size: &f" + s.count,
                "&7Makes drops every &f" + cycleSeconds + "s"));
        inv.setItem(50, Util.icon(Material.COMPARATOR, "&cFilter drops",
                "&7Choose what this spawner makes.",
                "&7Blocked items: &f" + s.blocked.size(),
                "", "&eClick to edit"));
        inv.setItem(51, Util.icon(Material.EXPERIENCE_BOTTLE, "&bCollect XP",
                "&7Stored: &f" + (long) s.xp,
                "", "&eClick to collect"));
        p.openInventory(inv);
    }

    private void reopen(Player p, Spawner s) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, s));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder top = e.getView().getTopInventory().getHolder();
        if (top instanceof FilterHolder fh) {
            handleFilter(e, fh);
            return;
        }
        if (!(top instanceof SpawnerHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        Player p = (Player) e.getWhoClicked();
        Spawner s = spawners.get(h.key);
        if (s == null) { p.closeInventory(); return; }
        int slot = e.getSlot();

        if (slot == 47) {
            double earned = sellAll(p, s);
            plugin.msg(p, earned > 0 ? "Sold drops for &a$" + Util.money(earned) + "&7." : "&cNothing sellable in storage.");
            save();
            reopen(p, s);
            return;
        }
        if (slot == 48) {
            dropAll(p, s);
            save();
            reopen(p, s);
            return;
        }
        if (slot == 50) {
            Bukkit.getScheduler().runTask(plugin, () -> openFilter(p, s));
            return;
        }
        if (slot == 51) {
            long xp = (long) s.xp;
            if (xp > 0) {
                p.giveExp((int) xp);
                s.xp -= xp;
                plugin.msg(p, "Collected &b" + xp + " XP&7.");
                save();
            } else {
                plugin.msg(p, "&cNo XP stored yet.");
            }
            reopen(p, s);
            return;
        }
        if (slot < 0 || slot >= 45) return;

        // click on a stored item
        int i = 0;
        Material target = null;
        for (Map.Entry<Material, Long> en : s.storage.entrySet()) {
            if (en.getValue() <= 0) continue;
            if (i++ == slot) { target = en.getKey(); break; }
        }
        if (target == null) return;

        long stored = s.storage.get(target);
        long want = e.isShiftClick() ? 36L * 64 : 64;
        int take = (int) Math.min(stored, want);
        s.storage.put(target, stored - take);
        give(p, new ItemStack(target, take));
        save();
        reopen(p, s);
    }

    private void dropAll(Player p, Spawner s) {
        int limit = plugin.getConfig().getInt("spawners.drop-all-limit", 2304);
        int given = 0;
        for (Map.Entry<Material, Long> en : new ArrayList<>(s.storage.entrySet())) {
            if (given >= limit) break;
            long stored = en.getValue();
            if (stored <= 0) continue;
            int take = (int) Math.min(stored, (long) (limit - given));
            s.storage.put(en.getKey(), stored - take);
            give(p, new ItemStack(en.getKey(), take));
            given += take;
        }
        if (given == 0) {
            plugin.msg(p, "&cThe spawner has no drops stored.");
            return;
        }
        boolean more = false;
        for (long v : s.storage.values()) {
            if (v > 0) { more = true; break; }
        }
        plugin.msg(p, "Dropped &f" + given + " &7items." + (more ? " &7Click again for more." : ""));
    }

    // ---------- filter menu ----------

    private void openFilter(Player p, Spawner s) {
        FilterHolder holder = new FilterHolder(key(s));
        Inventory inv = Bukkit.createInventory(holder, 27,
                Util.color("&8Filter: " + Util.pretty(s.type) + " Spawner"));
        holder.inv = inv;
        ItemStack filler = Util.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        Loot l = loot.get(s.type);
        List<Drop> drops = l == null ? new ArrayList<>() : l.drops();
        for (int i = 0; i < drops.size() && i < 7; i++) {
            Material m = drops.get(i).material();
            holder.materials.add(m);
            boolean blocked = s.blocked.contains(m);
            inv.setItem(10 + i, Util.icon(m,
                    (blocked ? "&c\u2716 " : "&a\u2714 ") + Util.pretty(m.name()),
                    blocked ? "&cBlocked - not generated" : "&aGenerating",
                    "", "&eClick to toggle"));
        }
        inv.setItem(22, Util.icon(Material.ARROW, "&cBack"));
        p.openInventory(inv);
    }

    private void handleFilter(InventoryClickEvent e, FilterHolder fh) {
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Player p = (Player) e.getWhoClicked();
        Spawner s = spawners.get(fh.key);
        if (s == null) { p.closeInventory(); return; }

        int slot = e.getSlot();
        if (slot == 22) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, s));
            return;
        }
        int i = slot - 10;
        if (i < 0 || i >= fh.materials.size()) return;
        Material m = fh.materials.get(i);
        if (!s.blocked.remove(m)) s.blocked.add(m);
        save();
        Bukkit.getScheduler().runTask(plugin, () -> openFilter(p, s));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder top = e.getView().getTopInventory().getHolder();
        if (top instanceof SpawnerHolder || top instanceof FilterHolder) e.setCancelled(true);
    }
}
