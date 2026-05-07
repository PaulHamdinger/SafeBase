//==============================================================================
// LecternListener.java
//==============================================================================
//
// Event handlers for book placement on lecterns, book removal, lectern
// destruction, and platform destruction. This is the lifecycle glue between
// the physical structure (lectern + 3x3 platform) and the ZoneStore.
//
// A valid SafeBase structure is :
//   - A lectern block
//   - Sitting on top of a 3x3 platform of uniform material :
//       * White wool → WHITELIST mode
//       * Black wool → BLACKLIST mode
//       * Any other material → invalid, zone not created
//   - With a book-and-quill placed on it
//
//==============================================================================

package com.nethercore.safebase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;


public final class LecternListener implements Listener {

    private final SafeBasePlugin plugin;


    public LecternListener( SafeBasePlugin plugin ) {
        this.plugin = plugin;
    }


    //==========================================================================
    // Platform detection
    //==========================================================================
    //
    // Checks the 3x3 layer directly below a lectern. Returns the zone mode
    // if the platform is valid, or null if not a SafeBase structure.
    //
    //==========================================================================

    record CountResult( Zone.Mode mode, int count ) {
        int halfWidth() { return 64 + ( count - 1 ) * 32; }
    }

    static CountResult countPlatformBlocks( Block lecternBlock ) {

        int bx = lecternBlock.getX();
        int by = lecternBlock.getY() - 1;
        int bz = lecternBlock.getZ();
        World world = lecternBlock.getWorld();

        Block center = world.getBlockAt( bx, by, bz );
        Material centerType = center.getType();

        Zone.Mode mode;
        if ( centerType == Material.WHITE_WOOL ) {
            mode = Zone.Mode.WHITELIST;
        } else if ( centerType == Material.BLACK_WOOL ) {
            mode = Zone.Mode.BLACKLIST;
        } else {
            return null;
        }

        int count = 0;
        for ( int dx = -1; dx <= 1; dx++ ) {
            for ( int dz = -1; dz <= 1; dz++ ) {
                if ( world.getBlockAt( bx + dx, by, bz + dz ).getType() == centerType ) {
                    count++;
                }
            }
        }

        return new CountResult( mode, count );
    }


    //==========================================================================
    // isPlatformBlock
    //==========================================================================
    //
    // Returns true if the given block is part of the 3x3 platform beneath a
    // zone's anchor (lectern).
    //
    //==========================================================================

    private boolean isPlatformBlock( Block block, Zone zone ) {

        if ( !block.getWorld().getName().equals( zone.world() ) ) return false;
        if ( block.getY() != zone.anchorY() - 1 ) return false;
        return Math.abs( block.getX() - zone.anchorX() ) <= 1
            && Math.abs( block.getZ() - zone.anchorZ() ) <= 1;
    }


    //==========================================================================
    // Book placed on lectern
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onPlayerInteract( PlayerInteractEvent event ) {

        if ( event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ) return;

        Block block = event.getClickedBlock();
        if ( block == null ) return;
        if ( block.getType() != Material.LECTERN ) return;

        Player player = event.getPlayer();
        ItemStack hand = event.getItem();

        if ( hand == null ) return;
        if ( hand.getType() != Material.WRITABLE_BOOK ) return;

        if ( block.getState() instanceof Lectern lecternState ) {
            if ( lecternState.getInventory().getItem( 0 ) != null ) return;
        }

        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTaskLater( plugin, () -> {
            handleBookPlaced( loc, player );
        }, 1L );
    }


    //==========================================================================
    // handleBookPlaced
    //==========================================================================

    private void handleBookPlaced( Location loc, Player player ) {

        Block block = loc.getBlock();
        if ( block.getType() != Material.LECTERN ) return;
        if ( !( block.getState() instanceof Lectern lectern ) ) return;

        ItemStack bookItem = lectern.getInventory().getItem( 0 );
        if ( bookItem == null ) return;
        if ( !( bookItem.getItemMeta() instanceof BookMeta bookMeta ) ) return;

        //----------------------------------------------------------------------
        // Platform check : determines if this is a SafeBase structure.
        //----------------------------------------------------------------------

        CountResult platform = countPlatformBlocks( block );
        if ( platform == null ) return;

        //----------------------------------------------------------------------
        // Parse the book.
        //----------------------------------------------------------------------

        List< Component > pages = bookMeta.pages();
        BookParser.ParseResult result = BookParser.parse( pages );

        switch ( result ) {

            case BookParser.ParseResult.Disabled() :
                return;

            case BookParser.ParseResult.Active active :
                activateZone( loc, player, active, platform );
                return;
        }
    }


    //==========================================================================
    // activateZone
    //==========================================================================

    private void activateZone(
        Location loc,
        Player player,
        BookParser.ParseResult.Active parsed,
        CountResult platform
    ) {

        //----------------------------------------------------------------------
        // Permission check.
        //----------------------------------------------------------------------

        if ( !plugin.isAllowedToCreate( player ) ) {
            rejectBook( loc, player,
                "You do not have permission to create SafeBases." );
            return;
        }

        //----------------------------------------------------------------------
        // Zone half-width from platform block count.
        //----------------------------------------------------------------------

        int halfWidth = platform.halfWidth();
        Zone.Mode mode = platform.mode();

        //----------------------------------------------------------------------
        // Overlap check.
        //----------------------------------------------------------------------

        String worldName = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        Zone overlap = plugin.zoneStore().findOverlap( worldName, x, z, halfWidth );
        if ( overlap != null ) {
            rejectBook( loc, player,
                "Overlaps an existing SafeBase (id: " + overlap.id() + ")." );
            return;
        }

        //----------------------------------------------------------------------
        // World-spawn check.
        //----------------------------------------------------------------------

        World world = loc.getWorld();
        Location spawn = world.getSpawnLocation();

        if ( Math.abs( spawn.getBlockX() - x ) <= halfWidth
            && Math.abs( spawn.getBlockZ() - z ) <= halfWidth ) {
            rejectBook( loc, player,
                "SafeBase would contain this world's spawn point." );
            return;
        }

        //----------------------------------------------------------------------
        // Resolve members and remove owner from deny list if present.
        //----------------------------------------------------------------------

        UUID ownerUuid = player.getUniqueId();
        String ownerName = player.getName();
        List< ZoneMember > resolved = resolveMembers( parsed.members() );

        List< ZoneMember > allow;
        List< ZoneMember > deny;

        if ( mode == Zone.Mode.WHITELIST ) {
            allow = resolved;
            deny = List.of();
        } else {
            allow = List.of();
            deny = resolved.stream()
                .filter( m -> !m.matches( ownerUuid, ownerName ) )
                .toList();
        }

        //----------------------------------------------------------------------
        // Build the zone.
        //----------------------------------------------------------------------

        String zoneId = generateZoneId();
        Instant now = Instant.now();

        Zone zone = new Zone(
            zoneId, worldName,
            x, loc.getBlockY(), loc.getBlockZ(),
            ownerUuid, mode, halfWidth,
            allow, deny,
            ownerName, now, now
        );

        //----------------------------------------------------------------------
        // Persist and register.
        //----------------------------------------------------------------------

        plugin.zoneStore().save( zone );

        //----------------------------------------------------------------------
        // Canonicalize the book.
        //----------------------------------------------------------------------

        canonicalizeBook( loc, zone );

        //----------------------------------------------------------------------
        // Success feedback.
        //----------------------------------------------------------------------

        String modeLabel = mode == Zone.Mode.WHITELIST ? "Allowlist" : "Denylist";
        player.showTitle( Title.title(
            Component.text( "SafeBase Activated!", NamedTextColor.GOLD ).decorate( TextDecoration.BOLD ),
            Component.text( modeLabel + " - Zone " + zoneId, NamedTextColor.GRAY )
        ) );
        player.playSound( player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f );

        plugin.getLogger().info( "Zone " + zoneId + " created by " + ownerName
            + " at " + worldName + " " + x + "," + loc.getBlockY() + "," + loc.getBlockZ()
            + " mode=" + mode + " halfWidth=" + halfWidth );

        //----------------------------------------------------------------------
        // Initial-enforcement scan : eject/warn online players.
        //----------------------------------------------------------------------

        plugin.enforcement().runInitialScan();
    }


    //==========================================================================
    // rejectBook — shows failure feedback without modifying the book
    //==========================================================================

    private void rejectBook(
        Location loc,
        Player player,
        String reason
    ) {

        player.showTitle( Title.title(
            Component.text( "SafeBase Failed", NamedTextColor.RED ).decorate( TextDecoration.BOLD ),
            Component.text( reason.length() > 50 ? reason.substring( 0, 50 ) + "..." : reason, NamedTextColor.GRAY )
        ) );
        player.playSound( player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f );
    }


    //==========================================================================
    // canonicalizeBook
    //==========================================================================

    private void canonicalizeBook(
        Location loc,
        Zone zone
    ) {

        Block block = loc.getBlock();
        if ( block.getType() != Material.LECTERN ) return;
        if ( !( block.getState() instanceof Lectern lectern ) ) return;

        ItemStack bookItem = lectern.getSnapshotInventory().getItem( 0 );
        if ( bookItem == null ) return;
        // Only canonicalize unsigned books. Signed books retain their content.
        if ( bookItem.getType() != Material.WRITABLE_BOOK ) return;
        if ( !( bookItem.getItemMeta() instanceof BookMeta bookMeta ) ) return;

        BookMeta newMeta = bookMeta.clone();
        newMeta.pages( BookParser.generate( zone ) );
        bookItem.setItemMeta( newMeta );

        lectern.getSnapshotInventory().setItem( 0, bookItem );
        lectern.update( true, true );
    }


    //==========================================================================
    // Book taken from lectern
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onBookTaken( PlayerTakeLecternBookEvent event ) {

        deleteZoneAtLectern( event.getLectern().getLocation() );
    }


    //==========================================================================
    // Lectern broken
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onBlockBreak( BlockBreakEvent event ) {

        Block block = event.getBlock();

        if ( block.getType() == Material.LECTERN ) {
            deleteZoneAtLectern( block.getLocation() );
            return;
        }

        // Check if a platform block was broken.
        handlePlatformBlockChange( block );
    }


    //==========================================================================
    // Lectern or platform exploded (block or entity source)
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onBlockExplode( BlockExplodeEvent event ) {

        for ( Block block : event.blockList() ) {
            if ( block.getType() == Material.LECTERN ) {
                deleteZoneAtLectern( block.getLocation() );
            } else {
                handlePlatformBlockChange( block );
            }
        }
    }

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onEntityExplode( EntityExplodeEvent event ) {

        for ( Block block : event.blockList() ) {
            if ( block.getType() == Material.LECTERN ) {
                deleteZoneAtLectern( block.getLocation() );
            } else {
                handlePlatformBlockChange( block );
            }
        }
    }


    //==========================================================================
    // Lectern or platform burned
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onBlockBurn( BlockBurnEvent event ) {

        Block block = event.getBlock();

        if ( block.getType() == Material.LECTERN ) {
            deleteZoneAtLectern( block.getLocation() );
            return;
        }

        handlePlatformBlockChange( block );
    }


    //==========================================================================
    // Block placed in platform area — recount if outer block
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onBlockPlace( BlockPlaceEvent event ) {

        Block block = event.getBlock();

        for ( Zone zone : plugin.zoneStore().all() ) {
            if ( isPlatformBlock( block, zone ) ) {
                boolean isCenter = block.getX() == zone.anchorX()
                    && block.getZ() == zone.anchorZ();
                if ( !isCenter ) {
                    schedulePlatformRecount( zone );
                }
                return;
            }
        }
    }


    //==========================================================================
    // Entity changes block (enderman pickup, wither aura)
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onEntityChangeBlock( EntityChangeBlockEvent event ) {

        Block block = event.getBlock();

        if ( block.getType() == Material.LECTERN ) {
            deleteZoneAtLectern( block.getLocation() );
            return;
        }

        handlePlatformBlockChange( block );
    }


    //==========================================================================
    // Piston extends - wool pushed out of position
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onPistonExtend( BlockPistonExtendEvent event ) {

        for ( Block block : event.getBlocks() ) {
            handlePlatformBlockChange( block );
            deleteZoneIfLecternMoved( block );
        }
    }


    //==========================================================================
    // Piston retracts - blocks pulled out of position
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR, ignoreCancelled = true )
    public void onPistonRetract( BlockPistonRetractEvent event ) {

        for ( Block block : event.getBlocks() ) {
            handlePlatformBlockChange( block );
            deleteZoneIfLecternMoved( block );
        }
    }


    //==========================================================================
    // deleteZoneAtLectern
    //==========================================================================

    private void deleteZoneAtLectern( Location loc ) {

        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        Zone zone = plugin.zoneStore().findByAnchor( world, x, y, z );
        if ( zone == null ) return;

        plugin.zoneStore().delete( zone.id() );
        plugin.getLogger().info( "Zone " + zone.id() + " deleted (lectern at "
            + world + " " + x + "," + y + "," + z + " removed/destroyed)." );
    }


    //==========================================================================
    // handlePlatformBlockChange
    //==========================================================================
    //
    // Responds to changes in a zone's 3x3 platform. Center block (directly
    // under the lectern) → deletes the zone. Outer blocks → schedules a
    // 1-tick delayed recount + resize.
    //
    //==========================================================================

    private void handlePlatformBlockChange( Block block ) {

        for ( Zone zone : plugin.zoneStore().all() ) {
            if ( !isPlatformBlock( block, zone ) ) continue;

            boolean isCenter = block.getX() == zone.anchorX()
                && block.getZ() == zone.anchorZ();

            if ( isCenter ) {
                plugin.zoneStore().delete( zone.id() );
                plugin.notifyOwner( zone, "Center platform block destroyed." );
                plugin.getLogger().info( "Zone " + zone.id()
                    + " disabled (center platform block broken at "
                    + block.getX() + "," + block.getY() + "," + block.getZ() + ")." );
            } else {
                schedulePlatformRecount( zone );
            }
            return;
        }
    }


    //==========================================================================
    // schedulePlatformRecount + recountPlatform
    //==========================================================================
    //
    // Outer-block changes trigger a 1-tick delayed recount of matching blocks
    // in the 3x3 platform. If the center block is gone, the zone is disabled.
    // Otherwise the half-width is recomputed and the zone YAML updated.
    //
    //==========================================================================

    private void schedulePlatformRecount( Zone zone ) {

        plugin.getServer().getScheduler().runTaskLater( plugin, () -> {
            recountPlatform( zone );
        }, 1L );
    }


    private void recountPlatform( Zone zone ) {

        // Verify zone still exists (hasn't been deleted since recount scheduled).
        if ( plugin.zoneStore().byId( zone.id() ) == null ) return;

        World world = Bukkit.getWorld( zone.world() );
        if ( world == null ) return;

        Block center = world.getBlockAt( zone.anchorX(), zone.anchorY() - 1, zone.anchorZ() );
        Material centerType = center.getType();

        // If center block is no longer white/black wool → disable zone.
        if ( centerType != Material.WHITE_WOOL && centerType != Material.BLACK_WOOL ) {
            plugin.zoneStore().delete( zone.id() );
            plugin.notifyOwner( zone, "Center platform block missing." );
            plugin.getLogger().info( "Zone " + zone.id()
                + " disabled (center platform block missing during recount)." );
            return;
        }

        // Count matching blocks.
        int count = 0;
        for ( int dx = -1; dx <= 1; dx++ ) {
            for ( int dz = -1; dz <= 1; dz++ ) {
                if ( world.getBlockAt( zone.anchorX() + dx, zone.anchorY() - 1, zone.anchorZ() + dz ).getType() == centerType ) {
                    count++;
                }
            }
        }

        // Only update if size actually changed.
        int newHalfWidth = 64 + ( count - 1 ) * 32;
        if ( newHalfWidth == zone.halfWidth() ) return;

        // Delegate to centralized validation which handles world-spawn check,
        // overlap check, save, and enforcement re-scan. The zone is disabled
        // if validation fails.
        plugin.tryResizeZone( zone, newHalfWidth, world );
    }


    //==========================================================================
    // deleteZoneIfLecternMoved
    //==========================================================================
    //
    // Checks if the given block is at any zone's anchor position (the lectern).
    // If so, the zone is deleted — the structure is broken.
    //
    //==========================================================================

    private void deleteZoneIfLecternMoved( Block block ) {

        Zone zone = plugin.zoneStore().findByAnchor(
            block.getWorld().getName(), block.getX(), block.getY(), block.getZ()
        );
        if ( zone == null ) return;

        plugin.zoneStore().delete( zone.id() );
        plugin.getLogger().info( "Zone " + zone.id() + " deleted (lectern moved by piston at "
            + block.getX() + "," + block.getY() + "," + block.getZ() + ")." );
    }


    //==========================================================================
    // generateZoneId
    //==========================================================================

    private String generateZoneId() {

        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();

        for ( int attempt = 0; attempt < 100; attempt++ ) {
            char[] chars = new char[ 6 ];
            for ( int i = 0; i < 6; i++ ) {
                chars[ i ] = (char) ( 'a' + rng.nextInt( 26 ) );
            }
            String id = new String( chars );
            if ( plugin.zoneStore().byId( id ) == null ) return id;
        }

        return "z" + System.currentTimeMillis();
    }


    //==========================================================================
    // resolveMembers
    //==========================================================================

    private List< ZoneMember > resolveMembers( List< ZoneMember > raw ) {

        List< ZoneMember > resolved = new ArrayList<>( raw.size() );

        for ( ZoneMember m : raw ) {

            if ( m.uuid() != null && m.name() != null ) {
                OfflinePlayer byUuid = Bukkit.getOfflinePlayer( m.uuid() );
                String nameFromUuid = byUuid.getName();

                if ( nameFromUuid != null && nameFromUuid.equalsIgnoreCase( m.name() ) ) {
                    resolved.add( new ZoneMember( m.uuid(), nameFromUuid ) );
                } else {
                    resolved.add( new ZoneMember( m.uuid(), nameFromUuid ) );
                    resolved.add( new ZoneMember( null, m.name() ) );
                }

            } else if ( m.uuid() != null ) {
                OfflinePlayer byUuid = Bukkit.getOfflinePlayer( m.uuid() );
                String name = byUuid.getName();
                resolved.add( new ZoneMember( m.uuid(), name ) );

            } else {
                UUID cached = Bukkit.getPlayerUniqueId( m.name() );
                if ( cached != null ) {
                    resolved.add( new ZoneMember( cached, m.name() ) );
                } else {
                    resolved.add( m );
                }
            }
        }

        return resolved;
    }
}
