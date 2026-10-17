package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.WaypointStore;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.TextField;
import java.util.List;
import java.util.Locale;

/**
 * The WAYPOINTS tab: this world's (or server's) waypoints, every dimension, newest last. "Add here" marks where you
 * stand; each row edits the name, colour, coordinates and visibility, and the bin deletes it after a second press. The
 * Waypoints module draws the markers and has the keys; this page only edits the list, which is saved at once, per world,
 * on this computer.
 */
final class WaypointsPage extends Node {
	private static final float ROW_H = 156;
	private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
	private static final String[] DIMENSION_NAMES = {"Overworld", "Nether", "End"};
	private static final float HEADER = 64;

	private final MenuWindow window;
	private final PageControls.TextButton add;
	private final PageControls.TextButton settings;
	private final Label title = new Label(Type.SENTENCE, "");
	private final Label empty = new Label(Type.EMPTY, "");
	private final Label emptyNote = new Label(Type.EMPTY_NOTE, "");
	private final Rows rows = new Rows();
	private final ScrollView scroll;
	private final com.flintclient.client.ui.kit.SearchField search = add(new com.flintclient.client.ui.kit.SearchField("Search waypoints", 40));
	private @org.jspecify.annotations.Nullable FontFace searchFace;
	private String builtQuery = "";
	private int builtRevision = -1;
	private int confirm = -1;
	private long confirmSince;

	WaypointsPage(MenuWindow window) {
		this.window = window;
		add = add(new PageControls.TextButton("Add here", Icon.PLUS, PageControls.Look.ACCENT, this::addHere));
		settings = add(new PageControls.TextButton("Waypoint settings", Icon.GEAR, PageControls.Look.PLAIN,
			() -> window.openPage(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID)));
		scroll = add(new ScrollView(rows));
		search.name("Search waypoints");
		search.onChange(text -> rebuild());
	}

	private static WaypointStore store() {
		return Features.waypoints();
	}

	private void addHere() {
		int index = Mc263Features.addWaypointHere(store().nextName("Waypoint"), 0xFF8139);
		if (index >= 0) {
			rebuild();
			scroll.model().scrollTo(Float.MAX_VALUE, false);
		}
	}

	/** Esc: nothing of its own to close (the window goes back to MODS). */
	boolean escape() {
		return false;
	}

	private void rebuild() {
		builtRevision = store().revision();
		builtQuery = search.text();
		while (rows.childCount() > 0) {
			rows.remove(rows.child(0));
		}
		List<Waypoint> all = store().all();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).matches(builtQuery)) {
				rows.add(new Row(i));
			}
		}
		invalidate();
	}

	private boolean editing() {
		KitScreen screen = screen();
		if (screen == null || screen.focused() == null) {
			return false;
		}
		for (Node n = screen.focused(); n != null; n = n.parent()) {
			if (n == rows) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void layout() {
		float bar = r(HEADER);
		float at = x + w;
		at -= settings.prefWidth();
		settings.setBounds(at, y, settings.prefWidth(), bar);
		at -= r(12) + add.prefWidth();
		add.setBounds(at, y, add.prefWidth(), bar);
		float searchW = Math.min(r(420), Math.max(r(200), (at - x) * 0.45F));
		search.setBounds(at - r(12) - searchW, y, searchW, bar);
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(15),
				r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}
		boolean world = Mc263Features.dimension() != null;
		if (world) {
			add.setEnabled(true);
		} else {
			add.setDisabled("Join a world to add waypoints");
		}
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, y + bar + r(30), w, Math.max(0, h - bar - r(30)));
	}

	@Override
	protected void paint(Canvas c) {
		if (store().revision() != builtRevision && !editing()) {
			rebuild();
		}
		if (confirm >= 0 && System.currentTimeMillis() - confirmSince > 3000) {
			confirm = -1;
		}
		String dimension = Mc263Features.dimension();
		title.set(dimension == null ? "Waypoints are kept per world" : "Waypoints in this world · you're in " + nice(dimension));
		float cy = y + r(HEADER) / 2;
		MenuStyle.icon(c, Icon.INFO, x + r(15), cy, r(30), MenuStyle.INFO);
		MenuStyle.fitted(c, title, x + r(44), cy, MenuStyle.SENTENCE_TEXT, search.x() - r(60) - x);
		if (rows.childCount() == 0) {
			empty.set(dimension == null ? "Join a world to see its waypoints" : !store().all().isEmpty() ? "No waypoint matches the search"
				: "No waypoints in this world yet");
			emptyNote.set(dimension == null ? "Each world and server keeps its own list." : "Add here marks where you stand. So does the Create key ("
				+ "B unless you change it) while Waypoints is on.");
			float mid = scroll.y() + Math.min(scroll.height(), r(500)) / 2;
			MenuStyle.centered(c, empty, x + w / 2, mid - r(22), MenuStyle.EMPTY_TEXT, w - r(80));
			MenuStyle.centered(c, emptyNote, x + w / 2, mid + r(26), MenuStyle.EMPTY_NOTE, w - r(80));
		}
	}

	static int dimensionIndex(String dimension) {
		for (int i = 0; i < DIMENSIONS.length; i++) {
			if (DIMENSIONS[i].equals(dimension)) {
				return i;
			}
		}
		return 0;
	}

	/** "minecraft:the_nether" → "the Nether". */
	static String nice(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "the Overworld";
			case "minecraft:the_nether" -> "the Nether";
			case "minecraft:the_end" -> "the End";
			default -> dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
		};
	}

	private static final class Rows extends Node {
		@Override
		public float prefHeight(float width) {
			int n = childCount();
			return n == 0 ? 0 : n * r(ROW_H) + (n - 1) * r(12) + r(20);
		}

		@Override
		protected void layout() {
			for (int i = 0; i < childCount(); i++) {
				child(i).setBounds(x, y + i * (r(ROW_H) + r(12)), w - r(20), r(ROW_H));
			}
		}
	}

	/** One waypoint's row. */
	private final class Row extends Node {
		private final int index;
		private final PageControls.ColorSwatch swatch;
		private final TextField name;
		private final TextField[] coords = new TextField[3];
		private final PageControls.Switch visible;
		private final Bin bin;
		private final PageControls.TextButton[] shows = new PageControls.TextButton[4];
		private final PageControls.Stepper dimension;
		private final TextField group;
		private final PageControls.TextButton here;
		private final Label where = new Label(Type.ROW_NOTE, "");
		private FontFace face;

		Row(int index) {
			this.index = index;
			Waypoint wp = store().all().get(index);
			swatch = add(new PageControls.ColorSwatch(() -> current().rgb(), () -> window.openColorPicker(swatch(), "Waypoint color", 0xFF8139,
				() -> current().rgb(), v -> store().set(index, current().withColor(v)))));
			name = add(new TextField("Name", Waypoint.MAX_NAME));
			name.setText(wp.name());
			name.onChange(text -> {
				if (!text.isBlank()) {
					store().set(index, current().withName(text));
				}
			});
			int[] values = {wp.x(), wp.y(), wp.z()};
			String[] axes = {"X", "Y", "Z"};
			for (int k = 0; k < 3; k++) {
				int axis = k;
				TextField field = add(new TextField(axes[k], 9));
				field.setText(Integer.toString(values[k]));
				field.onChange(text -> {
					try {
						int v = Integer.parseInt(text.strip());
						Waypoint now = current();
						store().set(index, now.withPosition(axis == 0 ? v : now.x(), axis == 1 ? v : now.y(), axis == 2 ? v : now.z()));
					} catch (NumberFormatException ignored) {
						// Half-typed ("-"): kept until it's a number.
					}
				});
				field.name(axes[k] + " coordinate");
				coords[k] = field;
			}
			visible = add(new PageControls.Switch(() -> current().visible(), () -> store().set(index, current().withVisible(!current().visible()))));
			visible.name("Show " + wp.name());
			bin = add(new Bin());
			name.name("Waypoint name");
			String[] labels = {"Name", "Distance", "Beam", "Highlight"};
			for (int k = 0; k < 4; k++) {
				int which = k;
				shows[k] = add(new PageControls.TextButton(labels[k], null, PageControls.Look.PLAIN, () -> {
					Waypoint now = current();
					boolean[] f = {now.showText(), now.showDistance(), now.beam(), now.highlight()};
					f[which] = !f[which];
					store().set(index, now.withShows(f[0], f[1], f[2], f[3]));
				}).active(() -> {
					Waypoint now = current();
					return switch (which) {
						case 0 -> now.showText();
						case 1 -> now.showDistance();
						case 2 -> now.beam();
						default -> now.highlight();
					};
				}));
				shows[k].name((k == 0 ? "Show name" : k == 1 ? "Show distance" : k == 2 ? "Show beam" : "Highlight block") + " of " + wp.name());
			}
			dimension = add(new PageControls.Stepper(DIMENSION_NAMES, () -> dimensionIndex(current().dimension()),
				i -> store().set(index, current().withDimension(DIMENSIONS[i]))));
			dimension.name("Dimension of " + wp.name());
			group = add(new TextField("Group", Waypoint.MAX_GROUP));
			group.setText(wp.group());
			group.name("Group of " + wp.name());
			group.onChange(text -> store().set(index, current().withGroup(text)));
			here = add(new PageControls.TextButton("Here", Icon.PIN, PageControls.Look.PLAIN, this::setHere));
			here.name("Move " + wp.name() + " to where you stand");
		}

		private void setHere() {
			String now = Mc263Features.dimension();
			int[] at = Mc263Features.blockPosition();
			if (now == null || at == null) {
				return;
			}
			store().set(index, current().withPosition(at[0], at[1], at[2]).withDimension(now));
			coords[0].setText(Integer.toString(at[0]));
			coords[1].setText(Integer.toString(at[1]));
			coords[2].setText(Integer.toString(at[2]));
		}

		private PageControls.ColorSwatch swatch() {
			return swatch;
		}

		private Waypoint current() {
			List<Waypoint> all = store().all();
			return index < all.size() ? all.get(index) : new Waypoint("Waypoint", 0, 0, 0, "minecraft:overworld", 0xFF8139, false);
		}

		@Override
		protected void layout() {
			FontFace now = Type.VALUE.face();
			if (now != face) {
				face = now;
				TextField.Look look = new TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
					r(12), r(16), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL);
				name.look(look);
				group.look(look);
				for (TextField field : coords) {
					field.look(look);
				}
			}
			// Second line: what it shows, its group, its dimension, and Here.
			float ly = y + h - r(20) - r(52);
			float lx = x + r(24);
			for (PageControls.TextButton show : shows) {
				float bw = show.prefWidth();
				show.setBounds(lx, ly, bw, r(52));
				lx += bw + r(8);
			}
			float rx = x + w - r(24);
			here.setBounds(rx - here.prefWidth(), ly, here.prefWidth(), r(52));
			rx -= here.prefWidth() + r(12);
			float dw = Math.min(r(300), Math.max(r(200), (rx - lx) * 0.4F));
			dimension.setBounds(rx - dw, ly, dw, r(52));
			rx -= dw + r(12);
			group.setBounds(lx + r(8), ly, Math.max(r(120), rx - lx - r(8)), r(52));
			float cy = y + r(20) + r(26);
			float left = x + r(24);
			swatch.setBounds(left, cy - r(24), r(48) + r(14) + r(118), r(48));
			left += swatch.width() + r(16);
			float right = x + w - r(24);
			bin.setBounds(right - r(52), cy - r(26), r(52), r(52));
			right -= r(52) + r(16);
			visible.setBounds(right - r(PageControls.Switch.W), cy - r(PageControls.Switch.H) / 2, r(PageControls.Switch.W), r(PageControls.Switch.H));
			right -= r(PageControls.Switch.W) + r(24);
			float coordW = r(130);
			for (int k = 2; k >= 0; k--) {
				coords[k].setBounds(right - coordW, cy - r(26), coordW, r(52));
				right -= coordW + r(10);
			}
			name.setBounds(left, cy - r(26), Math.max(r(160), right - r(14) - left), r(52));
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.framed(c, x, y, w, h, r(18), MenuStyle.SECTION_CARD, index == confirm ? Palette.withAlpha(MenuStyle.DANGER, 0.6F)
				: MenuStyle.SECTION_EDGE);
			where.set(nice(current().dimension()));
			MenuStyle.fitted(c, where, name.x() + r(4), name.y() + name.height() + r(12), MenuStyle.LABEL, name.width());
		}

		/** Delete, after a second press within three seconds. */
		private final class Bin extends Pressable {
			Bin() {
				super(null);
				setAction(() -> {
					if (confirm == index) {
						confirm = -1;
						store().remove(index);
						rebuild();
					} else {
						confirm = index;
						confirmSince = System.currentTimeMillis();
					}
				});
				name("Delete waypoint");
				tooltip("Delete (press twice)");
			}

			@Override
			protected void paint(Canvas c) {
				boolean asking = confirm == index;
				MenuStyle.box(c, x, y, w, h, r(12), asking ? Palette.withAlpha(MenuStyle.DANGER, 0.35F) : Palette.lerp(MenuStyle.CHIP,
					MenuStyle.CHIP_HOVER, hover));
				MenuStyle.icon(c, Icon.TRASH, x + w / 2, y + h / 2, r(26), asking ? MenuStyle.DANGER_TEXT : MenuStyle.CHIP_TEXT);
				MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
			}
		}
	}

	@Override
	public String toString() {
		return "WaypointsPage[" + store().all().size() + "]".toLowerCase(Locale.ROOT);
	}
}
