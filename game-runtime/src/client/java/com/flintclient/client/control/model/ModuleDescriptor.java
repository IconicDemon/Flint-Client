package com.flintclient.client.control.model;

import com.flintclient.client.control.hud.HudPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything fixed about one HUD module: its names for the library, its settings (in sections, in display
 * order), where it goes by default and what it needs before it can show anything. A module's changeable
 * state lives in {@link ModuleState}.
 */
public final class ModuleDescriptor {
	/** What a module needs from the game to have something to show. */
	public enum Requirement {
		/** Works everywhere (FPS, CPS, keystrokes). */
		NONE,
		/** Reads the player, so only in a world. */
		WORLD,
		/** Shows the server-reported latency: multiplayer with Flint Network Core running. */
		SERVER_LATENCY
	}

	/** Whether the module is a widget on the HUD (placed, moved in the HUD editor) or changes the game some other way. */
	public enum Kind {
		HUD,
		FEATURE
	}

	/**
	 * How much of the module exists, said on its card and page. Only {@link #WORKING} and {@link #PARTIAL} modules can be
	 * turned on; the others show their page with the reason, never settings that would do nothing.
	 */
	public enum Status {
		/** Real behaviour; every setting on its page applies. */
		WORKING,
		/** Real behaviour for the settings shown; the note says what the reference had that isn't built yet. */
		PARTIAL,
		/** Not built yet: the page says what it will do. */
		NOT_BUILT,
		/** Needs a permission or service Flint doesn't have; the page says which. */
		BLOCKED;

		public boolean usable() {
			return this == WORKING || this == PARTIAL;
		}
	}

	private final String id;
	private final String name;
	private final String description;
	private final List<String> keywords;
	private final IconId icon;
	private final Category category;
	private final List<SettingSection> sections;
	private final List<SettingDescriptor<?>> settings;
	private final HudPlacement defaultPlacement;
	private final Requirement requirement;
	private final @Nullable String note;
	private final Kind kind;
	private final Status status;
	private final @Nullable String statusNote;
	private @Nullable BoxStyle boxStyle;

	public ModuleDescriptor(String id, String name, String description, List<String> keywords, IconId icon, Category category,
		List<SettingSection> sections, HudPlacement defaultPlacement, Requirement requirement, @Nullable String note) {
		this(id, name, description, keywords, icon, category, sections, defaultPlacement, requirement, note, Kind.HUD, Status.WORKING, null);
	}

	public ModuleDescriptor(String id, String name, String description, List<String> keywords, IconId icon, Category category,
		List<SettingSection> sections, HudPlacement defaultPlacement, Requirement requirement, @Nullable String note, Kind kind, Status status,
		@Nullable String statusNote) {
		this.id = Objects.requireNonNull(id);
		this.name = Objects.requireNonNull(name);
		this.description = Objects.requireNonNull(description);
		this.keywords = List.copyOf(keywords);
		this.icon = Objects.requireNonNull(icon);
		this.category = Objects.requireNonNull(category);
		this.sections = List.copyOf(sections);
		List<SettingDescriptor<?>> all = new ArrayList<>();
		for (SettingSection section : this.sections) {
			for (SettingDescriptor<?> setting : section.settings()) {
				for (SettingDescriptor<?> other : all) {
					if (other.id().equals(setting.id())) {
						throw new IllegalArgumentException("duplicate setting " + setting.id() + " in " + id);
					}
				}
				all.add(setting);
			}
		}
		this.settings = List.copyOf(all);
		this.defaultPlacement = Objects.requireNonNull(defaultPlacement);
		this.requirement = Objects.requireNonNull(requirement);
		this.note = note;
		this.kind = Objects.requireNonNull(kind);
		this.status = Objects.requireNonNull(status);
		this.statusNote = statusNote;
		if (!status.usable() && statusNote == null || status == Status.PARTIAL && statusNote == null) {
			throw new IllegalArgumentException(id + " needs a status note");
		}
	}

	/** Starts the description of a module ({@link Builder}). */
	public static Builder builder(String id, String name, String description) {
		return new Builder(id, name, description);
	}

	/** The boxed-counter look this module uses (its settings are among the module's), or null. */
	public @Nullable BoxStyle boxStyle() {
		return boxStyle;
	}

	public Kind kind() {
		return kind;
	}

	/** Whether it is a HUD widget (has a placement and shows in the HUD editor). */
	public boolean hud() {
		return kind == Kind.HUD;
	}

	public Status status() {
		return status;
	}

	/** What isn't built, or why it can't be (for PARTIAL, NOT_BUILT and BLOCKED); null for WORKING. */
	public @Nullable String statusNote() {
		return statusNote;
	}

	/** The key in layout files (see {@link ModuleId}). */
	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	/** One line for the library card. */
	public String description() {
		return description;
	}

	/** Extra search words (not shown). */
	public List<String> keywords() {
		return keywords;
	}

	public IconId icon() {
		return icon;
	}

	public Category category() {
		return category;
	}

	public List<SettingSection> sections() {
		return sections;
	}

	/** Every setting, in display order. */
	public List<SettingDescriptor<?>> settings() {
		return settings;
	}

	public HudPlacement defaultPlacement() {
		return defaultPlacement;
	}

	public Requirement requirement() {
		return requirement;
	}

	/** A longer explanation for the settings panel (how the data is gathered), or null. */
	public @Nullable String note() {
		return note;
	}

	/** The setting with this id, or null. */
	public @Nullable SettingDescriptor<?> setting(String settingId) {
		for (int i = 0; i < settings.size(); i++) {
			if (settings.get(i).id().equals(settingId)) {
				return settings.get(i);
			}
		}
		return null;
	}

	/**
	 * The position of {@code setting} in {@link #settings()}, or -1. Only the module's own constant counts: another
	 * module's setting with the same id (Coordinates' and Armor's {@code "layout"}) is a mistake, not a match.
	 */
	public int indexOf(SettingDescriptor<?> setting) {
		for (int i = 0; i < settings.size(); i++) {
			if (settings.get(i) == setting) {
				return i;
			}
		}
		return -1;
	}

	public Availability availability(ControlContext context) {
		return availability(context.inWorld(), context.multiplayer(), context.latencyKnown(), context.networkCoreRunning());
	}

	/** As {@link #availability(ControlContext)}, without making a context (allocation-free). */
	public Availability availability(boolean inWorld, boolean multiplayer, boolean latencyKnown, boolean networkCoreRunning) {
		return switch (requirement) {
			case NONE -> Availability.AVAILABLE;
			case WORLD -> inWorld ? Availability.AVAILABLE : Availability.NEEDS_WORLD;
			case SERVER_LATENCY -> {
				// Off a server nothing would help, so don't suggest turning Network Core on.
				if (!multiplayer) {
					yield Availability.SERVERS_ONLY;
				}
				if (!networkCoreRunning) {
					yield Availability.NETWORK_CORE_OFF;
				}
				yield latencyKnown ? Availability.AVAILABLE : Availability.WAITING_FOR_SERVER;
			}
		};
	}

	@Override
	public String toString() {
		return "ModuleDescriptor[" + id + "]";
	}

	/** Builds a {@link ModuleDescriptor}; HUD modules call {@link #hud}, the rest are features. */
	public static final class Builder {
		private final String id;
		private final String name;
		private final String description;
		private List<String> keywords = List.of();
		private IconId icon = IconId.GAUGE;
		private Category category = Category.UTILITY;
		private final List<SettingSection> sections = new ArrayList<>();
		private HudPlacement placement = HudPlacement.of(com.flintclient.client.control.hud.Anchor.TOP_LEFT, 4, 4);
		private Requirement requirement = Requirement.NONE;
		private @Nullable String note;
		private Kind kind = Kind.FEATURE;
		private Status status = Status.WORKING;
		private @Nullable String statusNote;
		private @Nullable BoxStyle style;

		private Builder(String id, String name, String description) {
			this.id = id;
			this.name = name;
			this.description = description;
		}

		public Builder keywords(String... words) {
			keywords = List.of(words);
			return this;
		}

		public Builder icon(IconId value) {
			icon = value;
			return this;
		}

		public Builder category(Category value) {
			category = value;
			return this;
		}

		public Builder section(SettingSection section) {
			sections.add(section);
			return this;
		}

		public Builder section(String title, SettingDescriptor<?>... settings) {
			return section(SettingSection.of(title, settings));
		}

		public Builder advanced(String title, SettingDescriptor<?>... settings) {
			return section(SettingSection.advanced(title, settings));
		}

		/** A HUD widget that starts at {@code at}. */
		public Builder hud(HudPlacement at) {
			kind = Kind.HUD;
			placement = at;
			return this;
		}

		public Builder needs(Requirement value) {
			requirement = value;
			return this;
		}

		/** A longer explanation of where the data comes from. */
		public Builder note(String value) {
			note = value;
			return this;
		}

		/** Uses {@code value}'s look: its "Style" section first (with {@code extra} after), its details folded at the end. */
		public Builder style(BoxStyle value) {
			style = value;
			return this;
		}

		public Builder partial(String whatIsMissing) {
			status = Status.PARTIAL;
			statusNote = whatIsMissing;
			return this;
		}

		public Builder notBuilt(String why) {
			status = Status.NOT_BUILT;
			statusNote = why;
			return this;
		}

		public Builder blocked(String why) {
			status = Status.BLOCKED;
			statusNote = why;
			return this;
		}

		public ModuleDescriptor build() {
			ModuleDescriptor made = new ModuleDescriptor(id, name, description, keywords, icon, category, sections, placement, requirement, note, kind,
				status, statusNote);
			made.boxStyle = style;
			return made;
		}
	}
}
