package com.flintclient.client.control.platform.ambient;

import java.util.Locale;

/**
 * A volume in two parts: what the surroundings make it (the condition) and what the settings make it. Adapted from
 * AmbientSounds (LGPL-3.0, CreativeMD; see META-INF/licenses/LGPL-3.0-AmbientSounds.txt).
 */
class AmbientVolume {
	static final AmbientVolume SILENT = new AmbientVolume(0, 0) {
		@Override
		void mulCondition(double volume) {
		}

		@Override
		void mulSetting(double volume) {
		}
	};
	static final AmbientVolume MAX = new AmbientVolume(1, 1) {
		@Override
		void mulCondition(double volume) {
		}

		@Override
		void mulSetting(double volume) {
		}
	};
	private double conditionVolume;
	private double settingVolume;

	AmbientVolume(double conditionVolume, double settingVolume) {
		this.conditionVolume = conditionVolume;
		this.settingVolume = settingVolume;
	}

	void assign(AmbientVolume volume) {
		conditionVolume = volume.conditionVolume;
		settingVolume = volume.settingVolume;
	}

	void setConditionVolumeDirect(double volume) {
		conditionVolume = volume;
	}

	void mulVolume(AmbientVolume selection) {
		mulCondition(selection.conditionVolume());
		mulSetting(selection.settingVolume());
	}

	void mulCondition(double volume) {
		conditionVolume *= volume;
	}

	void mulSetting(double volume) {
		settingVolume *= volume;
	}

	double conditionVolume() {
		return conditionVolume;
	}

	double settingVolume() {
		return settingVolume;
	}

	double volume() {
		return settingVolume * conditionVolume;
	}

	AmbientVolume copy() {
		return new AmbientVolume(conditionVolume, settingVolume);
	}

	@Override
	public String toString() {
		return AmbientSounds.f(volume()) + "(" + AmbientSounds.f(conditionVolume()) + ")";
	}
}
