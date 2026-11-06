package com.flintclient.client.util;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What Flint's credits page says (ui/screen/FlintCreditsScreen): who made Flint Client, its licence, the open-source software
 * it runs on and ships, and the mods whose code or assets its features adapt, each with its licence as its own licence file
 * states it. Every licence text Flint ships is in the runtime jar's {@code META-INF/licenses}, one file per entry that has
 * one ({@link Entry#licenseFile}); a test keeps this list and that folder in step. Plain data, free of Minecraft types.
 */
public final class FlintCredits {
	/** One credited work: its name, its licence, who made it (null when its licence file doesn't say), and its licence file. */
	public record Entry(String name, String license, @Nullable String by, @Nullable String licenseFile) {
	}

	/** A titled group of entries. */
	public record Section(String title, List<Entry> entries) {
	}

	public static final String PRODUCT = "Flint Client";
	public static final String CREATOR = "Created by IconicDemon";
	public static final String LICENSE = "Flint Client is free software, released under the GNU General Public License, version 3. "
		+ "The software below keeps its own licence; every licence text Flint ships is inside Flint's game files (META-INF/licenses).";
	/** Mojang's brand guidelines' wording for a product that isn't theirs. */
	public static final String NOT_OFFICIAL = "Flint Client is not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.";
	public static final String MINECRAFT = "Minecraft is a trademark of Mojang AB. Flint doesn't include Minecraft's game files: the launcher "
		+ "downloads them from Mojang for players who own the game.";

	public static final List<Section> SECTIONS = List.of(
		new Section("Runs on", List.of(
			new Entry("Fabric Loader", "Apache License 2.0", "FabricMC", null),
			new Entry("Mixin", "MIT License", "SpongePowered, with FabricMC's changes", null),
			new Entry("MixinExtras", "MIT License", "LlamaLad7", null),
			new Entry("Gson", "Apache License 2.0", "Google (included with Minecraft)", null),
			new Entry("LWJGL", "BSD 3-Clause License", "Lightweight Java Game Library (included with Minecraft)", null),
			new Entry("JOML", "MIT License", "JOML contributors (included with Minecraft)", null))),
		new Section("Fonts and icons", List.of(
			new Entry("Nunito Sans", "SIL Open Font License 1.1", "The Nunito Project Authors", "OFL-NunitoSans.txt"),
			new Entry("Inter", "SIL Open Font License 1.1", "The Inter Project Authors", "OFL-Inter.txt"),
			new Entry("Outfit", "SIL Open Font License 1.1", "The Outfit Project Authors", "OFL-Outfit.txt"),
			new Entry("Font Awesome Free 6.5.2", "SIL Open Font License 1.1", "Fonticons, Inc.", "OFL-1.1-FontAwesome-Free-6.5.2.txt"),
			new Entry("Lucide", "ISC License", "Lucide Icons and Contributors", "ISC-Lucide.txt"))),
		new Section("Features adapted from", List.of(
			new Entry("AmbientSounds", "LGPL-3.0", "CreativeMD and AriaFreeze", "LGPL-3.0-AmbientSounds.txt"),
			new Entry("AppleSkin", "The Unlicense", "squeek502", "Unlicense-AppleSkin.txt"),
			new Entry("BetterF3", "MIT License", "cominixo and TreyRuffy", "MIT-BetterF3.txt"),
			new Entry("BetterHurtCam", "MIT License", "uku", "MIT-BetterHurtCam.txt"),
			new Entry("Chat Heads", "Mozilla Public License 2.0", "dzwdz and Fourmisain", "MPL-2.0-ChatHeads.txt"),
			new Entry("Clean View", "MIT License", "NotZeox", "MIT-CleanView.txt"),
			new Entry("Clipboard Screenshot", "MIT License", "Blugon0921", "MIT-ClipboardScreenshot.txt"),
			new Entry("Clumps", "MIT License", "Jaredlll08", "MIT-Clumps.txt"),
			new Entry("Combat Hitboxes", "Apache License 2.0", "Sootysplash", "Apache-2.0-CombatHitboxes.txt"),
			new Entry("Custom Crosshair Mod and Crosshair Indicator", "CC0 1.0", "wjbaker, and Crosshair Indicator's author",
				"CC0-1.0-CustomCrosshairMod.txt"),
			new Entry("Custom Fog", "MIT License", "Setadokalo", "MIT-CustomFog.txt"),
			new Entry("Dropped Item Tweaks", "LGPL-3.0", "Microcontrollers", "LGPL-3.0-DroppedItemTweaks.txt"),
			new Entry("Falling Leaves", "MIT License", "RandomMcSomethin", "MIT-FallingLeaves.txt"),
			new Entry("Loot Beams Refork", "CC0 1.0", "Clefal", "CC0-1.0-LootBeamsRefork.txt"),
			new Entry("Model Gap Fix", "GPL-3.0", "MehVahdJukaar", "GPL-3.0-ModelGapFix.txt"),
			new Entry("Natural Motion Blur", "LGPL-3.0", "Pasi, IMS212, Romain H and What42Pizza", "LGPL-3.0-NaturalMotionBlur.txt"),
			new Entry("No Chat Reports", "WTFPL", "Aizistral", "WTFPL-NoChatReports.txt"),
			new Entry("Ping View", "MIT License", "karlsson1000, with itsrec0very", "MIT-PingView.txt"),
			new Entry("Player Health Indicators", "MIT License", "Gaider10", "MIT-PlayerHealthIndicators.txt"),
			new Entry("Player Reach Display", "MIT License", "KikiJiji", "MIT-PlayerReachDisplay.txt"),
			new Entry("Potion Counter", "MIT License", "uku", "MIT-PotionCounter.txt"),
			new Entry("ptdisplay (Playtime Display)", "LGPL-3.0", "JuiceyBeans", "LGPL-3.0-ptdisplay.txt"),
			new Entry("Realistic Item Drops", "CC BY-SA 4.0", "Silabear and Stoupy51", "CC-BY-SA-4.0-RealisticItemDrops.txt"),
			new Entry("ShulkerBoxTooltip", "MIT License", "Yanis Guaye", "MIT-ShulkerBoxTooltip.txt"),
			new Entry("Sound Physics Remastered", "GPL-3.0", "Sonic Ether, vlad2305m, Max Henkel and Saint", "GPL-3.0-SoundPhysicsRemastered.txt"),
			new Entry("Status Effect Bars", "LGPL-3.0", "A5b84 and contributors", "LGPL-3.0-StatusEffectBars.txt"),
			new Entry("TierTagger", "Mozilla Public License 2.0", "MCTiers", "MPL-2.0-TierTagger.txt"),
			new Entry("Title Tweaks", "LGPL-3.0", "Microcontrollers and Wyvest", "LGPL-3.0-TitleTweaks.txt"),
			new Entry("Toast Control", "MIT License", "Brennan Ward", "MIT-ToastControl.txt"),
			new Entry("uku's Armor HUD", "MIT License", "BerdinskiyBear", "MIT-ukus-armor-hud.txt"),
			new Entry("Zoomify", "LGPL-3.0", "isXander", "LGPL-3.0-Zoomify.txt"))));

	private FlintCredits() {
	}
}
