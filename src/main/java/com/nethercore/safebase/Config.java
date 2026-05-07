//==============================================================================
// Config.java
//==============================================================================
//
// Holder for the admin-tunable values from config.yml. Loaded on
// enable and re-loaded on /safebase refresh. Bogus values fall back to
// hard-coded defaults with a WARN-level log line ; the plugin still starts.
//
//==============================================================================

package com.nethercore.safebase;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;


public final class Config {

    //= Hard-coded defaults =====================================================
    //
    // These mirror the design's stated v1 defaults. Used both by config.yml
    // (as the shipped values) and as the fallback when an admin's override
    // is invalid.
    //
    //===========================================================================

    public static final int    DEFAULT_SAFE_ZONE_HALF_WIDTH    = 128;
    public static final int    DEFAULT_WARNING_MOAT_WIDTH     = 32;
    public static final int    DEFAULT_WARNING_COOLDOWN_SECS   = 30;
    public static final int    DEFAULT_SLOW_TASK_INTERVAL_TICKS = 40;
    public static final String DEFAULT_PARTICLE_EFFECT         = "color-ring";


    //= Loaded values ===========================================================

    /** Particle display modes for active-zone lectern effects. */
    public enum ParticleEffectType {
        BEACON,           // GLOW column + END_ROD sparkle (current default)
        COLORED_BEACON,  // DUST column tinted by zone mode
        COLOR_RING,       // DUST ring at platform height, tinted by zone mode
        SHIELD_DOME       // Hemisphere of DUST over the platform
    }

    private final int               defaultSafeZoneHalfWidth;
    private final int               warningMoatWidth;
    private final int               warningCooldownSeconds;
    private final int               slowTaskIntervalTicks;
    private final List< String >     allowedPlayers;
    private final ParticleEffectType particleEffect;


    //= Construction ============================================================

    private Config(
        int           defaultSafeZoneHalfWidth,
        int           warningMoatWidth,
        int           warningCooldownSeconds,
        int           slowTaskIntervalTicks,
        List< String > allowedPlayers,
        ParticleEffectType particleEffect
    ) {
        this.defaultSafeZoneHalfWidth      = defaultSafeZoneHalfWidth;
        this.warningMoatWidth       = warningMoatWidth;
        this.warningCooldownSeconds = warningCooldownSeconds;
        this.slowTaskIntervalTicks  = slowTaskIntervalTicks;
        this.allowedPlayers         = allowedPlayers;
        this.particleEffect         = particleEffect;
    }


    //==========================================================================
    // load
    //==========================================================================
    //
    // Reads the supplied FileConfiguration into a validated Config instance.
    // Each field is checked independently ; bad values log a warning and
    // fall back to the hard-coded default.
    //
    //==========================================================================

    public static Config load( FileConfiguration source, Logger log ) {

        migrateConfig( source, log );

        int safe = readInt(
            source, "default-safe-zone-half-width", DEFAULT_SAFE_ZONE_HALF_WIDTH,
            v -> v >= 1 && v <= 10000,
            "must be between 1 and 10000",
            log
        );

        int moat = readInt(
            source, "warning-moat-width", DEFAULT_WARNING_MOAT_WIDTH,
            v -> v >= 1 && v <= 10000,
            "must be between 1 and 10000",
            log
        );

        int cooldown = readInt(
            source, "warning-cooldown-seconds", DEFAULT_WARNING_COOLDOWN_SECS,
            v -> v >= 0 && v <= 3600,
            "must be between 0 and 3600",
            log
        );

        int interval = readInt(
            source, "slow-task-interval-ticks", DEFAULT_SLOW_TASK_INTERVAL_TICKS,
            v -> v >= 1 && v <= 1200,
            "must be between 1 and 1200 ticks",
            log
        );

        ParticleEffectType effect = readParticleEffect( source, log );

        List< String > allowedPlayers = source.getStringList( "allowed-players" );

        return new Config( safe, moat, cooldown, interval, allowedPlayers, effect );
    }


    //==========================================================================
    // migrateConfig — un-versioned → v5 upgrade
    //==========================================================================
    //
    // The only config format "in the wild" is the un-versioned v1 release
    // which uses safe-zone-half-width, warning-zone-half-width, and
    // default-warning-message keys. This method detects un-versioned or
    // pre-v5 configs and applies the single transformation step to v5.
    //
    // Returns true if a migration was performed (caller should persist).
    //
    //==========================================================================

    static boolean migrateConfig( FileConfiguration source, Logger log ) {

        int version = source.getInt( "schema-version", -1 );
        if ( version >= 5 ) return false;

        log.info( "Migrating config from schema-version " + ( version < 1 ? "none" : String.valueOf( version ) ) + " to 5." );

        // Rename safe-zone-half-width → default-safe-zone-half-width (pre-v4 naming).
        if ( source.contains( "safe-zone-half-width" ) && !source.contains( "default-safe-zone-half-width" ) ) {
            source.set( "default-safe-zone-half-width", source.getInt( "safe-zone-half-width", DEFAULT_SAFE_ZONE_HALF_WIDTH ) );
            source.set( "safe-zone-half-width", null );
        }

        // Replace warning-zone-half-width with computed warning-moat-width.
        if ( source.contains( "warning-zone-half-width" ) ) {
            int safe    = source.getInt( "default-safe-zone-half-width", DEFAULT_SAFE_ZONE_HALF_WIDTH );
            int oldWarn = source.getInt( "warning-zone-half-width", 160 );
            int moat    = oldWarn - safe;
            if ( moat < 1 || moat > 10000 ) moat = DEFAULT_WARNING_MOAT_WIDTH;
            source.set( "warning-moat-width", moat );
            source.set( "warning-zone-half-width", null );
        }

        // default-warning-message was removed in v5.
        source.set( "default-warning-message", null );

        source.set( "schema-version", 5 );
        log.info( "Config migrated to schema-version 5." );
        return true;
    }


    //==========================================================================
    // readInt - validated integer load with fallback
    //==========================================================================

    private static int readInt(
        FileConfiguration source,
        String key,
        int defaultValue,
        java.util.function.IntPredicate valid,
        String constraint,
        Logger log
    ) {

        if ( ! source.isInt( key ) && ! source.isLong( key ) ) {
            if ( source.contains( key ) ) {
                log.warning( "config.yml : " + key + " is not an integer. Falling back to default " + defaultValue + "." );
            }
            return defaultValue;
        }

        int value = source.getInt( key, defaultValue );

        if ( ! valid.test( value ) ) {
            log.warning( "config.yml : " + key + "=" + value + " " + constraint + ". Falling back to default " + defaultValue + "." );
            return defaultValue;
        }

        return value;
    }


    //==========================================================================
    // readParticleEffect - string-to-enum load with fallback
    //==========================================================================

    private static ParticleEffectType readParticleEffect( FileConfiguration source, Logger log ) {

        String key = "particle-effect";
        String raw = source.getString( key, DEFAULT_PARTICLE_EFFECT );

        try {
            String normalised = raw.replace( '-', '_' ).toUpperCase( Locale.ROOT );
            return ParticleEffectType.valueOf( normalised );
        } catch ( IllegalArgumentException e ) {
            log.warning( "config.yml : " + key + "=\"" + raw + "\" is invalid."
                + " Options: beacon, colored-beacon, color-ring, shield-dome."
                + " Falling back to " + DEFAULT_PARTICLE_EFFECT + "." );
            return ParticleEffectType.valueOf(
                DEFAULT_PARTICLE_EFFECT.replace( '-', '_' ).toUpperCase( Locale.ROOT )
            );
        }
    }


    //= Accessors ==============================================================

    public int                 defaultSafeZoneHalfWidth()      { return defaultSafeZoneHalfWidth; }
    public int                 warningZoneHalfWidth()   { return defaultSafeZoneHalfWidth + warningMoatWidth; }
    public int                 warningMoatWidth()       { return warningMoatWidth; }
    public int                 warningCooldownSeconds() { return warningCooldownSeconds; }
    public int                 slowTaskIntervalTicks()  { return slowTaskIntervalTicks; }
    public List< String >      allowedPlayers()         { return allowedPlayers; }
    public ParticleEffectType  particleEffect()         { return particleEffect; }
}
