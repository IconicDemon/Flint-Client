package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;

/**
 * Camera views and the third-person camera. Client-side only: none changes where you look or what the server sees. Views are
 * numbered as Minecraft's perspectives: 0 first person, 1 third person behind, 2 third person in front.
 */
public final class CameraMods {
	private CameraMods() {
	}

	/** A view to switch to: behind (1) or in front (2). */
	static OptionSetting thirdView(String id, int defaultView) {
		return new OptionSetting(id, "View mode", null, defaultView - 1, "back", "Third person (behind)", "front", "Third person (front)");
	}

	/** Any of the three views, first person included. */
	static OptionSetting anyView(String id) {
		return new OptionSetting(id, "Perspective mode", null, 1, "first", "First person", "back", "Third person (behind)", "front",
			"Third person (front)");
	}

	public static final class Snaplook {
		public static final String ID = "snaplook";
		public static final BoolSetting HOLD = new BoolSetting("holdKey", "Hold key",
			"On: the view lasts while you hold the key. Off: a press switches to it, another press switches back", true);
		public static final KeySetting KEY = new KeySetting("key", "Keybind", null, KeySetting.NONE);
		public static final OptionSetting VIEW = thirdView("view", 1);
		public static final KeySetting SECOND_KEY = new KeySetting("secondKey", "Keybind", null, KeySetting.NONE);
		public static final OptionSetting SECOND_VIEW = thirdView("secondView", 2);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Snaplook", "Switch to a chosen camera view with a key")
			.keywords("perspective", "view", "f5", "camera", "third person", "snap").icon(IconId.COMPASS).category(Category.UTILITY)
			.section("General", HOLD).section("1", KEY, VIEW).section("2", SECOND_KEY, SECOND_VIEW).needs(Requirement.WORLD)
			.note("Your own view comes back when you let go (or press again). Freelook takes over while it is on; pressing F5 yourself "
				+ "keeps your choice.").build();
	}

	public static final class AutoPerspective {
		public static final String ID = "auto_perspective";
		public static final BoolSetting ELYTRA = new BoolSetting("elytra", "Elytra", "While you glide", true);
		public static final OptionSetting ELYTRA_VIEW = anyView("elytraView");
		public static final BoolSetting VEHICLE = new BoolSetting("vehicle", "Vehicle", "While you ride a boat, minecart or animal", true);
		public static final OptionSetting VEHICLE_VIEW = anyView("vehicleView");
		public static final BoolSetting SWIMMING = new BoolSetting("swimming", "Swimming", "While you swim (sprinting in water)", true);
		public static final OptionSetting SWIMMING_VIEW = anyView("swimmingView");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Auto Perspective",
				"Change camera view when flying, riding or swimming")
			.keywords("perspective", "elytra", "boat", "horse", "swim", "camera").icon(IconId.COMPASS).category(Category.UTILITY)
			.section("Elytra", ELYTRA, ELYTRA_VIEW).section("Vehicle", VEHICLE, VEHICLE_VIEW).section("Swimming", SWIMMING, SWIMMING_VIEW)
			.needs(Requirement.WORLD)
			.note("Switches back to your own view when you stop. Snaplook and Freelook come first; pressing F5 yourself keeps your choice "
				+ "until you stop.").build();
	}

	public static final class Camera {
		public static final String ID = "camera";
		public static final FloatSetting X_OFFSET = new FloatSetting("xOffset", "X offset", "Sideways: right is positive", 0, -4, 4, 0.05F,
			" blocks");
		public static final FloatSetting Y_OFFSET = new FloatSetting("yOffset", "Y offset", "Up is positive", 0, -4, 4, 0.05F, " blocks");
		public static final FloatSetting X_ROTATION = new FloatSetting("xRotation", "X rotation", "Tilts the camera up or down around you", 0,
			-90, 90, 0.5F, "°");
		public static final FloatSetting Y_ROTATION = new FloatSetting("yRotation", "Y rotation", "Turns the camera left or right around you", 0,
			-90, 90, 0.5F, "°");
		public static final FloatSetting DISTANCE = new FloatSetting("distance", "Distance", "Minecraft's is 4", 4, 0.05F, 16, 0.05F,
			" blocks");
		public static final KeySetting SCROLL_KEY = new KeySetting("scrollKey", "Scroll distance key",
			"Hold it and scroll in third person to change the distance", KeySetting.NONE);
		public static final FloatSetting INCREMENT = new FloatSetting("increment", "Distance increment", "How far one scroll or key press moves",
			0.5F, 0.05F, 16, 0.05F, " blocks");
		public static final KeySetting DISTANCE_UP = new KeySetting("distanceUp", "Distance up", "Farther, by the increment", KeySetting.NONE);
		public static final KeySetting DISTANCE_DOWN = new KeySetting("distanceDown", "Distance down", "Closer, by the increment",
			KeySetting.NONE);
		public static final BoolSetting SMOOTH = new BoolSetting("smoothZoom", "Camera smooth zoom", "Glides to a new distance", true);
		public static final OptionSetting EASING = new OptionSetting("easing", "Smooth zoom easing", null, 3, "out_exponential",
			"Out Exponential", "in_out_quadratic", "In Out Quadratic", "in_out_circular", "In Out Circular", "out_quintic", "Out Quintic");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Camera", "Third-person camera offset and distance")
			.keywords("camera", "third person", "distance", "offset", "shoulder", "f5").icon(IconId.COMPASS).category(Category.VISUAL)
			.section("Third Person Offset", X_OFFSET, Y_OFFSET, X_ROTATION, Y_ROTATION)
			.section("Third Person Distance", DISTANCE, SCROLL_KEY, INCREMENT, DISTANCE_UP, DISTANCE_DOWN)
			.section("Camera Smooth Zoom", SMOOTH, EASING).needs(Requirement.WORLD)
			.note("Only in third person. Walls still stop the camera, as in Minecraft. A distance changed with the keys or the wheel is "
				+ "kept.").build();
	}
}
