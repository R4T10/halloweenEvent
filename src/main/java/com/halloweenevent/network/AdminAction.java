package com.halloweenevent.network;

/** Action ids for {@link C2SAdminAction}. Shared by client and server. */
public final class AdminAction {
    public static final int REFRESH = 0;
    public static final int START = 1;
    public static final int STOP = 2;
    public static final int CORNER_1 = 3;
    public static final int CORNER_2 = 4;
    public static final int CLEAR_ZONE = 5;
    public static final int RESET_PROGRESS = 6;
    public static final int ADD_CANDY = 7;
    public static final int REMOVE_NEAREST_CANDY = 8;
    public static final int CLEAR_CANDIES = 9;
    public static final int TOGGLE_MODE = 10;
    /** value = new respawn seconds */
    public static final int SET_RESPAWN = 11;
    /** value = new candy target */
    public static final int SET_TARGET = 12;
    public static final int REWARD_FROM_HAND = 13;
    /** text = command */
    public static final int SET_REWARD_COMMAND = 14;
    /** value = level, text = player UUID */
    public static final int SET_PLAYER_LEVEL = 15;
    /** value = level */
    public static final int SET_DEFAULT_LEVEL = 16;
    public static final int TOGGLE_HOLD_SCARES = 17;
    public static final int TOGGLE_PREVENT_CARRY = 18;
    /** value = seconds, 0 disables */
    public static final int SET_CARRY_DROP = 19;
    public static final int TOGGLE_FREECAM_GUARD = 20;
    /** value = seconds between hand-grab checks, 0 disables */
    public static final int SET_GRAB_INTERVAL = 23;

    private AdminAction() {}
}
