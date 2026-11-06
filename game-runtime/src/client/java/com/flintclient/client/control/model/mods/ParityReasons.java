package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Why a setting of a module rebuilt on a real mod (BetterHurtCam, Zoomify, Custom Crosshair…) changes nothing as the
 * module is set now: the same rows the original's own screen greys out or hides. Asked by
 * {@code ModuleSettings.inactiveReason}; allocation-free.
 */
public final class ParityReasons {
	private ParityReasons() {
	}

	/** Loot Beams: the rows its own config leaves moot as set now. */
	private static @Nullable String lootBeams(ModuleState m, SettingDescriptor<?> s) {
		if (s == LookMods.LootBeams.FADE_IN_TIME || s == LookMods.LootBeams.FADE_DISTANCE || s == LookMods.LootBeams.RADIUS
			|| s == LookMods.LootBeams.HEIGHT || s == LookMods.LootBeams.Y_OFFSET || s == LookMods.LootBeams.ALPHA
			|| s == LookMods.LootBeams.COMMON_SHORTER || s == LookMods.LootBeams.GLOW || s == LookMods.LootBeams.DYNAMIC
			|| s == LookMods.LootBeams.SOUND) {
			return m.bool(LookMods.LootBeams.BEAM) ? null : "Turn on Beam first";
		}
		if (s == LookMods.LootBeams.GLOW_RADIUS) {
			return !m.bool(LookMods.LootBeams.BEAM) ? "Turn on Beam first" : m.bool(LookMods.LootBeams.GLOW) ? null : "Turn on Glow first";
		}
		if (s == LookMods.LootBeams.HALF_ROUND) {
			return !m.bool(LookMods.LootBeams.BEAM) ? "Turn on Beam first" : m.bool(LookMods.LootBeams.DYNAMIC) ? null : "Turn on Breathing beams first";
		}
		if (s == LookMods.LootBeams.SOUND_VOLUME || s == LookMods.LootBeams.SOUND_BLACKLIST || s == LookMods.LootBeams.SOUND_BLACKLIST_TAGS
			|| s == LookMods.LootBeams.SOUND_BLACKLIST_MODS) {
			return !m.bool(LookMods.LootBeams.BEAM) ? "Turn on Beam first" : m.bool(LookMods.LootBeams.SOUND) ? null : "Turn on Drop sound first";
		}
		if (s == LookMods.LootBeams.COLOR_BY_NAME || s == LookMods.LootBeams.COLOR_BY_TAG || s == LookMods.LootBeams.COLOR_BY_MOD) {
			return m.bool(LookMods.LootBeams.CUSTOM_COLOR) ? null : "Turn on Custom colors first";
		}
		if (s == LookMods.LootBeams.ONLY_RARE || s == LookMods.LootBeams.ONLY_EQUIPMENT || s == LookMods.LootBeams.WHITELIST
			|| s == LookMods.LootBeams.WHITELIST_TAGS || s == LookMods.LootBeams.WHITELIST_MODS || s == LookMods.LootBeams.EQUIPMENT_ITEMS
			|| s == LookMods.LootBeams.EQUIPMENT_TAGS || s == LookMods.LootBeams.EQUIPMENT_MODS || s == LookMods.LootBeams.NOT_EQUIPMENT) {
			if (m.bool(LookMods.LootBeams.ALL_ITEMS)) {
				return "All items is on";
			}
			boolean equipmentRow = s == LookMods.LootBeams.EQUIPMENT_ITEMS || s == LookMods.LootBeams.EQUIPMENT_TAGS
				|| s == LookMods.LootBeams.EQUIPMENT_MODS || s == LookMods.LootBeams.NOT_EQUIPMENT;
			return equipmentRow && !m.bool(LookMods.LootBeams.ONLY_EQUIPMENT) ? "Turn on Only equipment first" : null;
		}
		int info = m.option(LookMods.LootBeams.INFO);
		if (s == LookMods.LootBeams.INFO_ALL) {
			return info == 1 || info == 2 ? null : info == 0 ? "Loot information is None" : "Only for names over the item";
		}
		if (s == LookMods.LootBeams.SHOW_RARITY) {
			return info == 2 || info == 3 ? null : "Only when the rarity shows";
		}
		if (s == LookMods.LootBeams.ON_LOOK || s == LookMods.LootBeams.TEXT_BORDER || s == LookMods.LootBeams.STACK_COUNT
			|| s == LookMods.LootBeams.TEXT_ALPHA || s == LookMods.LootBeams.BACKGROUND_ALPHA || s == LookMods.LootBeams.NAMETAG_SCALE
			|| s == LookMods.LootBeams.NAMETAG_Y_OFFSET) {
			if (s == LookMods.LootBeams.STACK_COUNT && info == 3) {
				return null;
			}
			return info == 1 || info == 2 ? null : "Only for names over the item";
		}
		if (s == LookMods.LootBeams.LOOK_SENSITIVITY) {
			return info != 1 && info != 2 ? "Only for names over the item" : m.bool(LookMods.LootBeams.ON_LOOK) ? null
				: "Turn on Show when looked at first";
		}
		if (s == LookMods.LootBeams.TOOLTIP_CROUCH || s == LookMods.LootBeams.TOOLTIP_POSITION) {
			return info == 3 ? null : "Only in the Tooltip mode";
		}
		if (s == LookMods.LootBeams.TOOLTIP_X || s == LookMods.LootBeams.TOOLTIP_Y) {
			return info != 3 ? "Only in the Tooltip mode" : m.option(LookMods.LootBeams.TOOLTIP_POSITION) == 1 ? null : "Only at a Custom position";
		}
		return null;
	}

	/** Animations: Not Enough Animations' rows that wait for another one. */
	private static @Nullable String animations(ModuleState m, SettingDescriptor<?> s) {
		if (s == ToolMods.Animations.LADDER_ROTATE || s == ToolMods.Animations.LADDER_AMPLIFIER || s == ToolMods.Animations.LADDER_HEIGHT
			|| s == ToolMods.Animations.LADDER_SPEED) {
			return m.bool(ToolMods.Animations.LADDER) ? null : "Turn on Ladder animation first";
		}
		if (s == ToolMods.Animations.HORSE_LEGS) {
			return m.bool(ToolMods.Animations.HORSE) ? null : "Turn on Horse riding animation first";
		}
		if (s == ToolMods.Animations.BOW_LOCK) {
			return m.option(ToolMods.Animations.BOW) == 1 ? null : "Only with the Custom V1 bow";
		}
		if (s == ToolMods.Animations.NO_LEG_SMOOTHING || s == ToolMods.Animations.SMOOTHING_SPEED) {
			return m.bool(ToolMods.Animations.SMOOTHING) ? null : "Turn on Animation smoothing first";
		}
		if (s == ToolMods.Animations.MAX_NORMAL_ANGLE || s == ToolMods.Animations.MAX_BLOCKING_ANGLE) {
			return m.option(ToolMods.Animations.ROTATION_LOCK) == 0 ? null : "Only with Rotation lock None";
		}
		if (s == ToolMods.Animations.HOLD_UP_OFFSET || s == ToolMods.Animations.HOLD_UP_TARGET || s == ToolMods.Animations.HOLD_UP_CAMERA_OFFSET
			|| s == ToolMods.Animations.HOLD_UP_SELF || s == ToolMods.Animations.HOLDING_ITEMS) {
			int mode = m.option(ToolMods.Animations.HOLD_UP_MODE);
			if (mode == 2) {
				return "Holding up mode is No items";
			}
			if (s == ToolMods.Animations.HOLDING_ITEMS) {
				return mode == 1 ? "Holding up mode is All items" : null;
			}
			int target = m.option(ToolMods.Animations.HOLD_UP_TARGET);
			if (s == ToolMods.Animations.HOLD_UP_OFFSET) {
				return target == 0 ? null : "Only with Hold up target None";
			}
			if (s == ToolMods.Animations.HOLD_UP_CAMERA_OFFSET) {
				return target == 1 ? null : "Only with Hold up target Camera";
			}
			return null;
		}
		if (s == ToolMods.Animations.LANTERN_ITEMS) {
			return m.bool(ToolMods.Animations.LANTERNS) ? null : "Turn on Animate lanterns first";
		}
		if (s == ToolMods.Animations.BOW_ITEMS) {
			return m.bool(ToolMods.Animations.OFFHAND_HIDING) ? null : "Turn on Bow animation fixes first";
		}
		return null;
	}

	public static @Nullable String reason(ModuleState module, SettingDescriptor<?> setting) {
		if (LookMods.LootBeams.ID.equals(module.id())) {
			return lootBeams(module, setting);
		}
		if (ToolMods.Animations.ID.equals(module.id())) {
			return animations(module, setting);
		}
		if (setting == PlayerMods.HurtCam.MULTIPLIER || setting == PlayerMods.HurtCam.TYPE) {
			return module.bool(PlayerMods.HurtCam.ENABLED) ? null : "Turn on Hurtcam enabled first";
		}
		if (PlayerMods.Hitbox.ID.equals(module.id())) {
			return hitbox(module, setting);
		}
		if (setting == VisualMods.FallingLeaves.FALL_FACTOR || setting == VisualMods.FallingLeaves.WINTER_FACTOR) {
			// Falling Leaves reads the season from Fabric Seasons or Serene Seasons, neither of which this Minecraft has.
			return "Needs a seasons mod";
		}
		if (VisualMods.ModelGapFix.ID.equals(module.id())) {
			if (!com.flintclient.client.compat.McCompat.ITEM_MODEL_GAPS) {
				return "This Minecraft has no item model gaps to fix";
			}
			boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
			boolean macRow = setting == VisualMods.ModelGapFix.MAC_EXPANSION || setting == VisualMods.ModelGapFix.MAC_INDENT
				|| setting == VisualMods.ModelGapFix.MAC_SHRINK;
			return macRow == mac ? null : mac ? "On macOS the macOS rows apply" : "Only on macOS";
		}
		if (PlayerMods.Crosshair.ID.equals(module.id())) {
			return crosshair(module, setting);
		}
		if (setting == VisualMods.ParticleChanger.OPACITY || setting == VisualMods.ParticleChanger.OPACITY_DISTANCE) {
			return module.bool(VisualMods.ParticleChanger.OPACITY_ON) ? null : "Turn on Particle opacity first";
		}
		if (setting == VisualMods.ParticleChanger.EATING || setting == VisualMods.ParticleChanger.EFFECTS) {
			return module.integer(VisualMods.ParticleChanger.FILTER_DISTANCE) > 0 ? null : "Set a filter distance first";
		}
		if (setting == PlayerMods.Zoom.SCROLL_STEPS || setting == PlayerMods.Zoom.PER_STEP || setting == PlayerMods.Zoom.SCROLL_SMOOTHNESS
			|| setting == PlayerMods.Zoom.RETAIN) {
			return module.bool(PlayerMods.Zoom.SCROLL) ? null : "Turn on Enable scroll zoom first";
		}
		if (VisualMods.FogCustomizer.ID.equals(module.id())) {
			for (VisualMods.FogCustomizer.Config c : VisualMods.FogCustomizer.CONFIGS) {
				if (setting == c.type() || setting == c.linearStart() || setting == c.linearEnd() || setting == c.exp() || setting == c.exp2()) {
					if (!module.bool(c.enabled())) {
						return "Turn on Config enabled first";
					}
					int mode = module.option(c.type());
					if (setting == c.linearStart() || setting == c.linearEnd()) {
						return mode == 0 ? null : "Only in Linear mode";
					}
					if (setting == c.exp()) {
						return mode == 1 ? null : "Only in Exponential mode";
					}
					if (setting == c.exp2()) {
						return mode == 2 ? null : "Only in Exponential^2 mode";
					}
					return null;
				}
			}
			return null;
		}
		if (VisualMods.ItemPhysics.ID.equals(module.id())) {
			return itemPhysics(module, setting);
		}
		if (VisualMods.MotionBlur.ID.equals(module.id())) {
			return motionBlur(module, setting);
		}
		return null;
	}

	/**
	 * Motion Blur: the rows Natural Motion Blur's own screen greys out for the algorithm chosen (refresh rate scaling only for
	 * Velocity based; the profile for the velocity blur, which Hybrid blending also draws), and Flint's two velocity rows likewise.
	 */
	private static @Nullable String motionBlur(ModuleState m, SettingDescriptor<?> s) {
		int algorithm = m.option(VisualMods.MotionBlur.ALGORITHM);
		if (s == VisualMods.MotionBlur.REFRESH_SCALING) {
			return algorithm == 0 ? null : "Only for Velocity based";
		}
		if (s == VisualMods.MotionBlur.PROFILE || s == VisualMods.MotionBlur.QUALITY || s == VisualMods.MotionBlur.MOVEMENT) {
			return algorithm == 0 || algorithm == 2 ? null : "Only for Velocity based and Hybrid blending";
		}
		return null;
	}

	private static @Nullable String itemPhysics(ModuleState m, SettingDescriptor<?> s) {
		int style = m.option(VisualMods.ItemPhysics.STYLE);
		if (s == VisualMods.ItemPhysics.ROTATION_SPEED) {
			return style == 1 ? null : "Only for the Tumble style";
		}
		if (s == VisualMods.ItemPhysics.BLOCKS_ON_SIDE || s == VisualMods.ItemPhysics.SAME_HEADING || s == VisualMods.ItemPhysics.REALISTIC_SIZE
			|| s == VisualMods.ItemPhysics.LANDING_PARTICLES) {
			return style == 0 ? null : "Only for the Realistic style";
		}
		if (s == VisualMods.ItemPhysics.ONE_MODEL) {
			if (style != 0) {
				return "Only for the Realistic style";
			}
			return m.integer(VisualMods.ItemPhysics.STACK_COUNT) == 0 ? null : "Dropped stack item count decides";
		}
		if (s == VisualMods.ItemPhysics.STATIC) {
			return style == 2 ? null : "The physics style already stops the bob";
		}
		if (s == VisualMods.ItemPhysics.CUSTOM_ITEMS) {
			return m.decimal(VisualMods.ItemPhysics.CUSTOM_SCALE) != 0 ? null : "Set a custom item scale first";
		}
		return null;
	}

	private static @Nullable String crosshair(ModuleState m, SettingDescriptor<?> s) {
		if (s == PlayerMods.Crosshair.OUTLINE_COLOR || s == PlayerMods.Crosshair.OUTLINE_ALPHA) {
			return m.bool(PlayerMods.Crosshair.OUTLINE) ? null : "Turn on Enable outline first";
		}
		if (s == PlayerMods.Crosshair.DOT_COLOR || s == PlayerMods.Crosshair.DOT_ALPHA) {
			return m.bool(PlayerMods.Crosshair.DOT) ? null : "Turn on Enable dot first";
		}
		if (s == PlayerMods.Crosshair.HOSTILE_COLOR || s == PlayerMods.Crosshair.HOSTILE_ALPHA) {
			return m.bool(PlayerMods.Crosshair.HOSTILE) ? null : "Turn on hostile mob highlighting first";
		}
		if (s == PlayerMods.Crosshair.PASSIVE_COLOR || s == PlayerMods.Crosshair.PASSIVE_ALPHA) {
			return m.bool(PlayerMods.Crosshair.PASSIVE) ? null : "Turn on passive mob highlighting first";
		}
		if (s == PlayerMods.Crosshair.PLAYERS_COLOR || s == PlayerMods.Crosshair.PLAYERS_ALPHA) {
			return m.bool(PlayerMods.Crosshair.PLAYERS) ? null : "Turn on player highlighting first";
		}
		if (s == PlayerMods.Crosshair.COOLDOWN_COLOR || s == PlayerMods.Crosshair.COOLDOWN_ALPHA) {
			return m.bool(PlayerMods.Crosshair.ITEM_COOLDOWN) ? null : "Turn on the item cooldown indicator first";
		}
		if (s == PlayerMods.Crosshair.RAINBOW_SPEED) {
			return m.bool(PlayerMods.Crosshair.RAINBOW) ? null : "Turn on Enable rainbow first";
		}
		return null;
	}

	/** Chat Heads' rows: all need Player heads; the line-up only works Before Line, smart guessing only with both detections. */
	public static @Nullable String chatHeads(ModuleState m, SettingDescriptor<?> s) {
		if (s != UtilityMods.Chat.HEAD_POSITION && s != UtilityMods.Chat.THREE_D && s != UtilityMods.Chat.ALIGN && s != UtilityMods.Chat.SENDER_DETECTION
			&& s != UtilityMods.Chat.SMART_HEURISTICS && s != UtilityMods.Chat.SYSTEM_MESSAGES && s != UtilityMods.Chat.HEAD_SHADOW
			&& s != UtilityMods.Chat.HEAD_PADDING && s != UtilityMods.Chat.NAME_ALIASES && s != UtilityMods.Chat.DETECT_ALIASES
			&& s != UtilityMods.Chat.ROUND_AVATARS) {
			return null;
		}
		if (!m.bool(UtilityMods.Chat.AVATARS)) {
			return "Turn on Player heads first";
		}
		if (s == UtilityMods.Chat.ALIGN) {
			return m.option(UtilityMods.Chat.HEAD_POSITION) == 0 ? null : "Only with Render position Before Line";
		}
		if (s == UtilityMods.Chat.SMART_HEURISTICS) {
			return m.option(UtilityMods.Chat.SENDER_DETECTION) == 1 ? null : "Only with UUID and heuristic";
		}
		return null;
	}

	private static @Nullable String hitbox(ModuleState m, SettingDescriptor<?> s) {
		if (s == PlayerMods.Hitbox.EYE_COLOR || s == PlayerMods.Hitbox.EYE_OPACITY) {
			return m.bool(PlayerMods.Hitbox.EYE_LEVEL) ? null : "Turn on Render eye height first";
		}
		if (s == PlayerMods.Hitbox.LOOK_LINE || s == PlayerMods.Hitbox.LOOK_COLOR || s == PlayerMods.Hitbox.LOOK_OPACITY) {
			return m.bool(PlayerMods.Hitbox.LOOK) ? null : "Turn on Render look direction first";
		}
		if (s == PlayerMods.Hitbox.TARGET_COLOR || s == PlayerMods.Hitbox.TARGET_OPACITY) {
			return m.bool(PlayerMods.Hitbox.TARGET) ? null : "Turn on Target hitbox color first";
		}
		if (s == PlayerMods.Hitbox.DAMAGED_COLOR || s == PlayerMods.Hitbox.DAMAGED_OPACITY) {
			return m.bool(PlayerMods.Hitbox.DAMAGED) ? null : "Turn on Hitbox hurt first";
		}
		if (s == PlayerMods.Hitbox.OUTLINE_COLOR || s == PlayerMods.Hitbox.OUTLINE_OPACITY || s == PlayerMods.Hitbox.OUTLINE_SIZE) {
			return m.bool(PlayerMods.Hitbox.OUTLINE) ? null : "Turn on Outline first";
		}
		if (s == PlayerMods.Hitbox.HITTABLE_COLOR) {
			return m.bool(PlayerMods.Hitbox.HITTABLE) ? null : "Turn on Color in reach first";
		}
		if (s == PlayerMods.Hitbox.DISTANCE) {
			return m.bool(PlayerMods.Hitbox.LIMIT) ? null : "Turn on Max showable distance first";
		}
		return null;
	}
}
