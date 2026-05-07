//==============================================================================
// BookParser.java
//==============================================================================
//
// Parses and generates SafeBase book content. Pure logic - no Bukkit state,
// no side effects.
//
// In v5 the book holds only player names (one per line).
// Comments (# lines) are ignored.
//
// A book whose first non-blank line starts with # and contains "disabled"
// (case-insensitive) is treated as disabled.
//
//==============================================================================

package com.nethercore.safebase;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;


public final class BookParser {

    //==========================================================================
    // Patterns
    //==========================================================================

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private static final Pattern LEGACY_COLOR = Pattern.compile(
        "§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DISABLED_LINE = Pattern.compile(
        ".*disabled.*", Pattern.CASE_INSENSITIVE
    );


    //==========================================================================
    // ParseResult
    //==========================================================================

    public sealed interface ParseResult {

        record Active(
            List< ZoneMember > members
        ) implements ParseResult {}

        record Disabled() implements ParseResult {}
    }


    //==========================================================================
    // parse
    //==========================================================================

    public static ParseResult parse( List< Component > pages ) {

        String flat = flatten( pages );
        String[] lines = flat.split( "\n", -1 );
        return parseLines( lines );
    }


    //==========================================================================
    // parseLines
    //==========================================================================

    private static ParseResult parseLines( String[] lines ) {

        // Check first non-blank line for disabled marker.
        for ( String line : lines ) {
            String trimmed = line.strip();
            if ( trimmed.isEmpty() ) continue;
            if ( trimmed.startsWith( "#" ) && DISABLED_LINE.matcher( trimmed ).matches() ) {
                return new ParseResult.Disabled();
            }
            break;
        }

        List< ZoneMember > members = new ArrayList<>();

        for ( String line : lines ) {
            String trimmed = line.strip();
            if ( trimmed.isEmpty() ) continue;
            if ( trimmed.startsWith( "#" ) ) continue;

            // UUID-formatted entry.
            if ( UUID_PATTERN.matcher( trimmed ).matches() ) {
                try {
                    java.util.UUID uuid = java.util.UUID.fromString( trimmed );
                    members.add( new ZoneMember( uuid, null ) );
                } catch ( IllegalArgumentException e ) {
                    members.add( new ZoneMember( null, trimmed ) );
                }
                continue;
            }

            // Player name. Accept any non-empty line that passed the special-case
            // filters. Max 32 chars as a sanity check.
            if ( trimmed.length() <= 32 ) {
                members.add( new ZoneMember( null, trimmed ) );
                continue;
            }

            // Unrecognized line - silently dropped.
        }

        return new ParseResult.Active( members );
    }


    //==========================================================================
    // generate - produces the canonical book page(s) from zone data
    //==========================================================================

    private static final int LINES_PER_PAGE = 14;


    public static List< Component > generate( Zone zone ) {

        List< String > lines = new ArrayList<>();

        // Player list.
        List< ZoneMember > members = zone.mode() == Zone.Mode.WHITELIST ? zone.allow() : zone.deny();
        for ( ZoneMember m : members ) {
            if ( m.name() != null ) {
                lines.add( m.name() );
            } else if ( m.uuid() != null ) {
                lines.add( m.uuid().toString() );
            }
        }

        return paginate( lines );
    }


    //==========================================================================
    // paginate - splits lines into book pages
    //==========================================================================

    static List< Component > paginate( List< String > lines ) {

        List< Component > pages = new ArrayList<>();

        for ( int i = 0; i < lines.size(); i += LINES_PER_PAGE ) {
            int end = Math.min( i + LINES_PER_PAGE, lines.size() );
            String pageText = String.join( "\n", lines.subList( i, end ) );
            pages.add( Component.text( pageText ) );
        }

        if ( pages.isEmpty() ) pages.add( Component.text( "" ) );
        return pages;
    }


    //==========================================================================
    // flatten
    //==========================================================================

    public static String flatten( List< Component > pages ) {

        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        StringBuilder sb = new StringBuilder();

        for ( int i = 0; i < pages.size(); i++ ) {
            if ( i > 0 ) sb.append( '\n' );
            String text = plain.serialize( pages.get( i ) );
            text = LEGACY_COLOR.matcher( text ).replaceAll( "" );
            sb.append( text );
        }

        return sb.toString();
    }
}
