package com.flintclient.client.render;

import com.flintclient.client.FlintLog;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The mod cards' line icons: one per mod, picked by what the mod does, from Lucide (ISC licence, lucide.dev; see
 * META-INF/licenses/ISC-Lucide.txt). They are stored flattened to polylines on Lucide's 24-unit grid in
 * {@code assets/flintclient/icons/cards.txt} (made by the tools' make-icons.js) and drawn like Flint's own line icons, a
 * little lighter. Loaded once, on first use. Render thread.
 */
public final class CardIcons {
	/** Lucide's stroke is 2 units; the cards draw them a touch lighter, which reads cleaner at their size. */
	public static final float STROKE = 1.6F;
	private static final String FILE = "/assets/flintclient/icons/cards.txt";
	private static @Nullable Map<String, Integer> byKey;
	private static final List<Sdf.Path> PATHS = new ArrayList<>();

	private CardIcons() {
	}

	/** The icon for a card key ({@code "fps"}, {@code "3d_skins"}), or -1 for none. */
	public static int index(String key) {
		load();
		Integer i = byKey.get(key);
		return i == null ? -1 : i;
	}

	static Sdf.Path path(int index) {
		load();
		return PATHS.get(index);
	}

	private static void load() {
		if (byKey != null) {
			return;
		}
		Map<String, Integer> keys = new HashMap<>();
		try (InputStream in = CardIcons.class.getResourceAsStream(FILE)) {
			if (in == null) {
				throw new IllegalStateException("missing " + FILE);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; ) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\|", 3);
				if (parts.length < 3 || keys.containsKey(parts[0])) {
					continue;
				}
				keys.put(parts[0], PATHS.size());
				PATHS.add(parse(parts[2]));
			}
		} catch (Exception e) {
			FlintLog.LOGGER.warn("Flint: couldn't read the card icons; cards keep their line icons", e);
		}
		byKey = keys;
	}

	/** "x,y x,y;x,y ..." as one path: each polyline a move then lines. */
	static Sdf.Path parse(String data) {
		Sdf.Path path = new Sdf.Path();
		for (String poly : data.split(";")) {
			String[] points = poly.trim().split(" ");
			for (int i = 0; i < points.length; i++) {
				String[] xy = points[i].split(",");
				if (xy.length != 2) {
					continue;
				}
				float x = Float.parseFloat(xy[0]);
				float y = Float.parseFloat(xy[1]);
				if (i == 0) {
					path.move(x, y);
				} else {
					path.line(x, y);
				}
			}
		}
		return path;
	}
}
