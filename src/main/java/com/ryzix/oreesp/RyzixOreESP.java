package com.ryzix.oreesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class RyzixOreESP implements ClientModInitializer {

	public static final String MOD_ID = "ryzixoreesp";

	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		// Z = toggle OreESP (rebindable in Controls > Ryzix-OreESP)
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ryzixoreesp.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_Z,
				"key.categories.ryzixoreesp"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				if (client.player == null) continue;
				OreESP.toggle();
				client.player.sendMessage(
						Text.literal("OreESP: " + (OreESP.isEnabled() ? "ON" : "OFF")), true);
			}
			OreESP.tick(client);
		});

		// Drawn after the world, on the render thread. Only iterates the cache.
		WorldRenderEvents.LAST.register(OreESP::render);
	}
}
