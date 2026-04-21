package dev.justfeli.towed.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class TowedConfig {

    // -------------------------------------------------------------------------
    // Server config
    // -------------------------------------------------------------------------

    public static final class Server {
        public final ModConfigSpec.IntValue maxRopesPerEntity;

        Server(final ModConfigSpec.Builder builder) {
            builder.comment("Server-side settings for Create: Towed").push("server");

            this.maxRopesPerEntity = builder
                    .comment(
                            "Maximum number of tow-ropes that can be attached to a single entity (animal/mob) at once.",
                            "Default: 2  |  Min: 1  |  Max: 64"
                    )
                    .defineInRange("max_ropes_per_entity", 2, 1, 64);

            builder.pop();
        }
    }

    // -------------------------------------------------------------------------
    // Singleton holders
    // -------------------------------------------------------------------------

    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        final ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();
        SERVER = new Server(serverBuilder);
        SERVER_SPEC = serverBuilder.build();
    }

    private TowedConfig() {}
}
