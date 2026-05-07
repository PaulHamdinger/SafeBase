//==============================================================================
// ZoneStore.java
//==============================================================================
//
// Persistent store for SafeBase zones. Each zone lives in its own YAML file
// under plugins/SafeBase/zones/. On load, all files are read into an
// in-memory map for O(1) lookup by zone-id. A chunk rejection index
// (world → set of chunk keys) provides a fast-path skip for enforcement :
// players far from any zone avoid the linear zone scan entirely.
//
//==============================================================================

package com.nethercore.safebase;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;


public final class ZoneStore {

    private final File   zonesDir;
    private final Logger log;

    private final Map< String, Zone > zonesById = new LinkedHashMap<>();
    private final Map< String, Set< Long > > zoneChunks = new HashMap<>();

    private int warningMoatWidth = 32; // updated from Config after load

    private Consumer< String > onDelete;


    //==========================================================================
    // Construction
    //==========================================================================

    public ZoneStore( File dataFolder, Logger log ) {

        this.zonesDir = new File( dataFolder, "zones" );
        this.log      = log;
    }


    //==========================================================================
    // loadAll
    //==========================================================================
    //
    // Reads every .yml file in the zones/ directory into memory. Called on
    // plugin enable and on /safebase refresh. Clears existing state first.
    //
    //==========================================================================

    public void loadAll() {

        zonesById.clear();

        if ( !zonesDir.isDirectory() ) {
            zonesDir.mkdirs();
            return;
        }

        File[] files = zonesDir.listFiles( ( dir, name ) -> name.endsWith( ".yml" ) );
        if ( files == null ) return;

        int loaded = 0;
        int failed = 0;

        for ( File file : files ) {
            Zone zone = loadFile( file );
            if ( zone != null ) {
                zonesById.put( zone.id(), zone );
                loaded++;
            } else {
                failed++;
            }
        }

        log.info( "Loaded " + loaded + " zone(s) from disk." + ( failed > 0 ? " " + failed + " file(s) failed." : "" ) );

        // Rebuild chunk rejection index.
        zoneChunks.clear();
        for ( Zone zone : zonesById.values() ) {
            addZoneToIndex( zone );
        }

        // Refresh last-known owner names from the server's player database.
        // Saves each updated zone so the YAML file stays in sync.
        for ( Zone zone : zonesById.values() ) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer( zone.owner() );
            String currentName = offline.getName();
            if ( currentName != null && !currentName.equals( zone.ownerName() ) ) {
                Zone updated = new Zone(
                    zone.id(), zone.world(),
                    zone.anchorX(), zone.anchorY(), zone.anchorZ(),
                    zone.owner(), zone.mode(), zone.halfWidth(),
                    zone.allow(), zone.deny(),
                    currentName,
                    zone.createdAt(), Instant.now()
                );
                zonesById.put( zone.id(), updated );
                save( updated );
            }
        }
    }


    //==========================================================================
    // save
    //==========================================================================
    //
    // Writes a Zone to its YAML file. Creates or overwrites.
    //
    //==========================================================================

    public void save( Zone zone ) {

        zonesById.put( zone.id(), zone );
        addZoneToIndex( zone );

        if ( !zonesDir.isDirectory() ) zonesDir.mkdirs();

        File file = new File( zonesDir, zone.id() + ".yml" );
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set( "schema-version", 5 );
        yaml.set( "zone-id", zone.id() );

        yaml.set( "anchor.world", zone.world() );
        yaml.set( "anchor.x", zone.anchorX() );
        yaml.set( "anchor.y", zone.anchorY() );
        yaml.set( "anchor.z", zone.anchorZ() );

        yaml.set( "owner", zone.owner().toString() );
        yaml.set( "mode", zone.mode() == Zone.Mode.WHITELIST ? "whitelist" : "blacklist" );

        yaml.set( "safe-zone-half-width", zone.halfWidth() );
        yaml.set( "last-known-owner-name", zone.ownerName() );

        yaml.set( "allow", serializeMembers( zone.allow() ) );
        yaml.set( "deny", serializeMembers( zone.deny() ) );

        yaml.set( "created-at", zone.createdAt().toString() );
        yaml.set( "updated-at", zone.updatedAt().toString() );

        try {
            yaml.save( file );
        } catch ( IOException e ) {
            log.severe( "Failed to save zone " + zone.id() + " : " + e.getMessage() );
        }
    }


    //==========================================================================
    // delete
    //==========================================================================

    public boolean delete( String zoneId ) {

        Zone removed = zonesById.remove( zoneId );
        if ( removed == null ) return false;

        removeZoneFromIndex( removed );

        if ( onDelete != null ) onDelete.accept( zoneId );

        File file = new File( zonesDir, zoneId + ".yml" );
        if ( file.exists() ) file.delete();
        return true;
    }


    //==========================================================================
    // onDelete callback
    //==========================================================================

    public void setOnDelete( Consumer< String > callback ) { this.onDelete = callback; }


    //==========================================================================
    // Chunk rejection index
    //==========================================================================
    //
    // Maintains a set of all chunks that contain any zone's warning area.
    // Fast-path guard for enforcement : if a player's chunk isn't in this
    // set, they are nowhere near any zone — skip the linear zone scan.
    //
    // ZoneStore needs to know warningMoatWidth (from Config) so it can
    // compute each zone's outer radius for the index. Set via setter before
    // loadAll().
    //
    //==========================================================================

    public void setWarningMoatWidth( int width ) { this.warningMoatWidth = width; }


    private static long chunkKey( int cx, int cz ) {
        return ( (long) cx << 32 ) | ( cz & 0xFFFFFFFFL );
    }


    private void addZoneToIndex( Zone zone ) {

        int radius = zone.halfWidth() + warningMoatWidth;
        int minCX = ( zone.anchorX() - radius ) >> 4;
        int maxCX = ( zone.anchorX() + radius ) >> 4;
        int minCZ = ( zone.anchorZ() - radius ) >> 4;
        int maxCZ = ( zone.anchorZ() + radius ) >> 4;

        Set< Long > chunks = zoneChunks.computeIfAbsent( zone.world(), k -> new HashSet<>() );

        for ( int cx = minCX; cx <= maxCX; cx++ ) {
            for ( int cz = minCZ; cz <= maxCZ; cz++ ) {
                chunks.add( chunkKey( cx, cz ) );
            }
        }
    }


    private void removeZoneFromIndex( Zone zone ) {

        int radius = zone.halfWidth() + warningMoatWidth;
        int minCX = ( zone.anchorX() - radius ) >> 4;
        int maxCX = ( zone.anchorX() + radius ) >> 4;
        int minCZ = ( zone.anchorZ() - radius ) >> 4;
        int maxCZ = ( zone.anchorZ() + radius ) >> 4;

        Set< Long > chunks = zoneChunks.get( zone.world() );
        if ( chunks == null ) return;

        for ( int cx = minCX; cx <= maxCX; cx++ ) {
            for ( int cz = minCZ; cz <= maxCZ; cz++ ) {
                chunks.remove( chunkKey( cx, cz ) );
            }
        }
    }


    /** Fast-path : returns true if any zone's warning area touches this chunk. */
    public boolean hasZoneInChunk( String world, int chunkX, int chunkZ ) {

        Set< Long > chunks = zoneChunks.get( world );
        return chunks != null && chunks.contains( chunkKey( chunkX, chunkZ ) );
    }


    //==========================================================================
    // Queries
    //==========================================================================

    public @Nullable Zone byId( String id ) { return zonesById.get( id ); }

    public Collection< Zone > all() { return Collections.unmodifiableCollection( zonesById.values() ); }

    public int count() { return zonesById.size(); }


    //==========================================================================
    // findSafeZoneAt
    //==========================================================================
    //
    // Returns the zone whose safe-zone box contains the given world + x + z,
    // or null if none. Uses each zone's own halfWidth.
    //
    //==========================================================================

    public @Nullable Zone findSafeZoneAt( String world, int x, int z ) {

        for ( Zone zone : zonesById.values() ) {
            if ( zone.world().equals( world ) && zone.containsSafe( x, z ) ) {
                return zone;
            }
        }
        return null;
    }


    //==========================================================================
    // findWarningZoneAt
    //==========================================================================
    //
    // Returns the first zone whose warning box contains the given location and
    // whose safe box does NOT. Warning radius = zone.halfWidth + moatWidth.
    // Returns null if none.
    //
    //==========================================================================

    public @Nullable Zone findWarningZoneAt( String world, int x, int z, int moatWidth ) {

        for ( Zone zone : zonesById.values() ) {
            if ( !zone.world().equals( world ) ) continue;
            int warnHalf = zone.halfWidth() + moatWidth;
            if ( zone.containsWarning( x, z, warnHalf ) && !zone.containsSafe( x, z ) ) {
                return zone;
            }
        }
        return null;
    }


    //==========================================================================
    // findOverlap
    //==========================================================================
    //
    // Returns the first existing zone whose safe box overlaps a proposed new
    // anchor at (world, x, z) with the given halfWidth. Used at book-placement
    // validation time. Overlap threshold = existing.halfWidth + proposed.halfWidth.
    //
    //==========================================================================

    public @Nullable Zone findOverlap( String world, int x, int z, int proposedHalfWidth ) {
        return findOverlap( world, x, z, proposedHalfWidth, null );
    }

    public @Nullable Zone findOverlap( String world, int x, int z, int proposedHalfWidth, @Nullable String excludeId ) {

        for ( Zone zone : zonesById.values() ) {
            if ( excludeId != null && zone.id().equals( excludeId ) ) continue;
            if ( zone.overlaps( world, x, z, proposedHalfWidth ) ) return zone;
        }
        return null;
    }


    //==========================================================================
    // findByAnchor
    //==========================================================================

    public @Nullable Zone findByAnchor( String world, int x, int y, int z ) {

        for ( Zone zone : zonesById.values() ) {
            if ( zone.world().equals( world )
                && zone.anchorX() == x
                && zone.anchorY() == y
                && zone.anchorZ() == z ) {
                return zone;
            }
        }
        return null;
    }


    //==========================================================================
    // loadFile (private)
    //==========================================================================

    private @Nullable Zone loadFile( File file ) {

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration( file );

        int schemaVersion = yaml.getInt( "schema-version", -1 );

        //----------------------------------------------------------------------
        // Zone migration : schema 1 (the only "in the wild" pre-v5 format) → 5
        //----------------------------------------------------------------------

        if ( schemaVersion == -1 || schemaVersion > 5 ) {
            log.warning( "Skipping " + file.getName() + " : unsupported schema-version " + schemaVersion );
            return null;
        }

        if ( schemaVersion < 5 ) {
            log.info( "Migrating zone " + file.getName() + " from schema-version " + schemaVersion + " to 5." );

            // warning-message was removed in v5.
            yaml.set( "warning-message", null );

            // safe-zone-half-width defaults to 128 for legacy zones.
            if ( !yaml.contains( "safe-zone-half-width" ) ) {
                yaml.set( "safe-zone-half-width", Config.DEFAULT_SAFE_ZONE_HALF_WIDTH );
            }

            // Resolve last-known-owner-name from the owner UUID.
            if ( !yaml.contains( "last-known-owner-name" ) ) {
                String ownerStr = yaml.getString( "owner" );
                if ( ownerStr != null ) {
                    try {
                        UUID ownerUuid = UUID.fromString( ownerStr );
                        OfflinePlayer offline = Bukkit.getOfflinePlayer( ownerUuid );
                        String name = offline.getName();
                        yaml.set( "last-known-owner-name", name != null ? name : ownerStr.substring( 0, 8 ) + "..." );
                    } catch ( Exception e ) {
                        yaml.set( "last-known-owner-name", ownerStr.substring( 0, 8 ) + "..." );
                    }
                }
            }

            yaml.set( "schema-version", 5 );

            try {
                yaml.save( file );
            } catch ( IOException e ) {
                log.severe( "Failed to save migrated zone " + file.getName() + " : " + e.getMessage() );
            }
        }

        String id = yaml.getString( "zone-id" );
        if ( id == null || id.isBlank() ) {
            log.warning( "Skipping " + file.getName() + " : missing zone-id." );
            return null;
        }

        //----------------------------------------------------------------------
        // Anchor
        //----------------------------------------------------------------------

        ConfigurationSection anchor = yaml.getConfigurationSection( "anchor" );
        if ( anchor == null ) {
            log.warning( "Skipping " + file.getName() + " : missing anchor section." );
            return null;
        }

        String world = anchor.getString( "world" );
        if ( world == null || world.isBlank() ) {
            log.warning( "Skipping " + file.getName() + " : missing anchor.world." );
            return null;
        }

        int ax = anchor.getInt( "x" );
        int ay = anchor.getInt( "y" );
        int az = anchor.getInt( "z" );

        //----------------------------------------------------------------------
        // Owner
        //----------------------------------------------------------------------

        String ownerStr = yaml.getString( "owner" );
        UUID owner;
        try {
            owner = UUID.fromString( ownerStr );
        } catch ( Exception e ) {
            log.warning( "Skipping " + file.getName() + " : invalid owner UUID." );
            return null;
        }

        //----------------------------------------------------------------------
        // Mode
        //----------------------------------------------------------------------

        String modeStr = yaml.getString( "mode", "whitelist" );
        Zone.Mode mode = "blacklist".equalsIgnoreCase( modeStr )
            ? Zone.Mode.BLACKLIST
            : Zone.Mode.WHITELIST;

        //----------------------------------------------------------------------
        // Members
        //----------------------------------------------------------------------

        List< ZoneMember > allow = loadMembers( yaml.getList( "allow" ) );
        List< ZoneMember > deny  = loadMembers( yaml.getList( "deny" ) );

        //----------------------------------------------------------------------
        // Zone half-width (optional — defaults to 128 for legacy zones)
        //----------------------------------------------------------------------

        int halfWidth = yaml.getInt( "safe-zone-half-width", Config.DEFAULT_SAFE_ZONE_HALF_WIDTH );

        if ( halfWidth < 1 || halfWidth > 10000 ) halfWidth = Config.DEFAULT_SAFE_ZONE_HALF_WIDTH;

        //----------------------------------------------------------------------
        // Last-known owner name
        //----------------------------------------------------------------------

        String ownerName = yaml.getString( "last-known-owner-name" );

        //----------------------------------------------------------------------
        // Timestamps
        //----------------------------------------------------------------------

        Instant createdAt = parseInstant( yaml.getString( "created-at" ), Instant.now() );
        Instant updatedAt = parseInstant( yaml.getString( "updated-at" ), createdAt );

        return new Zone( id, world, ax, ay, az, owner, mode, halfWidth,
            allow, deny, ownerName, createdAt, updatedAt );
    }


    //==========================================================================
    // loadMembers (private)
    //==========================================================================

    private List< ZoneMember > loadMembers( @Nullable List< ? > raw ) {

        if ( raw == null || raw.isEmpty() ) return List.of();

        List< ZoneMember > out = new ArrayList<>();

        for ( Object entry : raw ) {
            if ( entry instanceof Map< ?, ? > map ) {
                UUID   uuid = parseUuid( map.get( "uuid" ) );
                String name = map.get( "name" ) instanceof String s ? s : null;

                if ( uuid != null || name != null ) {
                    out.add( new ZoneMember( uuid, name ) );
                }
            }
        }

        return out;
    }


    //==========================================================================
    // serializeMembers (private)
    //==========================================================================

    private List< Map< String, String > > serializeMembers( List< ZoneMember > members ) {

        List< Map< String, String > > out = new ArrayList<>();

        for ( ZoneMember m : members ) {
            Map< String, String > entry = new LinkedHashMap<>();
            if ( m.uuid() != null ) entry.put( "uuid", m.uuid().toString() );
            if ( m.name() != null ) entry.put( "name", m.name() );
            out.add( entry );
        }

        return out;
    }


    //==========================================================================
    // Utility
    //==========================================================================

    private static @Nullable UUID parseUuid( @Nullable Object obj ) {

        if ( obj instanceof String s ) {
            try { return UUID.fromString( s ); }
            catch ( IllegalArgumentException ignored ) {}
        }
        return null;
    }

    private static Instant parseInstant( @Nullable String str, Instant fallback ) {

        if ( str == null || str.isBlank() ) return fallback;
        try { return Instant.parse( str ); }
        catch ( DateTimeParseException ignored ) { return fallback; }
    }
}
