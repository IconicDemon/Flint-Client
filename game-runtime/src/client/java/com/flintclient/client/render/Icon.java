package com.flintclient.client.render;

import java.util.function.Supplier;

/** Flint's line icons, drawn on a 24-unit grid with a 1.8-unit stroke. */
public enum Icon {
	USER(() -> new Sdf.Path()
		.circle(12, 7.5F, 4)
		.move(4.5F, 21).line(4.5F, 19.5F).arc(9, 19.5F, 4.5F, 180, 270).line(15, 15).arc(15, 19.5F, 4.5F, 270, 360).line(19.5F, 21)),
	USERS(() -> new Sdf.Path()
		.circle(9, 7.5F, 4)
		.move(2, 21).line(2, 19.5F).arc(6.5F, 19.5F, 4.5F, 180, 270).line(11.5F, 15).arc(11.5F, 19.5F, 4.5F, 270, 360).line(16, 21)
		.arc(15.5F, 7.5F, 4, -70, 70)
		.move(22, 21).line(22, 19.5F).arc(18, 19.5F, 4, 270, 360)),
	GEM(() -> new Sdf.Path()
		.move(6.5F, 3.5F).line(17.5F, 3.5F).line(21.5F, 9).line(12, 21).line(2.5F, 9).line(6.5F, 3.5F)
		.move(2.5F, 9).line(21.5F, 9)
		.move(10, 3.5F).line(8.5F, 9).line(12, 21).line(15.5F, 9).line(14, 3.5F)),
	CAMERA(() -> new Sdf.Path()
		.roundRect(2.5F, 6.5F, 21.5F, 20, 2.5F)
		.move(8, 6.5F).line(9.5F, 4).line(14.5F, 4).line(16, 6.5F)
		.circle(12, 13, 3.5F)),
	BAG(() -> new Sdf.Path()
		.roundRect(4, 8, 20, 21, 2)
		.move(8.5F, 11).line(8.5F, 6.5F).arc(12, 6.5F, 3.5F, 180, 360).line(15.5F, 11)),
	MESSAGE(() -> new Sdf.Path()
		.move(7, 17).line(4, 20.5F).line(4, 6).arc(6.5F, 6, 2.5F, 180, 270).line(18.5F, 3.5F).arc(18.5F, 6, 2.5F, 270, 360)
		.line(21, 14.5F).arc(18.5F, 14.5F, 2.5F, 0, 90).line(7, 17)),
	SUN(() -> {
		Sdf.Path path = new Sdf.Path().circle(12, 12, 4);
		for (int i = 0; i < 8; i++) {
			double a = Math.toRadians(i * 45);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			path.move(12 + 7.5F * c, 12 + 7.5F * s).line(12 + 9.5F * c, 12 + 9.5F * s);
		}
		return path;
	}),
	GRID(() -> new Sdf.Path()
		.roundRect(3.5F, 3.5F, 10.5F, 10.5F, 1.5F)
		.roundRect(13.5F, 3.5F, 20.5F, 10.5F, 1.5F)
		.roundRect(3.5F, 13.5F, 10.5F, 20.5F, 1.5F)
		.roundRect(13.5F, 13.5F, 20.5F, 20.5F, 1.5F)),
	CHEVRON_LEFT(() -> new Sdf.Path().move(14.5F, 6).line(8.5F, 12).line(14.5F, 18)),
	CHEVRON_RIGHT(() -> new Sdf.Path().move(9.5F, 6).line(15.5F, 12).line(9.5F, 18)),
	FOLDER(() -> new Sdf.Path()
		.move(3, 18.5F).line(3, 6).arc(5, 6, 2, 180, 270).line(9.5F, 4).line(11.5F, 6.5F).line(19, 6.5F).arc(19, 8.5F, 2, 270, 360)
		.line(21, 18).arc(19, 18, 2, 0, 90).line(5, 20).arc(5, 18, 2, 90, 180)),
	LAYERS(() -> new Sdf.Path()
		.move(12, 3).line(21.5F, 8).line(12, 13).line(2.5F, 8).line(12, 3)
		.move(2.5F, 12.5F).line(12, 17.5F).line(21.5F, 12.5F)
		.move(2.5F, 17).line(12, 22).line(21.5F, 17)),
	GLOBE(() -> new Sdf.Path()
		.circle(12, 12, 9.5F)
		.move(2.5F, 12).line(21.5F, 12)
		.arc(5.5F, 12, 11.5F, -55.6F, 55.6F)
		.arc(18.5F, 12, 11.5F, 124.4F, 235.6F)),
	ACCESSIBILITY(() -> new Sdf.Path()
		.circle(12, 4.5F, 1.8F)
		.move(4.5F, 8.5F).line(19.5F, 8.5F)
		.move(12, 8.5F).line(12, 14)
		.move(8, 21).line(12, 14).line(16, 21)),
	CLOSE(() -> new Sdf.Path().move(6, 6).line(18, 18).move(18, 6).line(6, 18)),
	PLUS(() -> new Sdf.Path().move(12, 5).line(12, 19).move(5, 12).line(19, 12)),
	SPARK(() -> new Sdf.Path()
		.move(12, 3).line(12, 8).move(12, 16).line(12, 21)
		.move(3, 12).line(8, 12).move(16, 12).line(21, 12)),

	// ---------- Control Center and HUD ----------

	/** Four-way arrows: move or rearrange (the HUD editor). */
	MOVE(() -> new Sdf.Path()
		.move(12, 3).line(12, 21).move(3, 12).line(21, 12)
		.move(9.5F, 5.5F).line(12, 3).line(14.5F, 5.5F)
		.move(9.5F, 18.5F).line(12, 21).line(14.5F, 18.5F)
		.move(5.5F, 9.5F).line(3, 12).line(5.5F, 14.5F)
		.move(18.5F, 9.5F).line(21, 12).line(18.5F, 14.5F)),
	/** A six-toothed cog around a hub: settings. */
	GEAR(Icon::gear),
	SEARCH(() -> new Sdf.Path()
		.circle(10.5F, 10.5F, 6.5F)
		.move(15.4F, 15.4F).line(20.5F, 20.5F)),
	/** A five-point star outline (favourite off; {@link FilledIcon#STAR} is the favourite on). */
	STAR(() -> new Sdf.Path().polygon(starPoints())),
	/** Three quarters of a circle with an arrowhead: back to the defaults. */
	RESET(() -> new Sdf.Path()
		.arc(12, 13, 7.5F, -90, 180)
		.move(12, 5.5F).line(8.5F, 5.5F)
		.move(11.5F, 2.5F).line(8.5F, 5.5F).line(11.5F, 8.5F)),
	UNDO(Icon::undo),
	REDO(() -> undo().mirrored(24)),
	INFO(() -> new Sdf.Path()
		.circle(12, 12, 9.5F)
		.dot(12, 7.4F, 0.45F)
		.move(12, 11).line(12, 16.5F)),
	WARNING(() -> new Sdf.Path()
		.polygon(12, 3, 21.5F, 20, 2.5F, 20)
		.move(12, 9.5F).line(12, 13.5F)
		.dot(12, 16.6F, 0.3F)),
	CHECK(() -> new Sdf.Path().move(4.5F, 12.5F).line(9.5F, 17.5F).line(19.5F, 6.5F)),
	TRASH(() -> new Sdf.Path()
		.move(3.5F, 6.5F).line(20.5F, 6.5F)
		.move(9, 6.5F).line(9, 3.5F).line(15, 3.5F).line(15, 6.5F)
		.move(5.5F, 6.5F).line(6.7F, 20.5F).line(17.3F, 20.5F).line(18.5F, 6.5F)
		.move(10, 10.5F).line(10, 16.5F).move(14, 10.5F).line(14, 16.5F)),
	/** Two overlapping sheets. */
	COPY(() -> new Sdf.Path()
		.roundRect(8.5F, 8.5F, 20.5F, 20.5F, 2)
		.move(3.5F, 14).line(3.5F, 5.5F).arc(5.5F, 5.5F, 2, 180, 270).line(14, 3.5F)),
	/** A pencil, point down to the left. */
	EDIT(() -> new Sdf.Path()
		.polygon(3.5F, 20.5F, 5, 15.5F, 15.5F, 5, 19, 8.5F, 8.5F, 19)
		.move(15.5F, 5).line(17, 3.5F).line(20.5F, 7).line(19, 8.5F)),
	PLAY(() -> new Sdf.Path().polygon(8, 5, 19, 12, 8, 19)),
	KEYBOARD(() -> new Sdf.Path()
		.roundRect(2.5F, 5.5F, 21.5F, 18.5F, 2)
		.dot(7, 10, 0.1F).dot(12, 10, 0.1F).dot(17, 10, 0.1F)
		.move(8, 14).line(16, 14)),
	EYE(() -> eye(new Sdf.Path())),
	EYE_OFF(() -> eye(new Sdf.Path()).move(4, 3.5F).line(20, 20.5F)),
	/** Three dots in a row: more actions. */
	DOTS(() -> new Sdf.Path().dot(5.5F, 12, 0.7F).dot(12, 12, 0.7F).dot(18.5F, 12, 0.7F)),

	// ---------- HUD modules (the names match the module descriptors' icon ids) ----------

	/** A dial with a needle: frame rate. */
	GAUGE(() -> new Sdf.Path()
		.arc(12, 13.5F, 9, 150, 390)
		.move(12, 13.5F).line(16.5F, 9)
		.dot(12, 13.5F, 0.6F)),
	/** Four rising bars: connection latency. */
	SIGNAL(() -> new Sdf.Path()
		.move(4.5F, 20).line(4.5F, 17)
		.move(9.5F, 20).line(9.5F, 13.5F)
		.move(14.5F, 20).line(14.5F, 10)
		.move(19.5F, 20).line(19.5F, 6.5F)),
	/** A mouse with its wheel: clicks per second. */
	MOUSE(() -> new Sdf.Path()
		.roundRect(6, 2.5F, 18, 21.5F, 6)
		.move(12, 6.5F).line(12, 10)),
	/** W above A, S and D: the movement keys as one cluster with shared walls (legible down to 12 px). */
	KEYS(() -> new Sdf.Path()
		.polygon(2.5F, 12, 8.8F, 12, 8.8F, 5.7F, 15.2F, 5.7F, 15.2F, 12, 21.5F, 12, 21.5F, 18.3F, 2.5F, 18.3F)
		.move(8.8F, 12).line(15.2F, 12)
		.move(8.8F, 12).line(8.8F, 18.3F)
		.move(15.2F, 12).line(15.2F, 18.3F)),
	/** A map pin: coordinates. */
	PIN(() -> new Sdf.Path()
		.arc(12, 9.5F, 6.5F, 145.6F, 394.4F)
		.line(12, 21).line(12 - 6.5F * 0.8251F, 9.5F + 6.5F * 0.5650F)
		.circle(12, 9.5F, 2.4F)),
	/** A ring with a diagonal needle: facing direction. */
	COMPASS(() -> new Sdf.Path()
		.circle(12, 12, 9.5F)
		.polygon(16, 8, 13.6F, 13.6F, 8, 16, 10.4F, 10.4F)),
	/** A shield: armour. */
	SHIELD(() -> new Sdf.Path()
		.move(12, 2.5F).line(19.5F, 5.3F).line(19.5F, 11)
		.quad(19.5F, 17.5F, 12, 21.5F).quad(4.5F, 17.5F, 4.5F, 11)
		.line(4.5F, 5.3F).line(12, 2.5F)),
	/** A flask with a liquid line: status effects. */
	FLASK(() -> new Sdf.Path()
		.move(8.5F, 3).line(15.5F, 3)
		.move(10, 3).line(10, 9).line(4.5F, 18.5F).quad(3.7F, 20.5F, 6, 20.5F)
		.line(18, 20.5F).quad(20.3F, 20.5F, 19.5F, 18.5F).line(14, 9).line(14, 3)
		.move(7.4F, 15).line(16.6F, 15)),

	// ---------- added for the Control Center deck (appended: earlier icons keep their atlas keys) ----------

	/** A chevron pointing down: an expanded section. */
	CHEVRON_DOWN(() -> new Sdf.Path().move(6, 9.5F).line(12, 15.5F).line(18, 9.5F)),
	/** A chevron pointing up: collapse. */
	CHEVRON_UP(() -> new Sdf.Path().move(6, 14.5F).line(12, 8.5F).line(18, 14.5F)),
	/** A horizontal stroke: smaller. */
	MINUS(() -> new Sdf.Path().move(5, 12).line(19, 12)),
	/** A magnet: snapping. */
	MAGNET(() -> new Sdf.Path()
		.move(5, 4).line(5, 12).arc(12, 12, 7, 180, 0).line(19, 4)
		.move(9.5F, 4).line(9.5F, 12).arc(12, 12, 2.5F, 180, 0).line(14.5F, 4)
		.move(5, 7.5F).line(9.5F, 7.5F).move(14.5F, 7.5F).line(19, 7.5F)),

	// ---------- the Right Shift menu (appended: earlier icons keep their atlas keys) ----------

	/** A speaker with two sound waves: sound. */
	SPEAKER(() -> new Sdf.Path()
		.polygon(3.5F, 9.5F, 7.5F, 9.5F, 12, 5.5F, 12, 18.5F, 7.5F, 14.5F, 3.5F, 14.5F)
		.arc(12.5F, 12, 3.5F, -50, 50)
		.arc(12.5F, 12, 7, -50, 50)),
	/** A crescent moon: night (the date and time pill). */
	MOON(() -> new Sdf.Path()
		.arc(12, 12, 8, 60, 300)
		.arc(20, 12, 8, 240, 120)),

	/** Two rays from a vertex with an arc between them and a dot at the tip: view angles. */
	VIEW_ANGLE(() -> new Sdf.Path()
		.move(3.5F, 19.5F).line(20.5F, 19.5F)
		.move(3.5F, 19.5F).line(15.5F, 6.5F)
		.arc(3.5F, 19.5F, 7.5F, -47, 0)
		.dot(17, 5, 0.9F)),
	/** A running figure with speed lines: toggle sneak and sprint. */
	RUNNER(() -> new Sdf.Path()
		.circle(15.5F, 4.5F, 2)
		.move(13.8F, 8.2F).line(11, 13.5F)
		.move(11, 13.5F).line(14.5F, 16).line(13.5F, 21)
		.move(11, 13.5F).line(8.5F, 17.5F).line(5, 18)
		.move(7.5F, 10).line(10.5F, 8.2F).line(13.8F, 8.2F).line(16, 11).line(19, 11.5F)
		.move(2.5F, 11).line(5.5F, 11)
		.move(3.5F, 14.5F).line(6, 14.5F)),
	/** A magnifying glass with a plus: zoom. */
	ZOOM_IN(() -> new Sdf.Path()
		.circle(10.5F, 10.5F, 6.5F)
		.move(15.4F, 15.4F).line(20.5F, 20.5F)
		.move(10.5F, 7.5F).line(10.5F, 13.5F)
		.move(7.5F, 10.5F).line(13.5F, 10.5F)),
	/** Steps climbing to the right: height limit. */
	STAIRS(() -> new Sdf.Path()
		.polygon(3.5F, 20.5F, 3.5F, 15, 9, 15, 9, 9.5F, 14.5F, 9.5F, 14.5F, 4, 20.5F, 4, 20.5F, 20.5F)),
	/** A chestplate with a scooped neck: armour status. */
	CHESTPLATE(() -> new Sdf.Path()
		.move(8, 3).line(3, 6.5F).line(5, 11).line(6.5F, 10).line(6.5F, 20.5F).line(17.5F, 20.5F).line(17.5F, 10)
		.line(19, 11).line(21, 6.5F).line(16, 3)
		.arc(12, 3, 4, 0, 180)),
	/** One key above three and a space bar: keystrokes. */
	KEYSTROKES(() -> new Sdf.Path()
		.roundRect(9.5F, 3, 14.5F, 8, 1.2F)
		.roundRect(3.5F, 9.5F, 8.5F, 14.5F, 1.2F)
		.roundRect(9.5F, 9.5F, 14.5F, 14.5F, 1.2F)
		.roundRect(15.5F, 9.5F, 20.5F, 14.5F, 1.2F)
		.roundRect(3.5F, 16.5F, 20.5F, 20.5F, 1.2F)),
	/** Half a sun on the horizon with its rays: day counter. */
	SUNRISE(() -> {
		Sdf.Path path = new Sdf.Path()
			.move(2.5F, 17.5F).line(21.5F, 17.5F)
			.move(7, 21).line(17, 21)
			.arc(12, 17.5F, 5, 180, 360);
		for (int angle = 210; angle <= 330; angle += 40) {
			double a = Math.toRadians(angle);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			path.move(12 + 7.5F * c, 17.5F + 7.5F * s).line(12 + 9.8F * c, 17.5F + 9.8F * s);
		}
		return path;
	}),
	/** A ring with four ticks: crosshair. */
	CROSSHAIR(() -> new Sdf.Path()
		.circle(12, 12, 7.5F)
		.move(12, 2.5F).line(12, 7).move(12, 17).line(12, 21.5F)
		.move(2.5F, 12).line(7, 12).move(17, 12).line(21.5F, 12)
		.dot(12, 12, 0.3F)),
	/** A small cross over a charging bar: attack indicator. */
	ATTACK(() -> new Sdf.Path()
		.move(12, 3.5F).line(12, 11.5F)
		.move(8, 7.5F).line(16, 7.5F)
		.roundRect(4, 15, 20, 20, 2)
		.move(6.5F, 17.5F).line(12, 17.5F)),
	/** A flag on a pole: waypoints. */
	FLAG(() -> new Sdf.Path()
		.move(5, 21.5F).line(5, 3)
		.move(5, 3.5F).line(19, 3.5F).line(15.5F, 8.25F).line(19, 13).line(5, 13)),
	/** A heart with a crack through it: hit colour. */
	HEART_CRACK(() -> new Sdf.Path()
		.move(12, 20).line(4.82F, 12.18F)
		.arc(8, 9, 4.5F, 135, 332.75F)
		.arc(16, 9, 4.5F, 207.25F, 405)
		.line(12, 20)
		.move(12.3F, 7.2F).line(10.5F, 11).line(13.5F, 13).line(11.5F, 16.5F)),
	/** A panel with a title rule and two scored rows: scoreboard. */
	SCOREBOARD(() -> new Sdf.Path()
		.roundRect(3.5F, 4, 20.5F, 20, 2)
		.move(3.5F, 9).line(20.5F, 9)
		.move(7, 13).line(13.5F, 13).dot(17, 13, 0.2F)
		.move(7, 16.5F).line(12, 16.5F).dot(17, 16.5F, 0.2F)),
	/** A serif capital T: titles. */
	TITLE_T(() -> new Sdf.Path()
		.move(5, 8.5F).line(5, 5.5F).line(19, 5.5F).line(19, 8.5F)
		.move(12, 5.5F).line(12, 19)
		.move(9, 19).line(15, 19)),
	/** Three squares and a diamond: item counter. */
	ITEMS(() -> new Sdf.Path()
		.roundRect(3.5F, 3.5F, 10.5F, 10.5F, 1.5F)
		.roundRect(13.5F, 3.5F, 20.5F, 10.5F, 1.5F)
		.roundRect(3.5F, 13.5F, 10.5F, 20.5F, 1.5F)
		.polygon(17, 13.2F, 20.8F, 17, 17, 20.8F, 13.2F, 17)),
	/** Two flasks side by side: potion counter. */
	FLASKS(() -> flask(flask(new Sdf.Path(), 7), 17)),
	/** A switch-like capsule with its knob and a trailing ghost: motion blur. */
	MOTION(() -> new Sdf.Path()
		.roundRect(2.5F, 7.5F, 21.5F, 16.5F, 4.5F)
		.dot(16.5F, 12, 1.6F)
		.circle(10.5F, 12, 1.8F)),
	/** One box above two, joined: pack organizer. */
	HIERARCHY(() -> new Sdf.Path()
		.roundRect(9, 2.5F, 15, 8, 1.2F)
		.roundRect(2.5F, 16, 8.5F, 21.5F, 1.2F)
		.roundRect(15.5F, 16, 21.5F, 21.5F, 1.2F)
		.move(12, 8).line(12, 12)
		.move(5.5F, 16).line(5.5F, 12).line(18.5F, 12).line(18.5F, 16)),
	/** A speech bubble with three dots: chat. */
	CHAT(() -> new Sdf.Path()
		.move(7, 17).line(4, 20.5F).line(4, 6).arc(6.5F, 6, 2.5F, 180, 270).line(18.5F, 3.5F).arc(18.5F, 6, 2.5F, 270, 360)
		.line(21, 14.5F).arc(18.5F, 14.5F, 2.5F, 0, 90).line(7, 17)
		.dot(8.5F, 10.25F, 0.2F).dot(12.5F, 10.25F, 0.2F).dot(16.5F, 10.25F, 0.2F)),
	/** Three bulleted lines: tab editor. */
	LIST(() -> new Sdf.Path()
		.dot(4.5F, 6, 0.4F).move(8.5F, 6).line(20.5F, 6)
		.dot(4.5F, 12, 0.4F).move(8.5F, 12).line(20.5F, 12)
		.dot(4.5F, 18, 0.4F).move(8.5F, 18).line(20.5F, 18)),
	/** A tag with its hole: nametags. */
	TAG(() -> new Sdf.Path()
		.polygon(3, 3, 11.5F, 3, 21, 12.5F, 12.5F, 21, 3, 11.5F)
		.circle(7.5F, 7.5F, 1.5F)),
	/** A box with a lid and a latch: shulker preview. */
	SHULKER(() -> new Sdf.Path()
		.roundRect(3, 4, 21, 10, 1.5F)
		.move(4.5F, 10).line(4.5F, 19).arc(6, 19, 1.5F, 180, 90).line(18, 20.5F).arc(18, 19, 1.5F, 90, 0).line(19.5F, 10)
		.roundRect(10, 8.5F, 14, 12.5F, 0.8F)),
	/** A page with an up-and-down arrow beside it: scrollable tooltips. */
	SCROLL_DOC(() -> new Sdf.Path()
		.roundRect(3, 3, 15, 21, 2)
		.move(6.5F, 8).line(11.5F, 8)
		.move(6.5F, 12).line(11.5F, 12)
		.move(6.5F, 16).line(10, 16)
		.move(19.5F, 6.5F).line(19.5F, 17.5F)
		.move(17.3F, 8.7F).line(19.5F, 6.5F).line(21.7F, 8.7F)
		.move(17.3F, 15.3F).line(19.5F, 17.5F).line(21.7F, 15.3F)),
	/** A four-point sparkle with a small one and a speck: particle changer. */
	SPARKLES(() -> new Sdf.Path()
		.polygon(10, 3, 11.7F, 8.3F, 17, 10, 11.7F, 11.7F, 10, 17, 8.3F, 11.7F, 3, 10, 8.3F, 8.3F)
		.move(18, 14).line(18, 20.5F).move(14.75F, 17.25F).line(21.25F, 17.25F)
		.dot(19, 4.5F, 0.4F)),
	/** A masquerade mask: nick hider. */
	MASK(() -> new Sdf.Path()
		.move(2.5F, 8).quad(12, 4.5F, 21.5F, 8).line(21, 13)
		.quad(20, 17.5F, 15.5F, 17.5F).quad(13, 17.5F, 12, 15).quad(11, 17.5F, 8.5F, 17.5F).quad(4, 17.5F, 3, 13).line(2.5F, 8)
		.circle(8, 11.5F, 1.8F)
		.circle(16, 11.5F, 1.8F)),
	/** An hourglass: cooldowns. */
	HOURGLASS(() -> new Sdf.Path()
		.move(5.5F, 3).line(18.5F, 3)
		.move(5.5F, 21).line(18.5F, 21)
		.move(7, 3).line(7, 6).quad(7, 9, 12, 12).quad(17, 9, 17, 6).line(17, 3)
		.move(7, 21).line(7, 18).quad(7, 15, 12, 12).quad(17, 15, 17, 18).line(17, 21)),
	/** A play triangle in a ring: playtime. */
	PLAY_CIRCLE(() -> new Sdf.Path()
		.circle(12, 12, 9.5F)
		.polygon(10, 8, 16, 12, 10, 16)),
	/** A memory module with three chips and its pins: memory usage. */
	MEMORY(() -> new Sdf.Path()
		.roundRect(2.5F, 6.5F, 21.5F, 16.5F, 1.5F)
		.roundRect(5.5F, 9.5F, 9, 13.5F, 0.6F)
		.roundRect(10.25F, 9.5F, 13.75F, 13.5F, 0.6F)
		.roundRect(15, 9.5F, 18.5F, 13.5F, 0.6F)
		.move(6, 16.5F).line(6, 19.5F).move(10, 16.5F).line(10, 19.5F)
		.move(14, 16.5F).line(14, 19.5F).move(18, 16.5F).line(18, 19.5F)),
	/** A rising zig-zag with an arrowhead: combo counter. */
	TREND(() -> new Sdf.Path()
		.move(3, 17.5F).line(9, 11.5F).line(13, 15.5F).line(20.5F, 8)
		.move(15, 8).line(20.5F, 8).line(20.5F, 13.5F)),
	/** A double-headed arrow between two stops: reach display. */
	REACH(() -> new Sdf.Path()
		.move(5, 12).line(19, 12)
		.move(8, 9).line(5, 12).line(8, 15)
		.move(16, 9).line(19, 12).line(16, 15)
		.move(2.5F, 7).line(2.5F, 17)
		.move(21.5F, 7).line(21.5F, 17)),
	/** A drumstick: saturation. */
	DRUMSTICK(() -> new Sdf.Path()
		.arc(14.5F, 9.5F, 6.5F, 150, 480)
		.move(8.87F, 12.75F).line(6.6F, 15)
		.move(11.25F, 15.13F).line(9, 17.4F)
		.circle(4.9F, 16.4F, 1.7F)
		.circle(7.6F, 19.1F, 1.7F)),
	/** A drop of liquid: colour saturation. */
	DROPLET(() -> new Sdf.Path()
		.move(12, 3).line(17.32F, 10.77F)
		.arc(12, 14.5F, 6.5F, -35, 215)
		.line(12, 3)
		.arc(12, 14.5F, 3.6F, 100, 165)),
	/** An apple with its stem and leaf: item physics. */
	APPLE(() -> new Sdf.Path()
		.move(12, 8).quad(8.5F, 5.5F, 5.5F, 8).quad(3, 10.5F, 4.5F, 15.5F).quad(6.5F, 21, 10, 20.5F).quad(11, 20.2F, 12, 20.5F)
		.quad(13, 20.2F, 14, 20.5F).quad(17.5F, 21, 19.5F, 15.5F).quad(21, 10.5F, 18.5F, 8).quad(15.5F, 5.5F, 12, 8)
		.move(12, 8).quad(12, 5.5F, 13.5F, 3.5F)
		.move(13, 5.8F).quad(16.5F, 6, 17.5F, 3).quad(14, 2.6F, 13, 5.8F)),
	/** A round bomb with a lit fuse: TNT countdown. */
	BOMB(() -> new Sdf.Path()
		.circle(10.5F, 14, 6.5F)
		.move(15.2F, 9.5F).quad(16.5F, 6, 19.5F, 5.5F)
		.move(21.5F, 2.5F).line(20.8F, 3.2F)
		.move(22, 6).line(21.2F, 5.7F)
		.move(18.7F, 2.2F).line(18.9F, 3.1F)
		.arc(10.5F, 14, 3.6F, 200, 250)),
	/** A blocky player: 3D skins. */
	PLAYER(() -> new Sdf.Path()
		.roundRect(9, 2.5F, 15, 8.5F, 1)
		.roundRect(8.5F, 10, 15.5F, 16, 1)
		.move(5.5F, 10.5F).line(5.5F, 15.5F)
		.move(18.5F, 10.5F).line(18.5F, 15.5F)
		.move(10.5F, 16).line(10.5F, 21.5F)
		.move(13.5F, 16).line(13.5F, 21.5F)),
	/** A ring with speed lines behind it: momentum. */
	MOMENTUM(() -> new Sdf.Path()
		.circle(15.5F, 12, 5.5F)
		.dot(15.5F, 12, 0.3F)
		.move(3.5F, 8.5F).line(8, 8.5F)
		.move(2, 12).line(7.5F, 12)
		.move(3.5F, 15.5F).line(8, 15.5F)),
	/** A square drawn in dashes: block outline. */
	DASHED_SQUARE(() -> new Sdf.Path()
		.move(4, 8).line(4, 4).line(8, 4)
		.move(16, 4).line(20, 4).line(20, 8)
		.move(20, 16).line(20, 20).line(16, 20)
		.move(8, 20).line(4, 20).line(4, 16)
		.move(10.5F, 4).line(13.5F, 4)
		.move(10.5F, 20).line(13.5F, 20)
		.move(4, 10.5F).line(4, 13.5F)
		.move(20, 10.5F).line(20, 13.5F)),
	/** An upward arrow inside focus corners: screenshot uploader. */
	UPLOAD_FRAME(() -> new Sdf.Path()
		.move(3, 8).line(3, 3).line(8, 3)
		.move(16, 3).line(21, 3).line(21, 8)
		.move(21, 16).line(21, 21).line(16, 21)
		.move(8, 21).line(3, 21).line(3, 16)
		.move(12, 16.5F).line(12, 7.5F)
		.move(8.5F, 11).line(12, 7.5F).line(15.5F, 11)),
	/** Two rays opening from the eye with the angle between them: FOV changer. */
	FOV(() -> new Sdf.Path()
		.move(12, 20).line(3.5F, 6.5F)
		.move(12, 20).line(20.5F, 6.5F)
		.arc(12, 20, 8.5F, 238, 302)
		.dot(12, 20, 0.3F)),
	/** Three wavy lines: fog customizer. */
	FOG(() -> wave(wave(wave(new Sdf.Path(), 7), 12), 17)),
	/** A triangle, a circle and a square: 2D items. */
	SHAPES(() -> new Sdf.Path()
		.polygon(7, 3.5F, 11.5F, 11, 2.5F, 11)
		.circle(17, 7.2F, 3.8F)
		.roundRect(12.5F, 13, 20.5F, 21, 1)
		.move(3.5F, 17).line(10.5F, 17)),
	/** A head above a half-full bar: boss bar. */
	BOSS_BAR(() -> new Sdf.Path()
		.circle(12, 6.5F, 3)
		.roundRect(3, 12.5F, 21, 17.5F, 2.5F)
		.move(5.8F, 15).line(13.5F, 15)),
	/** Nine dots in a square: menu blur, and the menu's view button. */
	NINE_DOTS(() -> {
		Sdf.Path path = new Sdf.Path();
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				path.dot(5.5F + 6.5F * col, 5.5F + 6.5F * row, 0.55F);
			}
		}
		return path;
	}),
	/** A wireframe cube: hitbox. */
	CUBE(() -> new Sdf.Path()
		.polygon(3.5F, 8.5F, 15, 8.5F, 15, 20.5F, 3.5F, 20.5F)
		.move(9, 3).line(20.5F, 3).line(20.5F, 15).line(15, 20.5F)
		.move(3.5F, 8.5F).line(9, 3)
		.move(15, 8.5F).line(20.5F, 3)
		.move(9, 3).line(9, 15).line(20.5F, 15)
		.move(9, 15).line(3.5F, 20.5F)),
	/** A light bulb: lighting. */
	BULB(() -> new Sdf.Path()
		.move(9.2F, 17).quad(9.2F, 15.3F, 7.02F, 13.68F)
		.arc(12, 9.5F, 6.5F, 140, 400)
		.quad(14.8F, 15.3F, 14.8F, 17).line(9.2F, 17)
		.move(9.5F, 19.5F).line(14.5F, 19.5F)
		.move(10.5F, 21.5F).line(13.5F, 21.5F)),
	/** A square divided into a three-by-three grid: chunk borders. */
	CHUNKS(() -> new Sdf.Path()
		.roundRect(3.5F, 3.5F, 20.5F, 20.5F, 1.5F)
		.move(9.17F, 3.5F).line(9.17F, 20.5F)
		.move(14.83F, 3.5F).line(14.83F, 20.5F)
		.move(3.5F, 9.17F).line(20.5F, 9.17F)
		.move(3.5F, 14.83F).line(20.5F, 14.83F)),
	/** A shield with a star on it: tier tagger. */
	SHIELD_STAR(() -> new Sdf.Path()
		.move(12, 2.5F).line(19.5F, 5.3F).line(19.5F, 11)
		.quad(19.5F, 17.5F, 12, 21.5F).quad(4.5F, 17.5F, 4.5F, 11)
		.line(4.5F, 5.3F).line(12, 2.5F)
		.polygon(star(12, 11.6F, 4, 1.7F))),
	/** A paintbrush: damage tint. */
	BRUSH(() -> new Sdf.Path()
		.move(20.5F, 3.5F).line(13, 11)
		.polygon(11.2F, 9.7F, 14.3F, 12.8F, 12.6F, 14.5F, 9.5F, 11.4F)
		.move(9.5F, 11.4F).quad(5.5F, 12.5F, 5, 16.5F).quad(4.7F, 19.5F, 3, 21).quad(7.5F, 21, 10, 18.5F).quad(12, 16.5F, 12.6F, 14.5F)),
	/** A horseshoe: horse stats. */
	HORSESHOE(() -> new Sdf.Path()
		.move(9.4F, 20.5F).line(6, 20.5F).line(5.42F, 12.39F)
		.arc(12, 10, 7, 160, 380)
		.line(18, 20.5F).line(14.6F, 20.5F).line(15.29F, 11.2F)
		.arc(12, 10, 3.5F, 20, -200)
		.line(9.4F, 20.5F)),
	/** A sword with sound waves: kill sounds. */
	SWORD(() -> new Sdf.Path()
		.polygon(20.5F, 3.5F, 20.5F, 6.8F, 10.4F, 16.9F, 7.1F, 13.6F, 17.2F, 3.5F)
		.move(5, 12).line(12, 19)
		.move(8.6F, 15.4F).line(4, 20)
		.dot(3.5F, 20.5F, 0.4F)
		.arc(14.5F, 14.5F, 4, 15, 75)
		.arc(14.5F, 14.5F, 7, 15, 75)),
	/** Nine inventory slots: inventory. */
	SLOTS(() -> {
		Sdf.Path path = new Sdf.Path();
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				float x = 3 + 6.33F * col;
				float y = 3 + 6.33F * row;
				path.roundRect(x, y, x + 5, y + 5, 0.8F);
			}
		}
		return path;
	}),
	/** A square with an arrow out of its corner: GUI scale. */
	EXPAND(() -> new Sdf.Path()
		.roundRect(3.5F, 3.5F, 20.5F, 20.5F, 2)
		.move(10.5F, 8.5F).line(15.5F, 8.5F).line(15.5F, 13.5F)
		.move(15.5F, 8.5F).line(8.5F, 15.5F)),
	/** Two beamed notes: Spotify. */
	MUSIC(() -> new Sdf.Path()
		.move(9, 18).line(9, 5.5F).line(19, 3.5F).line(19, 16)
		.circle(6.5F, 18, 2.5F)
		.circle(16.5F, 16, 2.5F));

	public static final float UNITS = 24.0F;
	public static final float STROKE = 1.8F;

	private final Supplier<Sdf.Path> shape;
	private Sdf.Path path;

	Icon(Supplier<Sdf.Path> shape) {
		this.shape = shape;
	}

	Sdf.Path path() {
		if (path == null) {
			path = shape.get();
		}
		return path;
	}

	/** Six teeth around a hub, traced as one outline: tips at radius 9.5, roots at 7. */
	private static Sdf.Path gear() {
		Sdf.Path path = new Sdf.Path();
		float tip = 9.5F;
		float root = 7.0F;
		boolean first = true;
		for (int i = 0; i < 6; i++) {
			float c = i * 60 - 90;
			// Each tooth: along the root, up a flank, across the tip, down the other flank.
			float[] angles = {c - 30, c - 17, c - 10, c + 10, c + 17};
			float[] radii = {root, root, tip, tip, root};
			for (int k = 0; k < angles.length; k++) {
				double a = Math.toRadians(angles[k]);
				float x = 12 + radii[k] * (float) Math.cos(a);
				float y = 12 + radii[k] * (float) Math.sin(a);
				if (first) {
					path.move(x, y);
					first = false;
				} else {
					path.line(x, y);
				}
			}
		}
		double a = Math.toRadians(-120);
		path.line(12 + root * (float) Math.cos(a), 12 + root * (float) Math.sin(a));
		return path.circle(12, 12, 3);
	}

	/** The star's ten corners (x, y pairs), shared by the outline and {@link FilledIcon#STAR}. */
	static float[] starPoints() {
		float[] xy = new float[20];
		for (int i = 0; i < 10; i++) {
			double a = Math.toRadians(-90 + i * 36);
			float r = i % 2 == 0 ? 9.6F : 4.3F;
			xy[2 * i] = 12 + r * (float) Math.cos(a);
			xy[2 * i + 1] = 12.7F + r * (float) Math.sin(a);
		}
		return xy;
	}

	/** A five-point star's corners around (cx, cy) with outer radius {@code r} and inner radius {@code inner}. */
	private static float[] star(float cx, float cy, float r, float inner) {
		float[] xy = new float[20];
		for (int i = 0; i < 10; i++) {
			double a = Math.toRadians(-90 + i * 36);
			float radius = i % 2 == 0 ? r : inner;
			xy[2 * i] = cx + radius * (float) Math.cos(a);
			xy[2 * i + 1] = cy + radius * (float) Math.sin(a);
		}
		return xy;
	}

	/** A narrow flask centred on x {@code cx}: rim, neck and a flat-bottomed body. */
	private static Sdf.Path flask(Sdf.Path path, float cx) {
		return path
			.move(cx - 2.5F, 4).line(cx + 2.5F, 4)
			.move(cx - 1.5F, 4).line(cx - 1.5F, 10).line(cx - 4, 18.3F).quad(cx - 4.4F, 20.5F, cx - 2.4F, 20.5F)
			.line(cx + 2.4F, 20.5F).quad(cx + 4.4F, 20.5F, cx + 4, 18.3F).line(cx + 1.5F, 10).line(cx + 1.5F, 4);
	}

	/** One wavy line across the grid at height {@code y}. */
	private static Sdf.Path wave(Sdf.Path path, float y) {
		return path.move(3, y).quad(5.25F, y - 2, 7.5F, y).quad(9.75F, y + 2, 12, y).quad(14.25F, y - 2, 16.5F, y).quad(18.75F, y + 2, 21, y);
	}

	/** An arrow back to the left that turns down and returns: undo (REDO is its mirror image). */
	private static Sdf.Path undo() {
		return new Sdf.Path()
			.move(8.5F, 4.5F).line(4, 9).line(8.5F, 13.5F)
			.move(4, 9).line(14.5F, 9).arc(14.5F, 14.25F, 5.25F, -90, 90).line(9, 19.5F);
	}

	/** An almond outline from two arcs meeting at x 2 and 22 (each bulging 6.4 from the middle), with a round pupil. */
	private static Sdf.Path eye(Sdf.Path path) {
		float half = 10;
		float bulge = 6.4F;
		float r = (half * half + bulge * bulge) / (2 * bulge);
		float corner = (float) Math.toDegrees(Math.atan2(r - bulge, half));
		return path
			.arc(12, 12 - bulge + r, r, 180 + corner, 360 - corner)
			.arc(12, 12 + bulge - r, r, corner, 180 - corner)
			.circle(12, 12, 2.8F);
	}
}
