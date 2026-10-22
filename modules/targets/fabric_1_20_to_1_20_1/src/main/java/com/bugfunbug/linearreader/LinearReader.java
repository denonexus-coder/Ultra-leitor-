package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1201.Minecraft1201Family;
import com.bugfunbug.linearreader.targets.Fabric1201Target;
import net.fabricmc.api.ModInitializer;

public class LinearReader implements ModInitializer {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1201Family.INSTANCE);
    }

    @Override
    public void onInitialize() {
        Fabric1201Target.INSTANCE.onInitialize();
    }
}
