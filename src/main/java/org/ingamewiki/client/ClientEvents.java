package org.ingamewiki.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ingamewiki.InGameWiki;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    private static final KeyMapping OPEN = new KeyMapping("key.ingamewiki.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.ingamewiki");

    @Mod.EventBusSubscriber(modid = InGameWiki.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    }
    @Mod.EventBusSubscriber(modid = InGameWiki.ID, value = Dist.CLIENT)
    public static class ForgeBus {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new WikiScreen());
        }
    }
}
