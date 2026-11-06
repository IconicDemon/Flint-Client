package com.flintclient.client.control.platform.ambient;

import java.lang.reflect.Field;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A condition that holds, with its volume and the narrower selection inside it (a region's variant, a sound's own
 * condition). Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
class AmbientSelection extends AmbientVolume {
	final AmbientCondition condition;
	@Nullable AmbientSelection subSelection;

	AmbientSelection(AmbientCondition condition) {
		super(1, condition.volume);
		this.condition = condition;
	}

	@Override
	void assign(AmbientVolume volume) {
		if (volume instanceof AmbientSelection sel) {
			subSelection = sel.subSelection;
		}
		super.assign(volume);
	}

	@Override
	double conditionVolume() {
		return subSelection != null ? subSelection.conditionVolume() * super.conditionVolume() : super.conditionVolume();
	}

	@Override
	double settingVolume() {
		return subSelection != null ? subSelection.settingVolume() * super.settingVolume() : super.settingVolume();
	}

	@Override
	double volume() {
		return subSelection != null ? subSelection.volume() * super.volume() : super.volume();
	}

	AmbientSelection last() {
		return subSelection == null ? this : subSelection.last();
	}

	AmbientSoundProperties getProperties() {
		AmbientSoundProperties properties = new AmbientSoundProperties();
		assignProperties(properties);
		return properties;
	}

	private static final Field[] PROPERTY_FIELDS = AmbientSoundProperties.class.getFields();

	void assignProperties(AmbientSoundProperties properties) {
		try {
			for (Field field : PROPERTY_FIELDS) {
				Object value = field.get(condition);
				if (value != null) {
					field.set(properties, value);
				}
			}
		} catch (IllegalAccessException | IllegalArgumentException e) {
			throw new IllegalStateException(e);
		}
		if (subSelection != null) {
			subSelection.assignProperties(properties);
		}
	}

	/** A sound's selection together with its categories' (each multiplies in). */
	static final class Multi extends AmbientSelection {
		private final List<AmbientSelection> selections;

		Multi(AmbientSelection selection, List<AmbientSelection> selections) {
			super(selection.condition);
			assign(selection);
			this.selections = selections;
		}

		@Override
		double conditionVolume() {
			double volume = 1;
			for (AmbientSelection sel : selections) {
				volume *= sel.conditionVolume();
			}
			return volume * super.conditionVolume();
		}

		@Override
		double settingVolume() {
			double volume = 1;
			for (AmbientSelection sel : selections) {
				volume *= sel.settingVolume();
			}
			return volume * super.settingVolume();
		}

		@Override
		double volume() {
			double volume = 1;
			for (AmbientSelection sel : selections) {
				volume *= sel.volume();
			}
			return volume * super.volume();
		}

		@Override
		void assignProperties(AmbientSoundProperties properties) {
			for (AmbientSelection sel : selections) {
				sel.assignProperties(properties);
			}
			super.assignProperties(properties);
		}
	}
}
