package net.astronomy.jeikubejsexporter;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(JeiKubeJsExporter.MOD_ID)
public class JeiKubeJsExporter {
    public static final String MOD_ID = "jeikubejsexporter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JeiKubeJsExporter(IEventBus modEventBus, ModContainer modContainer) {
    }
}
