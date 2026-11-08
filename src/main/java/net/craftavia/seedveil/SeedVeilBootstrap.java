package net.craftavia.seedveil;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.craftavia.seedveil.datapack.BedrockDatapack;
import net.kyori.adventure.text.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs before the worlds load: generates the bedrock data pack and registers it with the server,
 * so every chunk (including the spawn chunks of the very first start) uses the protected bedrock pattern.
 */
public final class SeedVeilBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final BootstrapContext context) {
        final Path dataDir = context.getDataDirectory();
        final BedrockDatapack.Options options = BedrockDatapack.readOptions(dataDir);
        if (!options.enabled()) {
            context.getLogger().info("Bedrock data pack layer is disabled in config.yml.");
            return;
        }

        final BedrockDatapack.Result result;
        try {
            result = BedrockDatapack.generate(dataDir, options);
        } catch (final IOException | RuntimeException e) {
            context.getLogger().error("Could not generate the bedrock data pack; the bedrock pattern stays vanilla.", e);
            return;
        }
        context.getLogger().info("Bedrock data pack ready: {} (data pack format {}).", result.files(), result.packFormat());

        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                event.registrar().discoverPack(result.root(), BedrockDatapack.PACK_ID, configurer -> configurer
                        .title(Component.text("SeedVeil Bedrock"))
                        .autoEnableOnServerStart(true)
                        .position(false, Datapack.Position.TOP));
            } catch (final IOException e) {
                context.getLogger().error("Could not register the bedrock data pack with the server.", e);
            }
        });
    }
}
