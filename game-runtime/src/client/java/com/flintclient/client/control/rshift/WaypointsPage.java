package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.WaypointStore;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.World;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.WaypointBadge;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.TextField;
import java.util.List;
import java.util.Locale;

/**
 * The WAYPOINTS tab: this world's (or server's) waypoints, every dimension, in the Waypoints module's sort order
 * (Proximity: nearest first, this dimension before the others; Alphabetical; Custom: as made). "Add here" marks the block
 * you stand on. Each row shows the waypoint's badge, name, coordinates, dimension and distance, with its switch, Edit and
 * the bin (a second press deletes); Edit unfolds the name, colour, coordinates, what it shows, its dimension and set. The
 * list is saved at once, per world, on this computer.
 */
final class WaypointsPage extends Node {
	/** A row folded, and the editor Edit unfolds under it. */
	private static final float ROW_H = 124;
	private static final float EDIT_H = 148;
	private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
	private static final String[] DIMENSION_NAMES = {"Overworld", "Nether", "End"};
	private static final float HEADER = 64;
	/** The page's padding inside the window (as the settings pages'), and above its toolbar. */
	private static final float PAD = 40;
	private static final float TOP = 30;

	private final Host window;
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
	private int open = -1;
	private long confirmSince;

	WaypointsPage(Host window) {
		this.window = window;
		add = add(new PageControls.TextButton("Add here", Glyph.PLUS, PageControls.Look.ACCENT, this::addHere));
		settings = add(new PageControls.TextButton("Waypoint settings", Glyph.GEAR, PageControls.Look.PLAIN,
			() -> window.openModule(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID)));
		scroll = add(new ScrollView(rows));
		search.name("Search waypoints");
		search.onChange(text -> rebuild());
	}

	private static WaypointStore store() {
		return Features.waypoints();
	}

	private void addHere() {
		int index = World.addWaypointHere(store().nextName("Waypoint"), World.nextWaypointColor());
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
		java.util.List<Integer> order = new java.util.ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).matches(builtQuery)) {
				order.add(i);
			}
		}
		int sort = com.flintclient.client.control.feature.Mods.state(com.flintclient.client.control.feature.Mods.index(
			com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID)).option(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.SORT);
		String here = World.dimension();
		double[] you = World.position();
		if (sort == 0 && here != null && you != null) {
			order.sort(java.util.Comparator.comparingDouble(i -> {
				Waypoint w = all.get(i);
				return w.dimension().equals(here) ? w.distanceTo(you[0], you[1], you[2]) : 1e12 + i;
			}));
		} else if (sort == 1) {
			order.sort(java.util.Comparator.comparing(i -> all.get(i).name().toLowerCase(Locale.ROOT)));
		}
		for (int i : order) {
			rows.add(new Row(i));
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
		// Inside the window's padding, as every other page.
		float px = x + r(PAD);
		float py = y + r(TOP);
		float bar = r(HEADER);
		float at = x + w - r(PAD);
		at -= settings.prefWidth();
		settings.setBounds(at, py, settings.prefWidth(), bar);
		at -= r(12) + add.prefWidth();
		add.setBounds(at, py, add.prefWidth(), bar);
		float searchW = Math.min(r(420), Math.max(r(200), (at - px) * 0.45F));
		search.setBounds(at - r(12) - searchW, py, searchW, bar);
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(15),
				r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}
		boolean world = World.dimension() != null;
		if (world) {
			add.setEnabled(true);
		} else {
			add.setDisabled("Join a world to add waypoints");
		}
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		// The rows end at the padding; the scroll bar sits in it.
		scroll.setBounds(px, py + bar + r(30), w - 2 * r(PAD) + r(20), Math.max(0, h - r(TOP) - bar - r(30)));
	}

	@Override
	protected void paint(Canvas c) {
		if (store().revision() != builtRevision && !editing()) {
			rebuild();
		}
		if (confirm >= 0 && System.currentTimeMillis() - confirmSince > 3000) {
			confirm = -1;
		}
		String dimension = World.dimension();
		title.set(dimension == null ? "Waypoints are kept per world" : "Waypoints in this world · you're in " + nice(dimension));
		float px = x + r(PAD);
		float cy = y + r(TOP) + r(HEADER) / 2;
		Glyph.INFO.draw(c, px + r(15), cy, r(30), MenuStyle.NEUTRAL);
		MenuStyle.fitted(c, title, px + r(44), cy, MenuStyle.SENTENCE_TEXT, search.x() - r(60) - px);
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
			float total = 0;
			for (int i = 0; i < n; i++) {
				total += ((Row) child(i)).rowHeight() + r(10);
			}
			return n == 0 ? 0 : total - r(10) + r(20);
		}

		@Override
		protected void layout() {
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				float rh = ((Row) child(i)).rowHeight();
				child(i).setBounds(x, at, w - r(20), rh);
				at += rh + r(10);
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
		private final PageControls.TextButton edit;
		private final Label title = new Label(Type.ROW_LABEL, "");
		private final Label where = new Label(Type.ROW_NOTE, "");
		private final Label far = new Label(Type.VALUE, "");
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
			String[] labels = {"Label", "Distance", "Beam", "Block"};
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
				shows[k].name((k == 0 ? "Show label" : k == 1 ? "Show distance" : k == 2 ? "Show beam" : "Highlight block") + " of " + wp.name());
			}
			dimension = add(new PageControls.Stepper(DIMENSION_NAMES, () -> dimensionIndex(current().dimension()),
				i -> store().set(index, current().withDimension(DIMENSIONS[i]))));
			dimension.name("Dimension of " + wp.name());
			group = add(new TextField("Group", Waypoint.MAX_GROUP));
			group.setText(wp.group());
			group.name("Group of " + wp.name());
			group.onChange(text -> store().set(index, current().withGroup(text)));
			here = add(new PageControls.TextButton("Here", Glyph.PIN, PageControls.Look.PLAIN, this::setHere));
			here.name("Move " + wp.name() + " to where you stand");
			edit = add(new PageControls.TextButton("Edit", Glyph.PEN, PageControls.Look.PLAIN, () -> {
				open = open == index ? -1 : index;
				WaypointsPage.this.invalidate();
				rows.invalidate();
			}).active(() -> open == index));
			edit.name("Edit " + wp.name());
		}

		float rowHeight() {
			return r(ROW_H) + (open == index ? r(EDIT_H) : 0);
		}

		private void setHere() {
			String now = World.dimension();
			int[] at = World.blockPosition();
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
			boolean editing = open == index;
			// The folded row: badge and words on the left; distance, switch, Edit and the bin on the right.
			float cy = y + r(ROW_H) / 2;
			float right = x + w - r(24);
			bin.setBounds(right - r(52), cy - r(26), r(52), r(52));
			right -= r(52) + r(12);
			edit.setBounds(right - edit.prefWidth(), cy - r(26), edit.prefWidth(), r(52));
			right -= edit.prefWidth() + r(20);
			visible.setBounds(right - r(PageControls.Switch.W), cy - r(PageControls.Switch.H) / 2, r(PageControls.Switch.W), r(PageControls.Switch.H));
			for (Node n : new Node[] {swatch, name, coords[0], coords[1], coords[2], shows[0], shows[1], shows[2], shows[3], dimension, group, here}) {
				n.setVisible(editing);
			}
			if (!editing) {
				return;
			}
			// The editor: name, X Y Z and colour; then what it shows, its set, its dimension and Here.
			float ey = y + r(ROW_H) + r(4);
			float left = x + r(24);
			float end = x + w - r(24);
			swatch.setBounds(left, ey, r(48) + r(14) + r(230), r(52));
			float coordRight = end;
			float coordW = r(130);
			for (int k = 2; k >= 0; k--) {
				coords[k].setBounds(coordRight - coordW, ey, coordW, r(52));
				coordRight -= coordW + r(10);
			}
			float nameLeft = left + swatch.width() + r(28);
			name.setBounds(nameLeft, ey, Math.max(r(160), coordRight - r(6) - nameLeft), r(52));
			float ly = ey + r(52) + r(16);
			float lx = left;
			for (PageControls.TextButton show : shows) {
				float bw = show.prefWidth();
				show.setBounds(lx, ly, bw, r(52));
				lx += bw + r(8);
			}
			float rx = end;
			here.setBounds(rx - here.prefWidth(), ly, here.prefWidth(), r(52));
			rx -= here.prefWidth() + r(12);
			float dw = Math.min(r(300), Math.max(r(200), (rx - lx) * 0.4F));
			dimension.setBounds(rx - dw, ly, dw, r(52));
			rx -= dw + r(12);
			group.setBounds(lx + r(8), ly, Math.max(r(120), rx - lx - r(8)), r(52));
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.block(c, x, y, w, h, MenuStyle.radius(), 0, 0);
			Waypoint wp = current();
			float cy = y + r(ROW_H) / 2;
			float badge = r(56);
			float bx = x + r(24) + badge / 2;
			WaypointBadge.draw(c, wp, c.px(bx), c.px(cy), Math.max(9, c.px(badge)), wp.visible() ? 1 : 0.45F);
			float tx = x + r(24) + badge + r(22);
			float limit = visible.x() - r(40) - tx;
			String dim = WaypointsPage.nice(wp.dimension());
			title.set(wp.name());
			where.set(wp.x() + ", " + wp.y() + ", " + wp.z() + "  \u00B7  " + Character.toUpperCase(dim.charAt(dim.startsWith("the ") ? 4 : 0))
				+ dim.substring(dim.startsWith("the ") ? 5 : 1) + (wp.group().isEmpty() ? "" : "  \u00B7  " + wp.group()));
			String now = World.dimension();
			double[] you = World.position();
			far.set(now == null || you == null ? "" : !wp.dimension().equals(now) ? "Other dimension"
				: WaypointBadge.distanceText(wp.distanceTo(you[0], you[1], you[2])));
			float farW = far.width(c);
			MenuStyle.fitted(c, far, visible.x() - r(24) - farW, cy, MenuStyle.LABEL, farW + 1);
			MenuStyle.fitted(c, title, tx, cy - r(19), wp.visible() ? MenuStyle.ROW_TEXT : MenuStyle.LABEL, limit - farW - r(20));
			MenuStyle.fitted(c, where, tx, cy + r(21), MenuStyle.LABEL, limit - farW - r(20));
			if (open == index) {
				c.fill(c.px(x + r(24)), c.px(y + r(ROW_H)) - 1, c.px(x + w - r(24)), c.px(y + r(ROW_H)), Palette.withAlpha(MenuStyle.ROW_TEXT, 0.08F));
			}
			if (index == confirm) {
				MenuStyle.ring(c, x, y, w, h, MenuStyle.radius(), Math.max(1, c.px(MenuStyle.d(2))), Palette.withAlpha(MenuStyle.RED, 0.8F));
			}
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
				Glyph.TRASH.draw(c, x + w / 2, y + h / 2, r(26), asking ? MenuStyle.DANGER_TEXT : MenuStyle.CHIP_TEXT);
				MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
			}
		}
	}

	@Override
	public String toString() {
		return "WaypointsPage[" + store().all().size() + "]".toLowerCase(Locale.ROOT);
	}
}
