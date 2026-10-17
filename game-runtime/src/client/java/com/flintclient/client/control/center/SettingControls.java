package com.flintclient.client.control.center;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.ColorSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IdSetting;
import com.flintclient.client.control.model.Labelled;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Segmented;
import com.flintclient.client.ui.kit.SettingRow;
import com.flintclient.client.ui.kit.Slider;
import com.flintclient.client.ui.kit.SliderModel;
import com.flintclient.client.ui.kit.Swatches;
import com.flintclient.client.ui.kit.Switch;
import com.flintclient.client.ui.kit.TextField;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Builds a setting's row from its descriptor, whatever the module: an on/off setting is a switch, a number a
 * slider with its unit, a choice a segmented control (a cycler when it doesn't fit), a colour the swatches. Each row
 * changes the module at once and registers how to bring it back in line with the model (run when the layout's
 * revision moves), so drawing never allocates. Shared by the deck's inspector and anything else listing settings.
 */
public final class SettingControls {
	/** Every colour in Flint's palette, Default first (drawn as a split circle). */
	static final HudColor[] COLOURS = HudColor.values();

	private SettingControls() {
	}

	/**
	 * A row for {@code setting} of {@code module}; {@code changed} hears whether a user change did change anything;
	 * {@code syncs} gets the row's way back to the model.
	 */
	public static SettingRow row(ModuleState module, SettingDescriptor<?> setting, Consumer<Boolean> changed, List<Runnable> syncs) {
		Node control = switch (setting) {
			case BoolSetting bool -> {
				Switch toggle = new Switch(module.bool(bool), on -> changed.accept(module.set(bool, on)));
				syncs.add(() -> toggle.setOn(module.bool(bool)));
				yield toggle;
			}
			case IntSetting number -> {
				Slider slider = new Slider(new SliderModel(number.min(), number.max(), number.step()), module.integer(number), 0, number.unit(),
					v -> changed.accept(module.set(number, Math.round(v))), null);
				syncs.add(() -> slider.setValue(module.integer(number)));
				yield slider;
			}
			case FloatSetting number -> {
				Slider slider = new Slider(new SliderModel(number.min(), number.max(), number.step()), module.decimal(number),
					decimals(number.step()), number.unit(), v -> changed.accept(module.set(number, v)), null);
				syncs.add(() -> slider.setValue(module.decimal(number)));
				yield slider;
			}
			case ColorSetting colour -> {
				Swatches swatches = swatches(COLOURS, module.choice(colour), value -> changed.accept(module.set(colour, value)));
				syncs.add(() -> swatches.setSelected(module.choice(colour).ordinal()));
				yield swatches;
			}
			case ChoiceSetting<?> choice -> choice(module, choice, changed, syncs);
			case RgbSetting rgb -> {
				// A hex field: a valid colour applies as it is typed.
				TextField field = new TextField("#RRGGBB", 7);
				field.setText(RgbSetting.hex(module.rgb(rgb)));
				field.onChange(text -> {
					int value = RgbSetting.parseHex(text);
					if (value >= 0) {
						changed.accept(module.set(rgb, value));
					}
				});
				// Syncs run when the layout changes (not while a half-typed value is being edited).
				syncs.add(() -> {
					if (RgbSetting.parseHex(field.text()) != module.rgb(rgb)) {
						field.setText(RgbSetting.hex(module.rgb(rgb)));
					}
				});
				yield field;
			}
			case OptionSetting option -> {
				String[] labels = option.labels().toArray(new String[0]);
				Segmented segmented = new Segmented(labels, module.option(option), i -> changed.accept(module.set(option, i)));
				syncs.add(() -> segmented.setSelected(module.option(option)));
				yield segmented;
			}
			case TextSetting text -> {
				TextField field = new TextField(text.label(), text.maxLength());
				field.setText(module.text(text));
				field.onChange(value -> changed.accept(module.set(text, value)));
				syncs.add(() -> {
					if (!field.text().equals(module.text(text))) {
						field.setText(module.text(text));
					}
				});
				yield field;
			}
			case IdSetting id -> pageOnly(module.text(id).isEmpty() ? "None" : module.text(id));
			case IdValuesSetting values -> pageOnly(module.values(values).size() + " changed");
			case IdListSetting list -> pageOnly(module.ids(list).size() + " items");
			case KeySetting key -> {
				// Keys are set on the module's page in the menu (it captures the next key pressed); here they're shown.
				TextField field = new TextField("None", 40);
				field.setText(key.label());
				field.setDisabled("Change keys on the module's page");
				yield field;
			}
		};
		// A setting another one makes moot (the CPS format with one button) is shown disabled, saying why.
		syncs.add(() -> {
			String why = ModuleSettings.inactiveReason(module, setting);
			if (why == null) {
				control.setEnabled(true);
			} else {
				control.setDisabled(why);
			}
		});
		return new SettingRow(setting.label(), setting.description(), control, setting.explain());
	}

	/** A setting edited on the module's page in the menu (it needs the list picker): shown here, not edited. */
	private static Node pageOnly(String shown) {
		TextField field = new TextField(shown, 120);
		field.setText(shown);
		field.setDisabled("Change this on the module's page");
		return field;
	}

	private static <E extends Enum<E> & Labelled> Segmented choice(ModuleState module, ChoiceSetting<E> choice, Consumer<Boolean> changed,
		List<Runnable> syncs) {
		List<E> options = choice.options();
		String[] labels = new String[options.size()];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = options.get(i).label();
		}
		Segmented segmented = new Segmented(labels, options.indexOf(module.choice(choice)), i -> changed.accept(module.set(choice, options.get(i))));
		syncs.add(() -> segmented.setSelected(options.indexOf(module.choice(choice))));
		return segmented;
	}

	/** Swatches for {@code colours} (Default as a split circle), {@code selected} chosen. */
	public static Swatches swatches(HudColor[] colours, @Nullable HudColor selected, Consumer<HudColor> chosen) {
		int[] argb = new int[colours.length];
		String[] names = new String[colours.length];
		int index = 0;
		for (int i = 0; i < colours.length; i++) {
			argb[i] = colours[i].isDefault() ? 0 : colours[i].argb();
			names[i] = colours[i].isDefault() ? "Default (the style's own)" : colours[i].label();
			if (colours[i] == selected) {
				index = i;
			}
		}
		return new Swatches(argb, names, index, i -> chosen.accept(colours[i]));
	}

	/** Slider decimals for a step: 0.05 shows two, 0.5 one, 1 none. */
	static int decimals(float step) {
		return Math.clamp(new BigDecimal(Float.toString(step)).stripTrailingZeros().scale(), 0, 3);
	}
}
