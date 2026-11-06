package com.flintclient.client.control.platform.ambient;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

/**
 * How a resource pack's file for an engine part combines with the one before it: replaces it, adds to its lists, or sets
 * the fields it names. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
enum AmbientStackType {
	overwrite {
		@Override
		void apply(Object holder, Field field, Object newHolder) {
			throw new UnsupportedOperationException();
		}
	},
	add {
		@Override
		void apply(Object holder, Field field, Object newHolder) throws IllegalAccessException {
			Object newValue = field.get(newHolder);
			if (newValue == null) {
				return;
			}
			Object original = field.get(holder);
			if (field.getType().isArray() && original != null) {
				int originalSize = Array.getLength(original);
				int newSize = Array.getLength(newValue);
				Object merged = Array.newInstance(field.getType().getComponentType(), originalSize + newSize);
				for (int i = 0; i < originalSize; i++) {
					Array.set(merged, i, Array.get(original, i));
				}
				for (int i = 0; i < newSize; i++) {
					Array.set(merged, originalSize + i, Array.get(newValue, i));
				}
				field.set(holder, merged);
			} else {
				field.set(holder, newValue);
			}
		}
	},
	set {
		@Override
		void apply(Object holder, Field field, Object newHolder) throws IllegalAccessException {
			Object newValue = field.get(newHolder);
			if (newValue != null) {
				field.set(holder, newValue);
			}
		}
	};

	abstract void apply(Object holder, Field field, Object newHolder) throws IllegalAccessException;
}
