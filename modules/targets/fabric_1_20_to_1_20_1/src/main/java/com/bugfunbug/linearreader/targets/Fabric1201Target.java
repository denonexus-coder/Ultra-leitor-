package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.fabricfamily.Fabric119To12111Bootstrap;
import com.bugfunbug.linearreader.mc1201.Minecraft1201Family;

public final class Fabric1201Target implements TargetBootstrap {

    public static final Fabric1201Target INSTANCE = new Fabric1201Target();

    private final Fabric119To12111Bootstrap loaderBootstrap =
            new Fabric119To12111Bootstrap(Minecraft1201Family.INSTANCE);

    private Fabric1201Target() {}

    public void onInitialize() {
        loaderBootstrap.onInitialize();
    }
}
