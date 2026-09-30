package com.ryzix.oreesp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

/**
 * FullBright, always on: keeps a client-side Night Vision effect applied.
 * Long finite duration = no night-vision flicker. Hidden icon/particles.
 * Re-applied automatically after joining a world / respawning.
 */
public final class FullBright {

	private static final int DURATION_TICKS = 1_000_000;

	private FullBright() {}

	public static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return;

		if (!player.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
			player.addStatusEffect(new StatusEffectInstance(
					StatusEffects.NIGHT_VISION, DURATION_TICKS, 0, false, false, false));
		}
	}
}
