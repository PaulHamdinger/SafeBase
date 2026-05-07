//==============================================================================
// EnforcementListener.java
//==============================================================================
//
// Core enforcement : prevents denied players from being inside safe zones.
// Handles walk-in (PlayerMoveEvent — safe zone only), teleport-in
// (PlayerTeleportEvent), login (PlayerJoinEvent), and respawn
// (PlayerRespawnEvent). The repeating background task handles warning-zone
// detection, warning messages, safety-net ejection, last-safe updates, and
// maintains the set of players near denied zones for the move-event fast
// path.
//
//==============================================================================

package com.nethercore.safebase;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;


public final class EnforcementListener implements Listener {

    private final SafeBasePlugin      plugin;
    private final SafeLocationTracker tracker;

    // Warning cooldowns keyed by "playerUUID:zoneId"
    private final Map< String, Long > warningCooldowns = new HashMap<>();

    // Per-player cooldown for "SafeBase Denied" titles (prevents spam on redirect).
    private final Map< UUID, Long > accessDeniedCooldowns = new HashMap<>();

    // Players currently inside a denied warning zone. onPlayerMove uses this
    // to skip safe-zone enforcement for players nowhere near a zone.
    private final Set< UUID > deniedInsideWarning = new HashSet<>();


    public EnforcementListener( SafeBasePlugin plugin, SafeLocationTracker tracker ) {

        this.plugin  = plugin;
        this.tracker = tracker;
    }


    //==========================================================================
    // startBackgroundTask - warning zones, safety net, particles
    //==========================================================================

    public void startBackgroundTask() {

        int interval = plugin.safeBaseConfig().slowTaskIntervalTicks();

        new BukkitRunnable() {
            @Override
            public void run() {
                for ( Player player : plugin.getServer().getOnlinePlayers() ) {
                    backgroundCheck( player );
                }
            }
        }.runTaskTimer( plugin, interval, interval );

        // Particles run on a separate, slower timer (80 ticks = 4 seconds) to
        // reduce network traffic. Background checks need 2-second responsiveness
        // for warning detection, but particle updates are purely cosmetic.
        new BukkitRunnable() {
            @Override
            public void run() {
                spawnLecternParticles();
            }
        }.runTaskTimer( plugin, 80, 80 );
    }


    //==========================================================================
    // PlayerMoveEvent - hard perimeter enforcement
    //==========================================================================
    //
    // Only performs safe-zone enforcement for players flagged as near a denied
    // zone (the set is maintained by the background task). All other checks
    // (warnings, last-safe updates) run in the background on a 2-second
    // interval.
    //
    //==========================================================================

    @EventHandler( priority = EventPriority.HIGH, ignoreCancelled = true )
    public void onPlayerMove( PlayerMoveEvent event ) {

        // Only react to block-boundary crossings.
        if ( event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ() ) return;

        Player player = event.getPlayer();

        if ( player.isOp() ) return;

        // Skip safe-zone scan for players not near any denied zone.
        if ( !deniedInsideWarning.contains( player.getUniqueId() ) ) return;

        Location to = event.getTo();

        Zone zone = plugin.zoneStore().findSafeZoneAt(
            to.getWorld().getName(), to.getBlockX(), to.getBlockZ()
        );

        if ( zone != null && zone.isDenied( player.getUniqueId(), player.getName() ) ) {
            // Redirect to the center of the from-block (same as WorldGuard's approach).
            // Pushing to the exact sub-block position allows cumulative drift through
            // the barrier since the block-boundary filter skips sub-block movements.
            Location redirect = event.getFrom().clone();
            redirect.setX( redirect.getBlockX() + 0.5 );
            redirect.setZ( redirect.getBlockZ() + 0.5 );
            redirect.setPitch( event.getTo().getPitch() );
            redirect.setYaw( event.getTo().getYaw() );
            event.setTo( redirect );

            // Record this centered block as the last safe position. The tracker
            // is no longer updated by the background task — only at denial time
            // — so the fallback is always a solid block outside the perimeter.
            tracker.update( player, redirect );

            // Eject vehicle chain if mounted (redirect alone doesn't work for riders).
            Entity vehicle = player.getVehicle();
            if ( vehicle != null ) {
                vehicle.eject();

                Entity current = vehicle;
                while ( current != null ) {
                    current.eject();
                    current.setVelocity( new Vector() );
                    if ( current instanceof LivingEntity ) {
                        current.teleport( redirect );
                    } else {
                        current.teleport( redirect.clone().add( 0, 1, 0 ) );
                    }
                    current = current.getVehicle();
                }

                Location dismount = redirect.clone().add( 0, 1, 0 );
                player.teleport( dismount );

                // One-tick delayed teleport prevents client-side rubberbanding.
                plugin.getServer().getScheduler().runTaskLater( plugin, () -> player.teleport( dismount.clone() ), 1 );
            }

            showAccessDenied( player, zone );
        }
    }


    //==========================================================================
    // PlayerTeleportEvent - redirect teleports into denied zones
    //==========================================================================

    @EventHandler( priority = EventPriority.HIGH, ignoreCancelled = true )
    public void onPlayerTeleport( PlayerTeleportEvent event ) {

        Player player = event.getPlayer();
        Location to = event.getTo();
        if ( to == null || to.getWorld() == null ) return;

        // Update tracker for everyone (including ops) before any guard.
        tracker.update( player, to );

        if ( player.isOp() ) return;

        Zone zone = plugin.zoneStore().findSafeZoneAt(
            to.getWorld().getName(), to.getBlockX(), to.getBlockZ()
        );

        if ( zone != null && zone.isDenied( player.getUniqueId(), player.getName() ) ) {
            event.setCancelled( true );
            showAccessDenied( player, zone );

            if ( event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY ) {
                String ownerName = zone.ownerName();
                String portalMsg = ownerName != null
                    ? "Portal blocked - You cannot enter " + ownerName + "'s SafeBase"
                    : "Portal blocked - You cannot enter this SafeBase";
                player.sendMessage( Component.text( portalMsg, NamedTextColor.RED ) );
            }
            return;
        }
    }


    //==========================================================================
    // PlayerJoinEvent - eject if inside a denied zone
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR )
    public void onPlayerJoin( PlayerJoinEvent event ) {

        Player player = event.getPlayer();

        // Update tracker if join location is safe.
        tracker.update( player, player.getLocation() );

        if ( player.isOp() ) return;

        Location loc = player.getLocation();

        Zone zone = plugin.zoneStore().findSafeZoneAt(
            loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ()
        );

        if ( zone != null && zone.isDenied( player.getUniqueId(), player.getName() ) ) {
            plugin.getServer().getScheduler().runTask( plugin, () -> {
                Location safe = tracker.resolve( player );
                player.teleport( safe );
                showAccessDenied( player, zone );
            } );
        }
    }


    //==========================================================================
    // PlayerRespawnEvent - redirect if respawn would land in a denied zone
    //==========================================================================

    @EventHandler( priority = EventPriority.HIGH )
    public void onPlayerRespawn( PlayerRespawnEvent event ) {

        Player player = event.getPlayer();
        if ( player.isOp() ) return;

        Location respawnLoc = event.getRespawnLocation();

        Zone zone = plugin.zoneStore().findSafeZoneAt(
            respawnLoc.getWorld().getName(), respawnLoc.getBlockX(), respawnLoc.getBlockZ()
        );

        if ( zone != null && zone.isDenied( player.getUniqueId(), player.getName() ) ) {
            // Fall through to world spawn (the guaranteed-safe fallback).
            Location worldSpawn = respawnLoc.getWorld().getSpawnLocation().clone().add( 0.5, 0, 0.5 );
            event.setRespawnLocation( worldSpawn );
            // Notify after respawn completes.
            plugin.getServer().getScheduler().runTask( plugin, () -> showAccessDenied( player, zone ) );
        }
    }


    //==========================================================================
    // PlayerQuitEvent - clean up tracker
    //==========================================================================

    @EventHandler
    public void onPlayerQuit( PlayerQuitEvent event ) {

        UUID uuid = event.getPlayer().getUniqueId();
        tracker.remove( uuid );
        deniedInsideWarning.remove( uuid );
        accessDeniedCooldowns.remove( uuid );
    }


    //==========================================================================
    // backgroundCheck - warning zone + safety net + set management
    //==========================================================================

    private void backgroundCheck( Player player ) {

        Location loc = player.getLocation();
        Config cfg = plugin.safeBaseConfig();
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        UUID uuid = player.getUniqueId();

        if ( !player.isOp() ) {

            // Fast-path : skip zone scan if player's chunk has no zones.
            if ( !plugin.zoneStore().hasZoneInChunk( world, x >> 4, z >> 4 ) ) {
                deniedInsideWarning.remove( uuid );
            } else {

                //--------------------------------------------------------------
                // Safety net : if somehow inside a denied safe zone, eject.
                //--------------------------------------------------------------

                Zone safeZone = plugin.zoneStore().findSafeZoneAt( world, x, z );
                if ( safeZone != null && safeZone.isDenied( uuid, player.getName() ) ) {
                    Location safe = tracker.resolve( player );
                    player.teleport( safe );
                    showAccessDenied( player, safeZone );
                    deniedInsideWarning.remove( uuid );
                    return;
                }

                //--------------------------------------------------------------
                // Warning zone detection + entry check.
                //--------------------------------------------------------------

                Zone warnZone = plugin.zoneStore().findWarningZoneAt(
                    world, x, z, cfg.warningMoatWidth()
                );
                if ( warnZone != null && warnZone.isDenied( uuid, player.getName() ) ) {
                    boolean wasInside = deniedInsideWarning.contains( uuid );
                    deniedInsideWarning.add( uuid );
                    if ( !wasInside ) {
                        sendWarning( player, warnZone );
                    }
                } else {
                    deniedInsideWarning.remove( uuid );
                }
            }
        } else {
            deniedInsideWarning.remove( uuid );
        }

        // Last-safe is no longer updated here — only set at denial time in
        // onPlayerMove, so the fallback is always a centered block outside
        // the safe zone perimeter. Lifecycle events (teleport, join, quit,
        // disable) still update the tracker independently.
    }


    //==========================================================================
    // sendWarning
    //==========================================================================

    private void sendWarning( Player player, Zone zone ) {

        String key = player.getUniqueId() + ":" + zone.id();
        long now = System.currentTimeMillis();
        long cooldownMs = plugin.safeBaseConfig().warningCooldownSeconds() * 1000L;

        Long last = warningCooldowns.get( key );
        if ( last != null && ( now - last ) < cooldownMs ) return;

        warningCooldowns.put( key, now );

        String ownerName = zone.ownerName();
        String subtitle = ownerName != null
            ? "Approaching " + ownerName + "'s SafeBase"
            : "Approaching SafeBase";

        player.showTitle( Title.title(
            Component.text( "Warning", NamedTextColor.YELLOW ).decorate( TextDecoration.BOLD ),
            Component.text( subtitle, NamedTextColor.RED ).decorate( TextDecoration.ITALIC ),
            Title.Times.times( Duration.ofMillis( 200 ), Duration.ofSeconds( 3 ), Duration.ofMillis( 500 ) )
        ) );
        player.playSound( player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.8f );
    }


    //==========================================================================
    // showAccessDenied
    //==========================================================================

    private void showAccessDenied( Player player, Zone zone ) {

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = accessDeniedCooldowns.get( uuid );
        if ( last != null && ( now - last ) < 2000 ) return;
        accessDeniedCooldowns.put( uuid, now );

        String ownerName = zone.ownerName();
        String subtitle = ownerName != null
            ? "You cannot enter " + ownerName + "'s SafeBase"
            : "You cannot enter this SafeBase";

        player.showTitle( Title.title(
            Component.text( "SafeBase Denied", NamedTextColor.RED ).decorate( TextDecoration.BOLD ),
            Component.text( subtitle, NamedTextColor.GRAY ),
            Title.Times.times( Duration.ofMillis( 200 ), Duration.ofSeconds( 2 ), Duration.ofMillis( 500 ) )
        ) );
        player.playSound( player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f );
    }


    //==========================================================================
    // spawnLecternParticles
    //==========================================================================
    //
    // Spawns END_ROD particles in a column above the 3x3 platform area for
    // active zone lecterns. Only processes lecterns in loaded chunks.
    //
    //==========================================================================

    private void spawnLecternParticles() {

        Config.ParticleEffectType effect = plugin.safeBaseConfig().particleEffect();

        for ( Zone zone : plugin.zoneStore().all() ) {
            World world = plugin.getServer().getWorld( zone.world() );
            if ( world == null ) continue;

            int chunkX = zone.anchorX() >> 4;
            int chunkZ = zone.anchorZ() >> 4;
            if ( !world.isChunkLoaded( chunkX, chunkZ ) ) continue;

            double cx = zone.anchorX() + 0.5;
            double cz = zone.anchorZ() + 0.5;
            double baseY = zone.anchorY();

            switch ( effect ) {
                case BEACON          -> spawnBeaconEffect( world, zone, cx, cz, baseY );
                case COLORED_BEACON  -> spawnColoredBeacon( world, zone, cx, cz, baseY );
                case COLOR_RING      -> spawnColorRing( world, zone, cx, cz, baseY );
                case SHIELD_DOME     -> spawnShieldDome( world, zone, cx, cz, baseY );
            }
        }
    }

    //----- effect implementations -----------------------------------------------

    /** GLOW column + END_ROD sparkle on each platform block (current default). */
    private void spawnBeaconEffect( World world, Zone zone, double cx, double cz, double baseY ) {

        world.spawnParticle( Particle.GLOW,
            new Location( world, cx, baseY + 0.5, cz ),
            20, 0.4, 15.0, 0.4, 0
        );

        for ( int dx = -1; dx <= 1; dx++ ) {
            for ( int dz = -1; dz <= 1; dz++ ) {
                world.spawnParticle( Particle.END_ROD,
                    new Location( world,
                        zone.anchorX() + dx + 0.5,
                        baseY + 0.3,
                        zone.anchorZ() + dz + 0.5 ),
                    1, 0.1, 0.05, 0.1, 0.002
                );
            }
        }
    }


    /** DUST column tinted by zone mode (white = whitelist, purple = blacklist). */
    private void spawnColoredBeacon( World world, Zone zone, double cx, double cz, double baseY ) {

        world.spawnParticle( Particle.DUST,
            new Location( world, cx, baseY + 0.5, cz ),
            20, 0.4, 15.0, 0.4, 0,
            new DustOptions( zone.mode() == Zone.Mode.WHITELIST ? Color.WHITE : Color.fromRGB( 80, 0, 130 ), 1.0f )
        );
    }


    /** DUST ring at platform height, tinted by zone mode. Slowly rotates across refreshes. */
    private void spawnColorRing( World world, Zone zone, double cx, double cz, double baseY ) {

        float size = 0.75f;
        DustOptions opts = new DustOptions(
            zone.mode() == Zone.Mode.WHITELIST ? Color.WHITE : Color.fromRGB( 80, 0, 130 ),
            size
        );

        int    count     = 8;
        double radius    = 0.75;
        int    speed     = 12;     // steps per refresh
        double heightOff = 1.0;

        long step = System.currentTimeMillis() / 2000;
        double angleOffset = ( step * speed % count ) * ( Math.PI * 2 / count );

        for ( int i = 0; i < count; i++ ) {
            double angle = i * ( Math.PI * 2 / count ) + angleOffset;
            world.spawnParticle( Particle.DUST,
                new Location( world, cx + radius * Math.cos( angle ), baseY + heightOff, cz + radius * Math.sin( angle ) ),
                1, 0, 0, 0, 0,
                opts
            );
        }
    }


    /** Hemisphere of DUST over the platform, tinted by zone mode. */
    private void spawnShieldDome( World world, Zone zone, double cx, double cz, double baseY ) {

        DustOptions opts = new DustOptions(
            zone.mode() == Zone.Mode.WHITELIST ? Color.WHITE : Color.fromRGB( 80, 0, 130 ),
            1.0f
        );

        int count = 30;
        double radius = 2.5;

        for ( int i = 0; i < count; i++ ) {
            double theta = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double phi   = ThreadLocalRandom.current().nextDouble() * Math.PI * 0.5;   // 0 to PI/2
            double r     = radius * ThreadLocalRandom.current().nextDouble();           // 0 to radius

            world.spawnParticle( Particle.DUST,
                new Location( world,
                    cx + r * Math.sin( phi ) * Math.cos( theta ),
                    baseY + r * Math.cos( phi ),
                    cz + r * Math.sin( phi ) * Math.sin( theta ) ),
                1, 0, 0, 0, 0,
                opts
            );
        }
    }


    //==========================================================================
    // runInitialScan
    //==========================================================================
    //
    // Called after zones are loaded (on enable, on refresh, and after zone
    // creation). Checks all online players against all zones and ejects /
    // warns as needed.
    //
    //==========================================================================

    public void runInitialScan() {

        Config cfg = plugin.safeBaseConfig();

        for ( Player player : plugin.getServer().getOnlinePlayers() ) {
            if ( player.isOp() ) continue;

            Location loc = player.getLocation();
            String world = loc.getWorld().getName();
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            UUID uuid = player.getUniqueId();

            // Fast-path : skip zone scan if this chunk has no zones.
            if ( !plugin.zoneStore().hasZoneInChunk( world, x >> 4, z >> 4 ) ) continue;

            Zone safeZone = plugin.zoneStore().findSafeZoneAt( world, x, z );
            if ( safeZone != null && safeZone.isDenied( uuid, player.getName() ) ) {
                Location safe = tracker.resolve( player );
                player.teleport( safe );
                showAccessDenied( player, safeZone );
                deniedInsideWarning.remove( uuid );
                continue;
            }

            Zone warnZone = plugin.zoneStore().findWarningZoneAt(
                world, x, z, cfg.warningMoatWidth()
            );
            if ( warnZone != null && warnZone.isDenied( uuid, player.getName() ) ) {
                deniedInsideWarning.add( uuid );
                sendWarning( player, warnZone );
            }
        }
    }


    //==========================================================================
    // removeZoneCooldowns
    //==========================================================================

    public void removeZoneCooldowns( String zoneId ) {

        warningCooldowns.keySet().removeIf( key -> key.endsWith( ":" + zoneId ) );
    }
}
