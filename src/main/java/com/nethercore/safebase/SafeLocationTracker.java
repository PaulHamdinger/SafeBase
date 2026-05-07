//==============================================================================
// SafeLocationTracker.java
//==============================================================================
//
// Tracks the last known safe location for each player. "Safe" means outside
// every warning zone. Updated from move, teleport, join, and the background
// task. In-memory only - not persisted across restarts.
//
// Also provides the fallback chain for choosing a teleport target when a
// denied player needs to be ejected.
//
//==============================================================================

package com.nethercore.safebase;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;


public final class SafeLocationTracker {

    private final SafeBasePlugin plugin;
    private final Map< UUID, Location > lastSafe = new HashMap<>();


    public SafeLocationTracker( SafeBasePlugin plugin ) {
        this.plugin = plugin;
    }


    //==========================================================================
    // update
    //==========================================================================
    //
    // Records a location as the player's last-safe if it is outside every
    // warning zone. Called from move, teleport, join, and the background task.
    //
    //==========================================================================

    public void update( Player player, Location loc ) {

        if ( !isInsideDeniedSafeZone( loc, player.getUniqueId(), player.getName() ) ) {
            lastSafe.put( player.getUniqueId(), loc.clone() );
        }
    }


    //==========================================================================
    // resolve
    //==========================================================================
    //
    // Returns a safe teleport target for the given player, following the
    // fallback chain :
    //
    //   1. Last-known-safe location (validated: not inside a denied safe zone)
    //   2. Player's respawn point (validated same way)
    //   3. World spawn (guaranteed safe by placement constraints)
    //
    //==========================================================================

    public Location resolve( Player player ) {

        UUID uuid = player.getUniqueId();
        String name = player.getName();

        //----------------------------------------------------------------------
        // Candidate 1 : last-known-safe
        //----------------------------------------------------------------------

        Location lastSafeLoc = lastSafe.get( uuid );
        if ( lastSafeLoc != null && lastSafeLoc.getWorld() != null ) {
            if ( !isInsideDeniedSafeZone( lastSafeLoc, uuid, name ) ) {
                return lastSafeLoc.clone();
            }
        }

        //----------------------------------------------------------------------
        // Candidate 2 : player respawn point (bed / anchor)
        //----------------------------------------------------------------------

        Location respawn = player.getRespawnLocation();
        if ( respawn != null && respawn.getWorld() != null ) {
            if ( !isInsideDeniedSafeZone( respawn, uuid, name ) ) {
                return respawn.clone();
            }
        }

        //----------------------------------------------------------------------
        // Candidate 3 : world spawn (terminal fallback)
        //----------------------------------------------------------------------

        World world = player.getWorld();
        return world.getSpawnLocation().clone().add( 0.5, 0, 0.5 );
    }


    //==========================================================================
    // remove
    //==========================================================================

    public void remove( UUID uuid ) {
        lastSafe.remove( uuid );
    }


    //==========================================================================
    // clear
    //==========================================================================

    public void clear() {
        lastSafe.clear();
    }


    //==========================================================================
    // saveToFile
    //==========================================================================
    //
    // Writes the last-safe map to a YAML cache file. Called from onDisable
    // only — never during gameplay. Empty map + existing file → delete.
    //
    //==========================================================================

    public void saveToFile( File file ) {

        if ( lastSafe.isEmpty() ) {
            if ( file.exists() ) file.delete();
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();

        for ( Map.Entry< UUID, Location > entry : lastSafe.entrySet() ) {
            ConfigurationSection sec = yaml.createSection( entry.getKey().toString() );
            Location loc = entry.getValue();
            sec.set( "world", loc.getWorld().getName() );
            sec.set( "x", loc.getX() );
            sec.set( "y", loc.getY() );
            sec.set( "z", loc.getZ() );
            sec.set( "yaw", (double) loc.getYaw() );
            sec.set( "pitch", (double) loc.getPitch() );
        }

        try {
            yaml.save( file );
        } catch ( IOException e ) {
            plugin.getLogger().warning( "Failed to save last-safe cache : " + e.getMessage() );
        }
    }


    //==========================================================================
    // loadFromFile
    //==========================================================================
    //
    // Reads last-safe locations from a YAML cache file. Wrapped in a single
    // try-catch — any failure (missing file, corrupt data, IO error) is silently
    // recovered : log a warning, delete the file, start fresh. Never blocks
    // plugin startup.
    //
    //==========================================================================

    public void loadFromFile( File file ) {

        if ( !file.exists() ) return;

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration( file );

            for ( String key : yaml.getKeys( false ) ) {
                try {
                    UUID uuid = UUID.fromString( key );
                    ConfigurationSection sec = yaml.getConfigurationSection( key );
                    if ( sec == null ) continue;

                    String worldName = sec.getString( "world" );
                    if ( worldName == null ) continue;

                    World world = plugin.getServer().getWorld( worldName );
                    if ( world == null ) continue;

                    double x = sec.getDouble( "x" );
                    double y = sec.getDouble( "y" );
                    double z = sec.getDouble( "z" );
                    float yaw = (float) sec.getDouble( "yaw", 0.0 );
                    float pitch = (float) sec.getDouble( "pitch", 0.0 );

                    lastSafe.put( uuid, new Location( world, x, y, z, yaw, pitch ) );
                } catch ( IllegalArgumentException ignored ) {
                    // Malformed UUID or entry — skip.
                }
            }
        } catch ( Exception e ) {
            plugin.getLogger().warning( "Corrupt last-safe cache, resetting : " + e.getMessage() );
            file.delete();
        }
    }


    //==========================================================================
    // Helpers
    //==========================================================================

    private boolean isInsideDeniedSafeZone( Location loc, UUID uuid, String name ) {

        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        Zone zone = plugin.zoneStore().findSafeZoneAt( world, x, z );
        if ( zone == null ) return false;
        return zone.isDenied( uuid, name );
    }
}
