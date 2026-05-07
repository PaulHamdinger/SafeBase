//==============================================================================
// BookResyncListener.java
//==============================================================================
//
// Keeps lectern books in sync with YAML data and validates zone structures
// on chunk load. Two responsibilities :
//
//   1. Resync the book content from YAML whenever the lectern's chunk loads.
//   2. Validate the structure (lectern + 3x3 platform) once ALL chunks
//      covering the platform are loaded. If the structure is gone, delete
//      the ghost zone.
//
// Because the 3x3 platform can straddle chunk boundaries, we listen for
// every chunk that *could* contain part of a zone's platform. On each load,
// we check whether all necessary chunks are now available. Only then do we
// access platform blocks.
//
//==============================================================================

package com.nethercore.safebase;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;


public final class BookResyncListener implements Listener {

    private final SafeBasePlugin plugin;


    public BookResyncListener( SafeBasePlugin plugin ) {
        this.plugin = plugin;
    }


    //==========================================================================
    // resyncLoadedChunks
    //==========================================================================
    //
    // Called on plugin enable and /safebase refresh. Processes any zone whose
    // anchor chunk is already loaded (resync book + validate if platform
    // chunks are also loaded).
    //
    //==========================================================================

    public void resyncLoadedChunks() {

        for ( Zone zone : plugin.zoneStore().all() ) {
            World world = Bukkit.getWorld( zone.world() );
            if ( world == null ) continue;

            if ( world.isChunkLoaded( zone.anchorX() >> 4, zone.anchorZ() >> 4 ) ) {
                resyncBook( zone );
            }

            if ( allPlatformChunksLoaded( zone, world ) ) {
                validateStructure( zone, world );
            }
        }
    }


    //==========================================================================
    // ChunkLoadEvent
    //==========================================================================
    //
    // For each loaded chunk, check all zones whose platform could touch it.
    // The platform spans anchorX ± 1, anchorZ ± 1 at anchorY - 1. A zone
    // is relevant if any of those 9 positions falls within this chunk.
    //
    //==========================================================================

    @EventHandler( priority = EventPriority.MONITOR )
    public void onChunkLoad( ChunkLoadEvent event ) {

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        String worldName = event.getWorld().getName();
        World world = event.getWorld();

        for ( Zone zone : plugin.zoneStore().all() ) {
            if ( !zone.world().equals( worldName ) ) continue;

            // Does this chunk contain the anchor or any platform block?
            if ( !zoneTouchesChunk( zone, chunkX, chunkZ ) ) continue;

            // Resync book if this is the anchor chunk.
            if ( ( zone.anchorX() >> 4 ) == chunkX && ( zone.anchorZ() >> 4 ) == chunkZ ) {
                resyncBook( zone );
            }

            // Validate structure only if all platform chunks are now loaded.
            if ( allPlatformChunksLoaded( zone, world ) ) {
                validateStructure( zone, world );
            }
        }
    }


    //==========================================================================
    // zoneTouchesChunk
    //==========================================================================
    //
    // Returns true if the given chunk contains the zone's anchor or any of
    // its platform blocks (anchorX ± 1, anchorZ ± 1).
    //
    //==========================================================================

    private boolean zoneTouchesChunk( Zone zone, int chunkX, int chunkZ ) {

        int minX = ( zone.anchorX() - 1 ) >> 4;
        int maxX = ( zone.anchorX() + 1 ) >> 4;
        int minZ = ( zone.anchorZ() - 1 ) >> 4;
        int maxZ = ( zone.anchorZ() + 1 ) >> 4;

        return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
    }


    //==========================================================================
    // allPlatformChunksLoaded
    //==========================================================================
    //
    // Determines the set of distinct chunks that the 3x3 platform occupies
    // and checks that every one is currently loaded.
    //
    //==========================================================================

    private boolean allPlatformChunksLoaded( Zone zone, World world ) {

        int minCX = ( zone.anchorX() - 1 ) >> 4;
        int maxCX = ( zone.anchorX() + 1 ) >> 4;
        int minCZ = ( zone.anchorZ() - 1 ) >> 4;
        int maxCZ = ( zone.anchorZ() + 1 ) >> 4;

        for ( int cx = minCX; cx <= maxCX; cx++ ) {
            for ( int cz = minCZ; cz <= maxCZ; cz++ ) {
                if ( !world.isChunkLoaded( cx, cz ) ) return false;
            }
        }

        return true;
    }


    //==========================================================================
    // validateStructure
    //==========================================================================
    //
    // Checks that the in-world structure backing a zone is still intact :
    //   - Lectern exists at anchor position
    //   - Book is on the lectern
    //   - Valid 3x3 platform below the lectern
    //
    // If any check fails, the zone is a ghost — delete it.
    //
    //==========================================================================

    private void validateStructure( Zone zone, World world ) {

        Block block = world.getBlockAt( zone.anchorX(), zone.anchorY(), zone.anchorZ() );

        // Lectern must exist.
        if ( block.getType() != Material.LECTERN ) {
            deleteGhostZone( zone, "lectern missing" );
            return;
        }

        // Book must be on the lectern.
        if ( block.getState() instanceof Lectern lectern ) {
            ItemStack bookItem = lectern.getSnapshotInventory().getItem( 0 );
            if ( bookItem == null ) {
                deleteGhostZone( zone, "no book on lectern" );
                return;
            }
        } else {
            deleteGhostZone( zone, "block is not a lectern state" );
            return;
        }

        // Platform must be valid.
        LecternListener.CountResult result = LecternListener.countPlatformBlocks( block );
        if ( result == null ) {
            deleteGhostZone( zone, "platform invalid" );
            return;
        }

        // Check halfWidth matches platform block count. If not, resize with
        // full validation (world-spawn, overlap, enforcement re-scan). The
        // zone is disabled if validation fails.
        if ( result.halfWidth() != zone.halfWidth() ) {
            plugin.tryResizeZone( zone, result.halfWidth(), world );
        }
    }


    //==========================================================================
    // deleteGhostZone
    //==========================================================================

    private void deleteGhostZone( Zone zone, String reason ) {

        plugin.zoneStore().delete( zone.id() );
        plugin.getLogger().warning( "Ghost zone " + zone.id() + " deleted (" + reason
            + "). Anchor: " + zone.world() + " "
            + zone.anchorX() + "," + zone.anchorY() + "," + zone.anchorZ() );
    }


    //==========================================================================
    // resyncBook
    //==========================================================================

    private void resyncBook( Zone zone ) {

        World world = Bukkit.getWorld( zone.world() );
        if ( world == null ) return;

        Block block = world.getBlockAt( zone.anchorX(), zone.anchorY(), zone.anchorZ() );
        if ( block.getType() != Material.LECTERN ) return;
        if ( !( block.getState() instanceof Lectern lectern ) ) return;

        ItemStack bookItem = lectern.getSnapshotInventory().getItem( 0 );
        if ( bookItem == null ) return;
        if ( bookItem.getType() != Material.WRITABLE_BOOK ) return;
        if ( !( bookItem.getItemMeta() instanceof BookMeta bookMeta ) ) return;

        BookMeta newMeta = bookMeta.clone();
        newMeta.pages( BookParser.generate( zone ) );
        bookItem.setItemMeta( newMeta );

        lectern.getSnapshotInventory().setItem( 0, bookItem );
        lectern.update( true, true );
    }
}
