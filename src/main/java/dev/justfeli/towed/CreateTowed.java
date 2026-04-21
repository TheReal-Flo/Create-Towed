package dev.justfeli.towed;

import com.mojang.logging.LogUtils;
import dev.justfeli.towed.config.TowedConfig;
import dev.justfeli.towed.index.TowedDataComponents;
import dev.justfeli.towed.network.TowedPacketManager;
import dev.justfeli.towed.platform.TowedPlatform;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(CreateTowed.MODID)
public class CreateTowed {
    public static final String MODID = "towed";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateTowed(final IEventBus modEventBus, final ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        TowedDataComponents.register();
        TowedPacketManager.init();
        TowedPlatform.init();
        modContainer.registerConfig(ModConfig.Type.SERVER, TowedConfig.SERVER_SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Initializing {}", MODID);
    }

    public static ResourceLocation path(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
