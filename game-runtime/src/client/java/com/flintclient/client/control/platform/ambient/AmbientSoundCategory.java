package com.flintclient.client.control.platform.ambient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A kind of sound (birds by day, crickets by night, wind, weather...): a condition of its own, nested under its parent,
 * and a volume setting. A sound plays only while all its categories hold. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientSoundCategory extends AmbientCondition {
	public String parent;
	public AmbientStackType stack = AmbientStackType.overwrite;
	transient String name;
	transient double volumeSetting = 1;
	transient @Nullable AmbientSoundCategory parentCategory;
	transient List<AmbientSoundCategory> children = new ArrayList<>();
	transient @Nullable AmbientSelection selection;

	@Override
	void init(AmbientEngine engine) {
		super.init(engine);
		parentCategory = parent == null ? null : engine.getSoundCategory(parent);
		if (parentCategory == this) {
			throw new IllegalStateException("Sound category cannot have itself as a parent " + name);
		}
		if (parentCategory != null) {
			parentCategory.children.add(this);
		}
	}

	void postInit(HashSet<String> remaining) {
		remaining.remove(name);
		for (AmbientSoundCategory child : children) {
			child.postInit(remaining);
		}
	}

	void tick(AmbientEnvironment env, @Nullable AmbientSelection parentSelection) {
		selection = value(env);
		if (selection != null) {
			if (parentSelection != null) {
				selection.last().subSelection = parentSelection;
			}
			selection.mulSetting(volumeSetting);
		}
		for (AmbientSoundCategory child : children) {
			child.tick(env, selection);
		}
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return this == o;
	}
}
