//==============================================================================
// ZoneMember.java
//==============================================================================
//
// One entry on a zone's allow or deny list. A member is identified by UUID,
// name, or both. Both fields are optional individually, but at least one must
// be present.
//
//==============================================================================

package com.nethercore.safebase;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;


public final class ZoneMember {

    private final @Nullable UUID   uuid;
    private final @Nullable String name;


    //==========================================================================
    // Construction
    //==========================================================================

    public ZoneMember( @Nullable UUID uuid, @Nullable String name ) {

        if ( uuid == null && name == null ) {
            throw new IllegalArgumentException( "ZoneMember must have at least a UUID or a name." );
        }
        this.uuid = uuid;
        this.name = name;
    }


    //==========================================================================
    // matches
    //==========================================================================
    //
    // Returns true if this member entry matches a given player. A match occurs
    // when the entry's UUID equals the player's UUID, OR the entry's name
    // equals the player's name (case-insensitive). Independent checks.
    //
    //==========================================================================

    public boolean matches( UUID playerUuid, String playerName ) {

        if ( uuid != null && uuid.equals( playerUuid ) ) return true;
        if ( name != null && name.equalsIgnoreCase( playerName ) ) return true;
        return false;
    }


    //==========================================================================
    // Accessors
    //==========================================================================

    public @Nullable UUID   uuid() { return uuid; }
    public @Nullable String name() { return name; }


    //==========================================================================
    // equals / hashCode / toString
    //==========================================================================

    @Override
    public boolean equals( Object o ) {

        if ( this == o ) return true;
        if ( !( o instanceof ZoneMember other ) ) return false;
        return Objects.equals( uuid, other.uuid ) && Objects.equals( name, other.name );
    }

    @Override
    public int hashCode() { return Objects.hash( uuid, name ); }

    @Override
    public String toString() {

        if ( uuid != null && name != null ) return name + " (" + uuid + ")";
        if ( uuid != null ) return uuid.toString();
        return name;
    }
}
