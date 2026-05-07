//==============================================================================
// SafeBaseCommand.java
//==============================================================================
//
// /safebase command tree built with Brigadier literal nodes. Registered via
// LifecycleEvents.COMMANDS in SafeBasePlugin#onEnable. Each subcommand is a
// literal node so the client knows all valid options and shows them on Tab.
//
//==============================================================================

package com.nethercore.safebase;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;


public final class SafeBaseCommand {

    private final SafeBasePlugin plugin;


    public SafeBaseCommand( SafeBasePlugin plugin ) {
        this.plugin = plugin;
    }


    //==========================================================================
    // buildCommandTree
    //==========================================================================

    public LiteralCommandNode< CommandSourceStack > buildCommandTree() {

        return Commands.literal( "safebase" )
            .requires( source -> source.getSender().isOp() )
            .executes( ctx -> { sendHelp( ctx.getSource().getSender() ); return Command.SINGLE_SUCCESS; } )
            .then( Commands.literal( "help" )
                .executes( ctx -> { sendHelp( ctx.getSource().getSender() ); return Command.SINGLE_SUCCESS; } )
            )
            .then( Commands.literal( "list" )
                .executes( ctx -> { executeList( ctx.getSource().getSender() ); return Command.SINGLE_SUCCESS; } )
            )
            .then( Commands.literal( "info" )
                .then( Commands.argument( "id", StringArgumentType.word() )
                    .suggests( ( ctx, builder ) -> {
                        String partial = builder.getRemaining().toLowerCase( Locale.ROOT );
                        for ( Zone zone : plugin.zoneStore().all() ) {
                            if ( zone.id().startsWith( partial ) ) builder.suggest( zone.id() );
                        }
                        return builder.buildFuture();
                    })
                    .executes( ctx -> {
                        executeInfo( ctx.getSource().getSender(), StringArgumentType.getString( ctx, "id" ) );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then( Commands.literal( "disable" )
                .then( Commands.argument( "id", StringArgumentType.word() )
                    .suggests( ( ctx, builder ) -> {
                        String partial = builder.getRemaining().toLowerCase( Locale.ROOT );
                        for ( Zone zone : plugin.zoneStore().all() ) {
                            if ( zone.id().startsWith( partial ) ) builder.suggest( zone.id() );
                        }
                        return builder.buildFuture();
                    })
                    .executes( ctx -> {
                        executeDisable( ctx.getSource().getSender(), StringArgumentType.getString( ctx, "id" ) );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then( Commands.literal( "allow" )
                .then( Commands.argument( "player", StringArgumentType.word() )
                    .suggests( ( ctx, builder ) -> {
                        String partial = builder.getRemaining().toLowerCase( Locale.ROOT );
                        for ( org.bukkit.entity.Player p : Bukkit.getOnlinePlayers() ) {
                            if ( p.getName().toLowerCase( Locale.ROOT ).startsWith( partial ) ) {
                                builder.suggest( p.getName() );
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes( ctx -> {
                        executeAllow( ctx.getSource().getSender(), StringArgumentType.getString( ctx, "player" ) );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then( Commands.literal( "deny" )
                .then( Commands.argument( "player", StringArgumentType.word() )
                    .suggests( ( ctx, builder ) -> {
                        String partial = builder.getRemaining().toLowerCase( Locale.ROOT );
                        for ( org.bukkit.entity.Player p : Bukkit.getOnlinePlayers() ) {
                            if ( p.getName().toLowerCase( Locale.ROOT ).startsWith( partial ) ) {
                                builder.suggest( p.getName() );
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes( ctx -> {
                        executeDeny( ctx.getSource().getSender(), StringArgumentType.getString( ctx, "player" ) );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then( Commands.literal( "refresh" )
                .executes( ctx -> {
                    plugin.reloadConfigFromDisk();
                    ctx.getSource().getSender().sendMessage(
                        Component.text( "SafeBase reloaded. " + plugin.zoneStore().count() + " zone(s) loaded.", NamedTextColor.GREEN )
                    );
                    return Command.SINGLE_SUCCESS;
                })
            )
            .build();
    }


    //==========================================================================
    // executeList
    //==========================================================================

    private void executeList( CommandSender sender ) {

        Collection< Zone > zones = plugin.zoneStore().all();

        if ( zones.isEmpty() ) {
            sender.sendMessage( Component.text( "No zones.", NamedTextColor.GRAY ) );
            return;
        }

        sender.sendMessage(
            Component.text( zones.size() + " zone(s) :", NamedTextColor.GOLD ).decorate( TextDecoration.BOLD )
        );

        for ( Zone zone : zones ) {
            int members = zone.allow().size() + zone.deny().size();
            String ownerName = resolvePlayerName( zone.owner() );

            sender.sendMessage(
                Component.text()
                    .append( Component.text( "  " + zone.id(), NamedTextColor.AQUA ) )
                    .append( Component.text( "  " + zone.world() + " @ " + zone.anchorX() + ", " + zone.anchorZ(), NamedTextColor.GRAY ) )
                    .append( Component.text( "  owner=" + ownerName, NamedTextColor.WHITE ) )
                    .append( Component.text( "  members=" + members, NamedTextColor.WHITE ) )
                    .build()
            );
        }
    }


    //==========================================================================
    // executeInfo
    //==========================================================================

    private void executeInfo( CommandSender sender, String id ) {

        Zone zone = plugin.zoneStore().byId( id );
        if ( zone == null ) {
            sender.sendMessage( Component.text( "No zone with id : " + id, NamedTextColor.RED ) );
            return;
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" ).withZone( ZoneOffset.UTC );
        String ownerName = resolvePlayerName( zone.owner() );

        sender.sendMessage( Component.text( "Zone : " + zone.id(), NamedTextColor.GOLD ).decorate( TextDecoration.BOLD ) );
        sender.sendMessage( infoLine( "World",   zone.world() ) );
        sender.sendMessage( infoLine( "Anchor",  zone.anchorX() + ", " + zone.anchorY() + ", " + zone.anchorZ() ) );
        sender.sendMessage( infoLine( "Owner",   ownerName + " (" + zone.owner() + ")" ) );
        sender.sendMessage( infoLine( "Mode",    zone.mode().name().toLowerCase( Locale.ROOT ) ) );
        sender.sendMessage( infoLine( "Created", fmt.format( zone.createdAt() ) + " UTC" ) );
        sender.sendMessage( infoLine( "Updated", fmt.format( zone.updatedAt() ) + " UTC" ) );

        if ( !zone.allow().isEmpty() ) {
            sender.sendMessage( Component.text( "  Allow :", NamedTextColor.GREEN ) );
            for ( ZoneMember m : zone.allow() ) {
                sender.sendMessage( Component.text( "    " + m, NamedTextColor.WHITE ) );
            }
        }

        if ( !zone.deny().isEmpty() ) {
            sender.sendMessage( Component.text( "  Deny :", NamedTextColor.RED ) );
            for ( ZoneMember m : zone.deny() ) {
                sender.sendMessage( Component.text( "    " + m, NamedTextColor.WHITE ) );
            }
        }
    }


    //==========================================================================
    // executeDisable
    //==========================================================================

    private void executeDisable( CommandSender sender, String id ) {

        Zone zone = plugin.zoneStore().byId( id );
        if ( zone == null ) {
            sender.sendMessage( Component.text( "No zone with id : " + id, NamedTextColor.RED ) );
            return;
        }

        plugin.zoneStore().delete( zone.id() );

        sender.sendMessage( Component.text( "Zone " + zone.id() + " disabled.", NamedTextColor.GREEN ) );
    }


    //==========================================================================
    // executeAllow
    //==========================================================================

    private void executeAllow( CommandSender sender, String name ) {

        plugin.allowPlayer( name );
        sender.sendMessage( Component.text( "Allowed " + name + " to use SafeBase.", NamedTextColor.GREEN ) );
    }


    //==========================================================================
    // executeDeny
    //==========================================================================

    private void executeDeny( CommandSender sender, String name ) {

        plugin.denyPlayer( name );
        sender.sendMessage( Component.text( "Denied " + name + " from using SafeBase. Their zones have been disabled.", NamedTextColor.GREEN ) );
    }


    //==========================================================================
    // Helpers
    //==========================================================================

    private void sendHelp( CommandSender sender ) {

        sender.sendMessage(
            Component.text( "SafeBase commands :", NamedTextColor.GOLD )
                .decorate( TextDecoration.BOLD )
        );

        sender.sendMessage( helpLine( "/safebase list",         "list all zones"                          ) );
        sender.sendMessage( helpLine( "/safebase info <id>",    "details on one zone"                     ) );
        sender.sendMessage( helpLine( "/safebase disable <id>", "disable a zone"                           ) );
        sender.sendMessage( helpLine( "/safebase allow <player>", "allow a player to use SafeBase"        ) );
        sender.sendMessage( helpLine( "/safebase deny <player>",  "deny a player and disable their zones" ) );
        sender.sendMessage( helpLine( "/safebase refresh",      "reload config.yml and YAML zone records" ) );
    }


    private Component helpLine( String usage, String description ) {

        return Component.text()
            .append( Component.text( "  " + usage, NamedTextColor.AQUA ) )
            .append( Component.text( "  -  ", NamedTextColor.DARK_GRAY ) )
            .append( Component.text( description, NamedTextColor.GRAY ) )
            .build();
    }


    private Component infoLine( String label, String value ) {

        return Component.text()
            .append( Component.text( "  " + label + " : ", NamedTextColor.GRAY ) )
            .append( Component.text( value != null ? value : "", NamedTextColor.WHITE ) )
            .build();
    }


    private String resolvePlayerName( java.util.UUID uuid ) {

        OfflinePlayer player = Bukkit.getOfflinePlayer( uuid );
        String name = player.getName();
        return name != null ? name : uuid.toString().substring( 0, 8 ) + "...";
    }
}
