package com.arena.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Melee AI for bots, tuned to behave like a human rather than an aura. Only active while a
 * bot is fighting in a match (after FIGHT!). Bots never heal by themselves: everything that
 * restores health or gives an effect comes from an item the bot visibly holds and uses.
 *
 * Movement/aim (all levels): turns toward the opponent at a limited speed, walks where it is
 * looking, swings only when roughly facing the opponent in reach after a short reaction time,
 * with random jitter on the cooldown and some misses.
 *
 * Items (all levels, the level only changes how early/precisely they are used). While an item
 * is being used it is shown in the bot's main hand and the bot cannot attack:
 *  kit 1/2: eats steaks when hurt (Regeneration I); kit 2 also raises its shield in bursts and
 *           swaps to the axe (which disables the shield) against a blocking opponent
 *  kit 3:   throws real Splash Healing II potions at its own feet
 *  kit 4:   golden apples, real bow shots from range, cobweb and lava at the opponent, water bucket when burning
 *  kit 5:   drinks Speed II, Strength II (and Fire Resistance when burning), throws real ender pearls
 *           to close distance, healing potions and golden apples, and a totem saves it once
 */
public class BotAi implements Listener {

    public enum Level {
        OFF(0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0),
        //   cooldown reach speed  miss heal hCd strafe react turn tol jitter
        EASY(14, 2.8, 0.19, 0.22, 8, 60, true, 5, 24, 28, 6),
        NORMAL(12, 3.0, 0.22, 0.10, 9, 40, true, 3, 36, 20, 4),
        HARD(11, 3.0, 0.23, 0.08, 10, 30, true, 2, 42, 18, 3);

        final int attackCooldown;
        final double reach;
        final double speed;
        final double missChance;
        final double healBelowHp;
        final int healCooldown;
        final boolean strafe;
        final int reactionTicks;
        final double turnPerTick;
        final double aimTolerance;
        final int attackJitter;

        Level(int attackCooldown, double reach, double speed, double missChance, double healBelowHp,
              int healCooldown, boolean strafe, int reactionTicks, double turnPerTick,
              double aimTolerance, int attackJitter) {
            this.attackCooldown = attackCooldown;
            this.reach = reach;
            this.speed = speed;
            this.missChance = missChance;
            this.healBelowHp = healBelowHp;
            this.healCooldown = healCooldown;
            this.strafe = strafe;
            this.reactionTicks = reactionTicks;
            this.turnPerTick = turnPerTick;
            this.aimTolerance = aimTolerance;
            this.attackJitter = attackJitter;
        }
    }

    private static class State {
        int kit;
        int steaks, gapples, healPots, arrows, webs, lava, water, pearls;
        boolean speedPot, strengthPot, fireResPot, totem;
        boolean healToggle;
        long nextAttackTick, nextHealTick, nextBowTick, nextWebTick, nextLavaTick, nextPearlTick, nextBlockTick;
        long blockingUntilTick;
        long usingUntilTick;
        Runnable pending;
        ItemStack weapon;
        long inReachSinceTick = -1;
        long noControlUntilMs;
        long nextStrafeSwitchTick;
        int strafeDir = 1;
        boolean immovable = true;
    }

    private static Level level = Level.NORMAL;
    private static long tick;
    private static final Map<UUID, State> states = new HashMap<>();

    public static void start(JavaPlugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, BotAi::tick, 1L, 1L);
    }

    public static Level getLevel() {
        return level;
    }

    public static boolean setLevel(String name) {
        try {
            level = Level.valueOf(name.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Called when a bot is dressed for a match: stocks its virtual inventory with what the kit contains. */
    public static void setKit(UUID id, int kit) {
        State s = new State();
        s.kit = kit;
        switch (kit) {
            case 1, 2 -> s.steaks = 6;
            case 3 -> s.healPots = 35;
            case 4 -> {
                s.gapples = 8;
                s.arrows = 32;
                s.webs = 8;
                s.lava = 2;
                s.water = 2;
            }
            case 5 -> {
                s.gapples = 16;
                s.healPots = 27;
                s.pearls = 16;
                s.speedPot = true;
                s.strengthPot = true;
                s.fireResPot = true;
                s.totem = true;
            }
            default -> { }
        }
        states.put(id, s);
    }

    public static void reset(UUID id) {
        states.remove(id);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (BotManager.isBot(id)) {
            states.computeIfAbsent(id, k -> new State()).noControlUntilMs = System.currentTimeMillis() + 350;
        }
    }

    /** Totem of undying (kit 5): a lethal hit is cancelled once and the bot gets the totem effects. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!BotManager.isBot(id) || !(e.getEntity() instanceof LivingEntity bot)) return;
        State s = states.get(id);
        if (s == null || !s.totem || e.getFinalDamage() < bot.getHealth() + bot.getAbsorptionAmount()) return;

        e.setCancelled(true);
        s.totem = false;
        bot.setHealth(1.0);
        bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
    }

    /** Shield (kit 2): hits landing during a blocking burst do nothing, unless the attacker uses an axe. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldHit(EntityDamageByEntityEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!BotManager.isBot(id)) return;
        State s = states.get(id);
        if (s == null || s.kit != 2 || tick >= s.blockingUntilTick) return;

        if (e.getDamager() instanceof LivingEntity attacker && attacker.getEquipment() != null
                && attacker.getEquipment().getItemInMainHand().getType().name().endsWith("_AXE")) {
            s.blockingUntilTick = 0;
            s.nextBlockTick = tick + 100;
            return;
        }
        e.setCancelled(true);
    }

    private static void tick() {
        tick++;
        for (UUID id : BotManager.ids()) {
            LivingEntity bot = BotManager.get(id);
            if (bot == null || bot.isDead()) continue;

            boolean active = level != Level.OFF
                    && MatchManager.isFighting(id)
                    && MatchManager.isFightStarted()
                    && !MatchManager.isPaused()
                    && !MatchManager.isFrozen();

            State s = states.computeIfAbsent(id, k -> new State());
            bot.setInvulnerable(!MatchManager.isFighting(id)); // lobby bots cannot be hurt
            if (s.immovable == active) {
                s.immovable = !active;
                BotManager.setImmovable(bot, s.immovable);
            }
            if (!active) {
                keepStill(bot, id);
                s.inReachSinceTick = -1;
                continue;
            }

            UUID p1 = MatchManager.getPlayer1();
            UUID p2 = MatchManager.getPlayer2();
            UUID otherId = id.equals(p1) ? p2 : p1;
            LivingEntity target = otherId != null ? BotManager.get(otherId) : null;
            if (target == null || target.isDead() || !target.getWorld().equals(bot.getWorld())) continue;

            finishUse(bot, s);
            act(bot, target, s);
        }
    }

    /** Outside a fight a bot never walks: no sideways motion, and it is put back at its spot if it was moved. */
    private static void keepStill(LivingEntity bot, UUID id) {
        Vector v = bot.getVelocity();
        if (v.getX() != 0 || v.getZ() != 0) {
            v.setX(0);
            v.setZ(0);
            bot.setVelocity(v);
        }
        Location home = BotManager.home(id);
        if (home != null && !MatchManager.isFighting(id) && home.getWorld().equals(bot.getWorld())
                && bot.getLocation().distanceSquared(home) > 2.25) {
            bot.teleport(home);
        }
    }

    private static void act(LivingEntity bot, LivingEntity target, State s) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location bl = bot.getLocation();
        Location tl = target.getLocation();
        Vector flat = tl.toVector().subtract(bl.toVector());
        flat.setY(0);
        double dist = flat.length();
        if (dist < 0.01) return;
        boolean busy = tick < s.usingUntilTick;

        // Turn toward the target at a limited, slightly noisy rate instead of snapping onto it
        double wantedYaw = Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        double wantedPitch = -Math.toDegrees(Math.atan2(
                target.getEyeLocation().getY() - bot.getEyeLocation().getY(), dist));
        double yawDiff = wrap180(wantedYaw - bl.getYaw());
        double pitchDiff = wantedPitch - bl.getPitch();
        double turn = level.turnPerTick * (0.7 + 0.6 * rng.nextDouble());
        float newYaw = (float) (bl.getYaw() + Math.max(-turn, Math.min(turn, yawDiff)));
        float newPitch = (float) (bl.getPitch() + Math.max(-turn / 2, Math.min(turn / 2, pitchDiff)));
        bot.setRotation(newYaw, newPitch);
        double aimError = Math.abs(wrap180(wantedYaw - newYaw));

        // Movement: walk where the bot is looking; using an item slows it down like a real player
        if (System.currentTimeMillis() >= s.noControlUntilMs) {
            double speed = level.speed * (busy ? 0.35 : 1.0);
            PotionEffect speedEffect = bot.getPotionEffect(PotionEffectType.SPEED);
            if (speedEffect != null) speed *= 1.0 + 0.2 * (speedEffect.getAmplifier() + 1);

            double yawRad = Math.toRadians(newYaw);
            Vector look = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
            Vector v = bot.getVelocity();
            double hx = 0, hz = 0;
            if (dist > 2.0 && aimError < 70) {
                hx = look.getX() * speed;
                hz = look.getZ() * speed;
            }
            if (level.strafe && dist < 3.5) {
                if (tick >= s.nextStrafeSwitchTick) {
                    s.strafeDir = -s.strafeDir;
                    s.nextStrafeSwitchTick = tick + 12 + rng.nextInt(18);
                }
                hx += -look.getZ() * speed * 0.5 * s.strafeDir;
                hz += look.getX() * speed * 0.5 * s.strafeDir;
            }
            v.setX(hx);
            v.setZ(hz);
            if (bot.isOnGround() && blockedAhead(bl, look)) {
                v.setY(0.42);
            }
            bot.setVelocity(v);
        }

        if (!busy) {
            chooseItemAction(bot, target, s, dist, aimError, rng);
            busy = tick < s.usingUntilTick;
        }

        // Attack: must be in reach AND roughly facing the target, after a short reaction time; never while using an item
        boolean inReach = bl.distance(tl) <= level.reach;
        if (!inReach) {
            s.inReachSinceTick = -1;
        } else if (s.inReachSinceTick < 0) {
            s.inReachSinceTick = tick;
        }
        boolean reacted = s.inReachSinceTick >= 0 && tick - s.inReachSinceTick >= level.reactionTicks;

        if (!busy && inReach && reacted && aimError <= level.aimTolerance && tick >= s.nextAttackTick) {
            s.nextAttackTick = tick + level.attackCooldown + rng.nextInt(level.attackJitter + 1);
            bot.swingMainHand();
            if (rng.nextDouble() >= level.missChance) {
                strike(bot, target, s);
            }
        }
    }

    private static void strike(LivingEntity bot, LivingEntity target, State s) {
        ItemStack weapon = bot.getEquipment() != null ? bot.getEquipment().getItemInMainHand() : null;
        double damage;
        // Kit 2: swap to the axe against a blocking player; the axe disables the shield
        if (s.kit == 2 && target instanceof Player tp && tp.isBlocking()) {
            damage = 9.0;
            tp.setCooldown(Material.SHIELD, 100);
            tp.clearActiveItem();
        } else {
            damage = damageOf(weapon);
        }
        PotionEffect strength = bot.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null) damage += 3.0 * (strength.getAmplifier() + 1);

        target.damage(damage, bot);
        int fire = weapon != null ? weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT) : 0;
        if (fire > 0) target.setFireTicks(80 * fire);
    }

    // ---------------------------------------------------------------- items

    /** Picks at most one item to use right now; every use takes time and shows the item in the bot's hand. */
    private static void chooseItemAction(LivingEntity bot, LivingEntity target, State s, double dist,
                                         double aimError, ThreadLocalRandom rng) {
        Location bl = bot.getLocation();
        Location tl = target.getLocation();

        // 1) Healing when hurt
        if (bot.getHealth() <= level.healBelowHp && tick >= s.nextHealTick && tryHeal(bot, s)) {
            s.nextHealTick = tick + level.healCooldown;
            return;
        }

        // 2) Extinguish / fire protection
        if (bot.getFireTicks() > 0) {
            if (s.kit == 5 && s.fireResPot) {
                s.fireResPot = false;
                drink(bot, s, PotionType.LONG_FIRE_RESISTANCE, () ->
                        bot.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 3600, 0)));
                return;
            }
            if (s.kit == 4 && s.water > 0 && bot.getLocation().getBlock().getType().isAir()) {
                s.water--;
                startUse(bot, s, new ItemStack(Material.WATER_BUCKET), 6, () -> {
                    Block feet = bot.getLocation().getBlock();
                    if (feet.getType().isAir()) {
                        ArenaBlockListener.trackBlock(feet);
                        feet.setType(Material.WATER);
                    }
                    bot.setFireTicks(0);
                });
                return;
            }
        }

        switch (s.kit) {
            case 4 -> {
                if (s.arrows > 0 && dist > 6 && dist < 24 && aimError < 20 && tick >= s.nextBowTick) {
                    s.arrows--;
                    s.nextBowTick = tick + 40;
                    startUse(bot, s, new ItemStack(Material.BOW), 20, () -> shootArrow(bot, target, rng));
                    return;
                }
                if (s.webs > 0 && dist > 2.5 && dist < 5 && tick >= s.nextWebTick) {
                    s.webs--;
                    s.nextWebTick = tick + 160;
                    startUse(bot, s, new ItemStack(Material.COBWEB), 4, () -> {
                        Block feet = target.getLocation().getBlock();
                        if (feet.getType().isAir()) {
                            ArenaBlockListener.trackBlock(feet);
                            feet.setType(Material.COBWEB);
                        }
                    });
                    return;
                }
                if (s.lava > 0 && dist > 3 && dist < 6 && bot.getHealth() > 10 && tick >= s.nextLavaTick) {
                    s.lava--;
                    s.nextLavaTick = tick + 400;
                    startUse(bot, s, new ItemStack(Material.LAVA_BUCKET), 6, () -> {
                        Vector away = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
                        if (away.lengthSquared() < 0.01) return;
                        Block spot = target.getLocation().clone().add(away.normalize()).getBlock();
                        if (spot.getType().isAir() && !spot.getRelative(0, -1, 0).getType().isAir()) {
                            ArenaBlockListener.trackBlock(spot);
                            spot.setType(Material.LAVA);
                        }
                    });
                    return;
                }
            }
            case 5 -> {
                if (s.speedPot && dist > 4) {
                    s.speedPot = false;
                    drink(bot, s, PotionType.STRONG_SWIFTNESS, () ->
                            bot.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 1)));
                    return;
                }
                if (s.strengthPot && dist > 4) {
                    s.strengthPot = false;
                    drink(bot, s, PotionType.STRONG_STRENGTH, () ->
                            bot.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 3600, 1)));
                    return;
                }
                if (s.pearls > 0 && dist > 12 && tick >= s.nextPearlTick) {
                    s.pearls--;
                    s.nextPearlTick = tick + 300;
                    startUse(bot, s, new ItemStack(Material.ENDER_PEARL), 6, () -> throwPearl(bot, target));
                    return;
                }
            }
            case 2 -> {
                if (dist < 4.0 && tick >= s.nextBlockTick) {
                    s.nextBlockTick = tick + 30 + rng.nextInt(20);
                    if (rng.nextDouble() < 0.5) s.blockingUntilTick = tick + 12;
                }
            }
            default -> { }
        }
    }

    /** Uses one healing item of the bot's kit. Returns false if it has nothing left to heal with. */
    private static boolean tryHeal(LivingEntity bot, State s) {
        switch (s.kit) {
            case 1, 2 -> {
                if (s.steaks <= 0) return false;
                s.steaks--;
                startUse(bot, s, new ItemStack(Material.COOKED_BEEF), 32, () ->
                        bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0)));
                return true;
            }
            case 3 -> {
                return throwHealPotion(bot, s);
            }
            case 4 -> {
                return eatGoldenApple(bot, s);
            }
            case 5 -> {
                s.healToggle = !s.healToggle;
                boolean first = s.healToggle ? throwHealPotion(bot, s) : eatGoldenApple(bot, s);
                return first || (s.healToggle ? eatGoldenApple(bot, s) : throwHealPotion(bot, s));
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean eatGoldenApple(LivingEntity bot, State s) {
        if (s.gapples <= 0) return false;
        s.gapples--;
        startUse(bot, s, new ItemStack(Material.GOLDEN_APPLE), 32, () -> {
            bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
            bot.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
        });
        return true;
    }

    private static boolean throwHealPotion(LivingEntity bot, State s) {
        if (s.healPots <= 0) return false;
        s.healPots--;
        ItemStack potion = potionStack(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
        startUse(bot, s, potion, 6, () -> {
            ThrownPotion thrown = bot.launchProjectile(ThrownPotion.class, new Vector(0, -0.4, 0));
            thrown.setItem(potion);
            ArenaBlockListener.trackEntity(thrown);
        });
        return true;
    }

    private static void drink(LivingEntity bot, State s, PotionType type, Runnable effect) {
        startUse(bot, s, potionStack(Material.POTION, type), 32, effect);
    }

    private static ItemStack potionStack(Material material, PotionType type) {
        ItemStack item = new ItemStack(material);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    private static void shootArrow(LivingEntity bot, LivingEntity target, ThreadLocalRandom rng) {
        Vector aim = target.getEyeLocation().toVector().subtract(bot.getEyeLocation().toVector());
        double dist = aim.length();
        aim.setY(aim.getY() + dist * 0.03); // compensate arrow drop
        aim.normalize().multiply(2.8);
        double spread = 0.05 + level.missChance * 0.4;
        aim.add(new Vector((rng.nextDouble() - 0.5) * spread, (rng.nextDouble() - 0.5) * spread,
                (rng.nextDouble() - 0.5) * spread));

        Arrow arrow = bot.launchProjectile(Arrow.class, aim);
        arrow.setDamage(3.5); // Power II bow
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        ArenaBlockListener.trackEntity(arrow);
    }

    private static void throwPearl(LivingEntity bot, LivingEntity target) {
        Vector to = target.getLocation().toVector().subtract(bot.getLocation().toVector());
        double range = Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
        double speed = 1.5;
        double angle = 0.5 * Math.asin(Math.min(1.0, range * 0.03 / (speed * speed)));
        Vector horizontal = new Vector(to.getX(), 0, to.getZ()).normalize();
        Vector velocity = horizontal.multiply(speed * Math.cos(angle)).setY(speed * Math.sin(angle) + 0.1);

        EnderPearl pearl = bot.launchProjectile(EnderPearl.class, velocity);
        ArenaBlockListener.trackEntity(pearl);
    }

    /** Shows `shown` in the bot's hand for `ticks`, then runs `effect` and puts the weapon back. */
    private static void startUse(LivingEntity bot, State s, ItemStack shown, int ticks, Runnable effect) {
        EntityEquipment eq = bot.getEquipment();
        if (eq != null) {
            if (s.weapon == null) s.weapon = eq.getItemInMainHand().clone();
            eq.setItemInMainHand(shown);
        }
        s.usingUntilTick = tick + ticks;
        s.pending = effect;
    }

    private static void finishUse(LivingEntity bot, State s) {
        if (s.pending == null || tick < s.usingUntilTick) return;
        Runnable effect = s.pending;
        s.pending = null;
        try {
            effect.run();
        } catch (RuntimeException ex) {
            Bukkit.getLogger().warning("[ArenaPlugin] Bot item use failed: " + ex);
        }
        EntityEquipment eq = bot.getEquipment();
        if (eq != null && s.weapon != null) {
            eq.setItemInMainHand(s.weapon);
        }
        s.weapon = null;
    }

    // ---------------------------------------------------------------- helpers

    private static double wrap180(double angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static boolean blockedAhead(Location bl, Vector dir) {
        Location front = bl.clone().add(dir.clone().multiply(0.7));
        return !front.getBlock().isPassable() && front.clone().add(0, 1, 0).getBlock().isPassable()
                && front.clone().add(0, 2, 0).getBlock().isPassable();
    }

    private static double damageOf(ItemStack weapon) {
        if (weapon == null) return 1.0;
        double base = switch (weapon.getType()) {
            case NETHERITE_SWORD -> 8.0;
            case DIAMOND_SWORD -> 7.0;
            default -> 1.0;
        };
        int sharp = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        return sharp > 0 ? base + 0.5 * sharp + 0.5 : base;
    }
}
