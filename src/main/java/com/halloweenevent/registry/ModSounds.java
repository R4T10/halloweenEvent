package com.halloweenevent.registry;

import com.halloweenevent.HalloweenEventMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, HalloweenEventMod.MODID);

    public static final RegistryObject<SoundEvent> WHISPER = sound("whisper");
    public static final RegistryObject<SoundEvent> WHISPER_LOUD = sound("whisper_loud");
    public static final RegistryObject<SoundEvent> STINGER = sound("stinger");
    public static final RegistryObject<SoundEvent> SCREAM = sound("scream");
    public static final RegistryObject<SoundEvent> GLITCH = sound("glitch");
    public static final RegistryObject<SoundEvent> HEARTBEAT = sound("heartbeat");
    public static final RegistryObject<SoundEvent> CANDY_PICKUP = sound("candy_pickup");
    public static final RegistryObject<SoundEvent> EVENT_WIN = sound("event_win");
    public static final RegistryObject<SoundEvent> FOOTSTEP = sound("footstep");
    public static final RegistryObject<SoundEvent> GRAB = sound("grab");

    private ModSounds() {}

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(HalloweenEventMod.MODID, name)));
    }
}
