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
 * menu clock's 24-hour time, whether mods' own toggle keys work, and whether Flint Store capes are drawn.
 */
public final class General extends Module {
	/** The menu's size on 1.8.9: the shared UI scaling, within what 1.8.9's menu lays out well. */
	public static final float MIN_SCALE = 0.8F;
	public static final float MAX_SCALE = 1.2F;
	/** As on every line, the menu's key is Minecraft's own binding (in Controls, under Flint Client), shown here by its key name. */
	public final Setting.Key menuKey = bind(new Setting.Key(bindingSlot(), GameOptions.MENU_KEY));
	public final Setting.Decimal menuScale = bind(new Setting.Decimal(Slot.game(GameOptions.MENU_SCALE), GameOptions.MENU_SCALE, 100, "%",
		MIN_SCALE, MAX_SCALE));
	public final Setting.Bool clock24h = bind(new Setting.Bool(Slot.game(GameOptions.CLOCK_24H), GameOptions.CLOCK_24H));
	public final Setting.Bool modKeys = bind(new Setting.Bool(Slot.game(GameOptions.MOD_KEYS), GameOptions.MOD_KEYS));
	/** Capes equipped in the Flint Store (StoreCapes189): the same switch as on every line. */
	public final Setting.Bool storeCapes = bind(new Setting.Bool(Slot.game(GameOptions.STORE_CAPES), GameOptions.STORE_CAPES));

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
