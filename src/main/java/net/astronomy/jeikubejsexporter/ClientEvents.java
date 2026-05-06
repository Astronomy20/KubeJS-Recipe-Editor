package net.astronomy.jeikubejsexporter;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.astronomy.jeikubejsexporter.gui.RecipeExporterScreen;

@EventBusSubscriber(modid = JeiKubeJsExporter.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientSetup.OPEN_EXPORTER_KEY.consumeClick() && mc.screen == null && mc.player != null) {
            mc.setScreen(new RecipeExporterScreen());
        }
    }
}
