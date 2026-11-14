package com.flintclient.legacy;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.shared.KeyNames189;
import com.flintclient.legacy.shared.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;

/**
 * Flint's own settings on 1.8.9 (the menu's Settings tab): the shared client settings 1.8.9 has, on the catalog's General page
 * rows: the menu's key (kept in step with Minecraft's Controls, where it is listed under Flint Client), the menu's size, the
 * menu clock's 24-hour time, whether mods' own toggle keys work, whether Flint Store capes are drawn, Flint's main menu or
 * Minecraft's, whether leaving a server asks first, Smooth screen sharing and Borderless fullscreen.
 */
public final class General extends Module {
	/** As on every line, the menu's key is Minecraft's own binding (in Controls, under Flint Client), shown here by its key name. */
	public final Setting.Key menuKey = bind(new Setting.Key(bindingSlot(), GameOptions.MENU_KEY));
	/** General › Menu size (Small, Medium, Large), as on every line: the shared menu reads it ({@code MenuStyle}). */
	public final Setting.Choice menuSize = bind(new Setting.Choice(Slot.game(GameOptions.MENU_SIZE), GameOptions.MENU_SIZE));
	/** Menu transparency and blur strength: kept as on every line (1.8.9's menu draws its panels solid and unblurred). */
	public final Setting.Int menuTransparency = bind(new Setting.Int(Slot.game(GameOptions.MENU_TRANSPARENCY), GameOptions.MENU_TRANSPARENCY));
	public final Setting.Int menuBlur = bind(new Setting.Int(Slot.game(GameOptions.MENU_BLUR), GameOptions.MENU_BLUR));
	public final Setting.Bool clock24h = bind(new Setting.Bool(Slot.game(GameOptions.CLOCK_24H), GameOptions.CLOCK_24H));
	public final Setting.Bool modKeys = bind(new Setting.Bool(Slot.game(GameOptions.MOD_KEYS), GameOptions.MOD_KEYS));
	/** Capes equipped in the Flint Store (StoreCapes189): the same switch as on every line. */
	public final Setting.Bool storeCapes = bind(new Setting.Bool(Slot.game(GameOptions.STORE_CAPES), GameOptions.STORE_CAPES));
	/** Minecraft's own title screen instead of Flint's main menu (the menu's switch sets the same value), as on every line. */
	public final Setting.Bool vanillaMainMenu = bind(new Setting.Bool(Slot.game(GameOptions.VANILLA_MAIN_MENU), GameOptions.VANILLA_MAIN_MENU));
	/** The pause menu's Disconnect asks first on a server ({@code mixin.GameMenuScreenMixin}), as on every line. */
	public final Setting.Bool leaveConfirm = bind(new Setting.Bool(Slot.game(GameOptions.LEAVE_CONFIRM), GameOptions.LEAVE_CONFIRM));
	/** Smooth screen sharing (perf/GpuPriority): the same switch as on every line. */
	public final Setting.Bool smoothSharing = bind(new Setting.Bool(Slot.game(GameOptions.SMOOTH_SHARING), GameOptions.SMOOTH_SHARING));
	/** Borderless fullscreen (BorderlessWindow189): the same switch as on every line. */
	public final Setting.Bool borderlessFullscreen = bind(new Setting.Bool(Slot.game(GameOptions.BORDERLESS_FULLSCREEN), GameOptions.BORDERLESS_FULLSCREEN));

	General() {
		super("general", "hud_editor", "Settings", "Flint's keys and how large its menu is");
	}

	private static Slot bindingSlot() {
		return new Slot() {
			private int revision;

			private KeyBinding binding() {
				return Flint.keys()[0];
			}

			@Override
			public Object get() {
				return KeyNames189.name(binding().getKeyCode());
			}

			@Override
			public void set(Object value) {
				int code = KeyNames189.code((String) value);
				if (code != binding().getKeyCode()) {
					binding().setKeyCode(code);
					KeyBinding.resetMapping();
					revision++;
					Minecraft mc = Minecraft.getInstance();
					if (mc.options != null) {
						mc.options.save();
					}
				}
			}

			@Override
			public Object defaultValue() {
				return KeyNames189.name(binding().getDefaultKeyCode());
			}

			@Override
			public int revision() {
				return revision;
			}
		};
	}
}
