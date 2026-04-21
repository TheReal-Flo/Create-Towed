package dev.justfeli.towed;

import dev.justfeli.towed.client.TowedClientEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = CreateTowed.MODID, dist = Dist.CLIENT)
public class CreateTowedClient {
    public CreateTowedClient(final IEventBus modEventBus, final ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.register(TowedClientEvents.class);
    }
}
