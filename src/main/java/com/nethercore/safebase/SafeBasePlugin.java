//==============================================================================
// SafeBasePlugin.java
//==============================================================================
//
// Plugin entry point. Holds the loaded Config, registers commands via
// Paper's LifecycleEvents.COMMANDS API (the legacy JavaPlugin#getCommand
// path is forbidden in Paper-plugin context). Other classes get a
// SafeBasePlugin reference passed in rather than reaching for a static
// singleton.
//
//==============================================================================

package com.nethercore.safebase;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


public final class SafeBasePlugin extends JavaPlugin {

    private Config               config;
    private ZoneStore            zoneStore;
    private SafeLocationTracker  tracker;
    private EnforcementListener  enforcement;
    private BookResyncListener   bookResync;


    //==========================================================================
    // onEnable
    //==========================================================================

    @Override
    public void onEnable() {

        //----------------------------------------------------------------------
        // Config : write the shipped config.yml on first run, then load.
        //----------------------------------------------------------------------

        saveDefaultConfig();
        reloadConfigFromDisk();


        //----------------------------------------------------------------------
        // Zone store : load all YAML records into memory.
        //----------------------------------------------------------------------

        zoneStore = new ZoneStore( getDataFolder(), getLogger() );
        zoneStore.setWarningMoatWidth( config.warningMoatWidth() );
        zoneStore.loadAll();


        //----------------------------------------------------------------------
        // Commands : registered through the Paper Brigadier registrar.
        //----------------------------------------------------------------------

        getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> event.registrar().register(
                new SafeBaseCommand( this ).buildCommandTree(),
                "SafeBase admin commands.",
                java.util.List.of()
            )
        );


        //----------------------------------------------------------------------
        // Listeners + enforcement
        //----------------------------------------------------------------------

        tracker     = new SafeLocationTracker( this );
        tracker.loadFromFile( new File( getDataFolder(), "last-safe-cache.yml" ) );
        enforcement = new EnforcementListener( this, tracker );
        bookResync  = new BookResyncListener( this );

        // Wire up zone-deletion cleanup so the warning cooldown map doesn't leak.
        zoneStore.setOnDelete( zoneId -> enforcement.removeZoneCooldowns( zoneId ) );

        getServer().getPluginManager().registerEvents( new LecternListener( this ), this );
        getServer().getPluginManager().registerEvents( enforcement, this );
        getServer().getPluginManager().registerEvents( bookResync, this );

        runWorldSpawnAutoDisable();
        enforcement.startBackgroundTask();
        enforcement.runInitialScan();
        bookResync.resyncLoadedChunks();

        // Periodic last-safe cache save (every 60 seconds, crash resilience).
        getServer().getScheduler().runTaskTimer( this, () -> {
            tracker.saveToFile( new File( getDataFolder(), "last-safe-cache.yml" ) );
        }, 1200L, 1200L );

        printBanner();
    }


    //==========================================================================
    // onDisable
    //==========================================================================

    @Override
    public void onDisable() {

        if ( tracker != null ) {
            // Re-populate before saving — PlayerQuitEvent fires before onDisable
            // and the quit handler clears the tracker.
            for ( Player player : getServer().getOnlinePlayers() ) {
                tracker.update( player, player.getLocation() );
            }
            tracker.saveToFile( new File( getDataFolder(), "last-safe-cache.yml" ) );
        }

        getLogger().info( "SafeBase v5.0.0 disabled." );
    }


    //==========================================================================
    // reloadConfigFromDisk
    //==========================================================================
    //
    // Re-reads config.yml from disk into a fresh Config instance. Called
    // on enable and from /safebase refresh. Future zone-store reload work
    // will hook in here too.
    //
    //==========================================================================

    public void reloadConfigFromDisk() {

        reloadConfig();

        int oldVersion = getConfig().getInt( "schema-version", -1 );
        boolean needsMigration = oldVersion < 5;

        this.config = Config.load( getConfig(), getLogger() );

        if ( needsMigration ) {
            // The in-memory migration worked, but Bukkit's saveConfig() preserves
            // the original file structure — old keys and comments leak through.
            // Rewrite cleanly from the shipped template, overlaying all values.
            List< String > allowedPlayers = getConfig().getStringList( "allowed-players" );
            int safe     = config.defaultSafeZoneHalfWidth();
            int moat     = config.warningMoatWidth();
            int cooldown = config.warningCooldownSeconds();
            int interval = config.slowTaskIntervalTicks();

            saveResource( "config.yml", true ); // overwrite with the fresh template

            reloadConfig();
            getConfig().set( "default-safe-zone-half-width",   safe );
            getConfig().set( "warning-moat-width",             moat );
            getConfig().set( "warning-cooldown-seconds",       cooldown );
            getConfig().set( "slow-task-interval-ticks",       interval );
            getConfig().set( "allowed-players",                allowedPlayers );
            getConfig().set( "schema-version",                 5 );
            saveConfig();

            // Re-read so Config reflects the saved values exactly.
            this.config = Config.load( getConfig(), getLogger() );
        }

        if ( zoneStore != null ) {
            zoneStore.setWarningMoatWidth( config.warningMoatWidth() );
            zoneStore.loadAll();
            runWorldSpawnAutoDisable();
        }

        if ( enforcement != null ) {
            enforcement.runInitialScan();
        }

        if ( bookResync != null ) {
            bookResync.resyncLoadedChunks();
        }
    }


    //==========================================================================
    // runWorldSpawnAutoDisable
    //==========================================================================
    //
    // Scans every loaded zone against its world's spawn point. Zones whose
    // safe-zone box contains world spawn are removed from memory and disk.
    // The book on the lectern is NOT rewritten here (would require chunk
    // loading) ; the YAML record is simply deleted. Runs on enable and on
    // /safebase refresh.
    //
    //==========================================================================

    private void runWorldSpawnAutoDisable() {

        List< Zone > toDisable = new ArrayList<>();

        for ( Zone zone : zoneStore.all() ) {
            World world = Bukkit.getWorld( zone.world() );
            if ( world == null ) continue;

            Location spawn = world.getSpawnLocation();
            if ( zone.containsWorldSpawn( spawn.getBlockX(), spawn.getBlockZ() ) ) {
                toDisable.add( zone );
            }
        }

        for ( Zone zone : toDisable ) {
            zoneStore.delete( zone.id() );
            getLogger().warning( "Auto-disabled zone " + zone.id() + " (world spawn inside safe zone). "
                + "Anchor: " + zone.world() + " " + zone.anchorX() + "," + zone.anchorY() + "," + zone.anchorZ()
                + " Owner: " + zone.owner() );

            Player owner = Bukkit.getPlayer( zone.owner() );
            if ( owner != null ) {
                owner.sendMessage( Component.text(
                    "Your SafeBase at " + zone.anchorX() + ", " + zone.anchorZ()
                    + " was disabled because it contains the world spawn point.",
                    NamedTextColor.RED
                ) );
            }
        }
    }


    //==========================================================================
    // notifyOwner — in-game notification for zone events
    //==========================================================================
    //
    // Sends a title to the zone owner if they are online. Does not modify the
    // book on the lectern — the owner discovers the state change via the title
    // or by noticing the absence of particle effects around the platform.
    //
    //==========================================================================

    public void notifyOwner( Zone zone, String subtitle ) {

        Player owner = Bukkit.getPlayer( zone.owner() );
        if ( owner != null ) {
            owner.showTitle( Title.title(
                Component.text( "SafeBase Disabled", NamedTextColor.RED ).decorate( TextDecoration.BOLD ),
                Component.text( subtitle, NamedTextColor.GRAY )
            ) );
        }
    }


    //==========================================================================
    // tryResizeZone — centralized resize validation
    //==========================================================================
    //
    // Validates and applies a zone half-width change. Checks world-spawn
    // containment and inter-zone overlap. If either check fails, the zone is
    // disabled (book rewritten, YAML deleted). On success, saves the updated
    // zone and runs an enforcement re-scan.
    //
    // Returns true if the zone was resized. Returns false if unchanged (caller
    // should check) or if the zone was disabled by validation.
    //
    //==========================================================================

    public boolean tryResizeZone( Zone zone, int newHalfWidth, World world ) {

        // Verify zone still exists.
        if ( zoneStore.byId( zone.id() ) == null ) return false;

        //----------------------------------------------------------------------
        // World-spawn check : zone must not contain world spawn.
        //----------------------------------------------------------------------

        Location spawn = world.getSpawnLocation();
        if ( Math.abs( spawn.getBlockX() - zone.anchorX() ) <= newHalfWidth
            && Math.abs( spawn.getBlockZ() - zone.anchorZ() ) <= newHalfWidth ) {
            zoneStore.delete( zone.id() );
            notifyOwner( zone, "Resize would contain world spawn." );
            getLogger().warning( "Zone " + zone.id()
                + " disabled (resize would contain world spawn)." );
            return false;
        }

        //----------------------------------------------------------------------
        // Overlap check : zone must not overlap another existing zone.
        //----------------------------------------------------------------------

        Zone overlap = zoneStore.findOverlap( zone.world(), zone.anchorX(), zone.anchorZ(),
            newHalfWidth, zone.id() );
        if ( overlap != null ) {
            zoneStore.delete( zone.id() );
            notifyOwner( zone, "Resize overlaps zone " + overlap.id() + "." );
            getLogger().warning( "Zone " + zone.id()
                + " disabled (resize overlaps zone " + overlap.id() + ")." );
            return false;
        }

        //----------------------------------------------------------------------
        // Apply resize.
        //----------------------------------------------------------------------

        Zone updated = new Zone(
            zone.id(), zone.world(),
            zone.anchorX(), zone.anchorY(), zone.anchorZ(),
            zone.owner(), zone.mode(), newHalfWidth,
            zone.allow(), zone.deny(),
            zone.ownerName(), zone.createdAt(), Instant.now()
        );

        zoneStore.save( updated );
        getLogger().info( "Zone " + zone.id() + " resized to halfWidth=" + newHalfWidth );

        enforcement().runInitialScan();
        return true;
    }


    //==========================================================================
    // printBanner
    //==========================================================================

    private void printBanner() {

        String v = getPluginMeta().getVersion();
        Component g = Component.text( "██", NamedTextColor.GREEN );
        Component s = Component.text( "  " );

        Bukkit.getConsoleSender().sendMessage( Component.empty() );
        Bukkit.getConsoleSender().sendMessage( Component.text()
            .append( g ).append( g ).append( g ).append( g ).append( g ).build() );
        Bukkit.getConsoleSender().sendMessage( Component.text()
            .append( g ).append( s ).append( g ).append( g ).append( g )
            .append( Component.text( "  SafeBase", NamedTextColor.GOLD ) ).build() );
        Bukkit.getConsoleSender().sendMessage( Component.text()
            .append( g ).append( s ).append( g ).append( s ).append( g )
            .append( Component.text( "  v" + v, NamedTextColor.GRAY ) ).build() );
        Bukkit.getConsoleSender().sendMessage( Component.text()
            .append( g ).append( s ).append( g ).append( g ).append( g ).build() );
        Bukkit.getConsoleSender().sendMessage( Component.text()
            .append( g ).append( g ).append( g ).append( g ).append( g ).build() );
        Bukkit.getConsoleSender().sendMessage( Component.empty() );
    }


    //==========================================================================
    // isAllowedToCreate
    //==========================================================================

    public boolean isAllowedToCreate( Player player ) {

        if ( player.isOp() ) return true;

        String name = player.getName();
        String uuid = player.getUniqueId().toString();

        for ( String entry : config.allowedPlayers() ) {
            String e = entry.strip();
            if ( e.equalsIgnoreCase( name ) || e.equalsIgnoreCase( uuid ) ) return true;
        }

        return false;
    }


    //==========================================================================
    // allowPlayer / denyPlayer
    //==========================================================================

    public void allowPlayer( String playerName ) {

        List< String > list = new ArrayList<>( getConfig().getStringList( "allowed-players" ) );
        for ( String entry : list ) {
            if ( entry.strip().equalsIgnoreCase( playerName ) ) return;
        }
        list.add( playerName );
        getConfig().set( "allowed-players", list );
        saveConfig();

        reloadConfigFromDisk();
    }


    public void denyPlayer( String playerName ) {

        List< String > list = new ArrayList<>( getConfig().getStringList( "allowed-players" ) );
        list.removeIf( entry -> entry.strip().equalsIgnoreCase( playerName ) );
        getConfig().set( "allowed-players", list );
        saveConfig();

        java.util.UUID uuid = Bukkit.getPlayerUniqueId( playerName );
        if ( uuid != null ) {
            List< Zone > owned = zoneStore.all().stream()
                .filter( z -> z.owner().equals( uuid ) )
                .toList();
            for ( Zone zone : owned ) {
                zoneStore.delete( zone.id() );
                getLogger().info( "Disabled zone " + zone.id()
                    + " (owner " + playerName + " denied)." );
            }
        }

        reloadConfigFromDisk();
    }


    //= Accessors ==============================================================

    public Config              safeBaseConfig() { return config; }
    public ZoneStore           zoneStore()      { return zoneStore; }
    public SafeLocationTracker tracker()        { return tracker; }
    public EnforcementListener enforcement()    { return enforcement; }
}
