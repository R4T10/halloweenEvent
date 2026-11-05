package com.halloweenevent.data;

import java.util.Locale;

/**
 * The kinds of scare a player can be exempted from (admin panel, "Exempt" tab). Shared by server and client: the
 * server never sends or applies a blocked kind, the client also drops blocked parts of combined scares (the horror
 * text, for example, brings eyes and glitches along). A player's exemptions travel as a bit mask, bit = ordinal.
 * Append new kinds at the end: saved masks depend on the order.
 */
public enum ScareKind {
    /** Whispers and the other spooky voices. */
    WHISPER,
    /** Horror text on the screen. */
    TEXT,
    /** Eyes on the screen: swarms and the eye wall. */
    EYES,
    /** The dark figure appearing (and the short blindness at level 3). */
    FIGURE,
    /** A figure sprinting at the player. */
    CHASE,
    /** The dark figure dragging the player away (and the clawed hand on the screen). */
    GRAB,
    /** The red hypnosis eye. */
    HYPNO,
    /** Screaming jumpscare faces (Carry On, Freecam, a chase that catches you). */
    FACE,
    /** Screen glitches, static and shaking. */
    GLITCH,
    /** The flashlight being killed, flickering, brown-outs. */
    FLASHLIGHT,
    /** Losing health at scare level 3. */
    DAMAGE;

    public int bit() {
        return 1 << ordinal();
    }

    public boolean in(int mask) {
        return (mask & bit()) != 0;
    }

    public String key() {
        return "gui.halloweenevent.scare." + name().toLowerCase(Locale.ROOT);
    }

    /** Every kind blocked. */
    public static final int ALL = (1 << values().length) - 1;
}
