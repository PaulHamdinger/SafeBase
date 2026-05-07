//==============================================================================
// Zone.java
//==============================================================================
//
// Immutable snapshot of one SafeBase zone. Built from YAML on disk (or from
// a freshly-parsed book) and held in the ZoneStore's in-memory map.
//
//==============================================================================

package com.nethercore.safebase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;


public final class Zone {

    //==========================================================================
    // Mode enum
    //==========================================================================

    public enum Mode { WHITELIST, BLACKLIST }


    //==========================================================================
    // Fields
    //==========================================================================

    private final String       id;
    private final String       world;
    private final int          anchorX;
    private final int          anchorY;
    private final int          anchorZ;
    private final UUID         owner;
    private final Mode         mode;
    private final int          halfWidth;
    private final List< ZoneMember > allow;
    private final List< ZoneMember > deny;
    private final @Nullable String ownerName;  // last-known owner name
    private final Instant      createdAt;
    private final Instant      updatedAt;


    //==========================================================================
    // Construction
    //==========================================================================

    public Zone(
        String id,
        String world,
        int anchorX, int anchorY, int anchorZ,
        UUID owner,
        Mode mode,
        int halfWidth,
        List< ZoneMember > allow,
        List< ZoneMember > deny,
        @Nullable String ownerName,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id             = id;
        this.world          = world;
        this.anchorX        = anchorX;
        this.anchorY        = anchorY;
        this.anchorZ        = anchorZ;
        this.owner          = owner;
        this.mode           = mode;
        this.halfWidth      = halfWidth;
        this.allow          = List.copyOf( allow );
        this.deny           = List.copyOf( deny );
        this.ownerName      = ownerName;
        this.createdAt      = createdAt;
        this.updatedAt      = updatedAt;
    }


    //==========================================================================
    // Spatial queries
    //==========================================================================
    //
    // "Contains" checks are axis-aligned box tests on X and Z only. The zone
    // extends full vertical height. containsSafe uses the zone's own halfWidth.
    // containsWarning takes an explicit outer radius (zone halfWidth + moat).
    //
    //==========================================================================

    public boolean containsSafe( int x, int z ) {

        return Math.abs( x - anchorX ) <= halfWidth
            && Math.abs( z - anchorZ ) <= halfWidth;
    }

    public boolean containsWarning( int x, int z, int warningHalfWidth ) {

        return Math.abs( x - anchorX ) <= warningHalfWidth
            && Math.abs( z - anchorZ ) <= warningHalfWidth;
    }


    //==========================================================================
    // isDenied
    //==========================================================================
    //
    // Returns true if the given player is denied from this zone. The owner is
    // implicitly allowed regardless of list contents. Mode determines which
    // list is checked :
    //
    //   WHITELIST : denied unless on the allow list.
    //   BLACKLIST : denied if on the deny list.
    //
    //==========================================================================

    public boolean isDenied( UUID playerUuid, String playerName ) {

        if ( playerUuid.equals( owner ) ) return false;

        return switch ( mode ) {
            case WHITELIST -> !anyMatch( allow, playerUuid, playerName );
            case BLACKLIST -> anyMatch( deny, playerUuid, playerName );
        };
    }


    //==========================================================================
    // overlaps
    //==========================================================================
    //
    // Two safe zones overlap when their boxes touch or intersect. With
    // potentially different half-widths, the threshold is R1 + R2.
    //
    //==========================================================================

    public boolean overlaps( String otherWorld, int otherX, int otherZ, int otherHalfWidth ) {

        if ( !world.equals( otherWorld ) ) return false;
        int threshold = this.halfWidth + otherHalfWidth;
        return Math.abs( otherX - anchorX ) < threshold
            && Math.abs( otherZ - anchorZ ) < threshold;
    }


    //==========================================================================
    // containsWorldSpawn
    //==========================================================================

    public boolean containsWorldSpawn( int spawnX, int spawnZ ) {

        return Math.abs( spawnX - anchorX ) <= halfWidth
            && Math.abs( spawnZ - anchorZ ) <= halfWidth;
    }


    //==========================================================================
    // Accessors
    //==========================================================================

    public String       id()             { return id; }
    public String       world()          { return world; }
    public int          anchorX()        { return anchorX; }
    public int          anchorY()        { return anchorY; }
    public int          anchorZ()        { return anchorZ; }
    public UUID         owner()          { return owner; }
    public Mode         mode()           { return mode; }
    public int          halfWidth()      { return halfWidth; }
    public List< ZoneMember > allow()    { return allow; }
    public List< ZoneMember > deny()     { return deny; }
    public @Nullable String ownerName()  { return ownerName; }
    public Instant      createdAt()      { return createdAt; }
    public Instant      updatedAt()      { return updatedAt; }


    //==========================================================================
    // Helpers
    //==========================================================================

    private static boolean anyMatch( List< ZoneMember > list, UUID uuid, String name ) {

        for ( ZoneMember m : list ) {
            if ( m.matches( uuid, name ) ) return true;
        }
        return false;
    }
}
