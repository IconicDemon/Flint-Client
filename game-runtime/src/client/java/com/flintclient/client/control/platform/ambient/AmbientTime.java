package com.flintclient.client.control.platform.ambient;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A volume over the day: named spans (day, night, sunrise, sunset, noon, midnight) or points by hour or sun angle,
 * straight lines between them. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
@JsonAdapter(AmbientTime.Serializer.class)
abstract class AmbientTime {
	static final Map<String, Premade> PREMADE = new HashMap<>();
	static final double ANGLE_TO_TIME = 1 / 15.0;
	static final AmbientTime NONE = new AmbientTime() {
		@Override
		double value(AmbientEnvironment env) {
			return 0;
		}
	};
	static final Premade DAY = new Premade("day", 1, 6, 18);
	static final Premade NIGHT = new Premade("night", new double[] {0, 1, 4, 1, 5, 0, 19, 0, 19, 1, 24, 1});
	static final Premade SUNRISE = new Premade("sunrise", 1, 5, 6);
	static final Premade SUNSET = new Premade("sunset", 1, 18, 19);
	static final Premade NOON = new Premade("noon", 0.5, 10.5, 13.5);
	static final Premade MIDNIGHT = new Premade("midnight", new double[] {0, 1, 1, 1, 1.5, 0, 22.5, 0, 23, 1, 24, 1});

	abstract double value(AmbientEnvironment env);

	/** Straight lines between (time, value) points; before the first and after the last, their value (CreativeCore's). */
	static final class Line {
		private final double[] times;
		private final double[] values;

		Line(List<double[]> points) {
			times = new double[points.size()];
			values = new double[points.size()];
			for (int i = 0; i < points.size(); i++) {
				times[i] = points.get(i)[0];
				values[i] = points.get(i)[1];
			}
		}

		double at(double t) {
			int first = -1;
			int second = -1;
			for (int i = 0; i < times.length; i++) {
				if (times[i] >= t) {
					if (first == -1) {
						first = i;
					} else {
						second = i;
					}
					break;
				}
				first = i;
			}
			if (second == -1) {
				return values[first];
			}
			double mu = (t - times[first]) / (times[second] - times[first]);
			return (values[second] - values[first]) * mu + values[first];
		}
	}

	static class Hour extends AmbientTime {
		private final Line line;

		Hour(double... times) {
			if (times.length % 2 != 0) {
				throw new IllegalArgumentException("Invalid times " + Arrays.toString(times) + " cannot calculate ambient time");
			}
			List<double[]> points = new ArrayList<>();
			if (times[times.length - 2] < 24) {
				points.add(new double[] {times[times.length - 2] - 24, times[times.length - 1]});
			}
			for (int i = 0; i < times.length; i += 2) {
				points.add(new double[] {times[i], times[i + 1]});
			}
			if (times[0] > 0) {
				points.add(new double[] {times[0] + 24, times[1]});
			}
			line = new Line(points);
		}

		@Override
		double value(AmbientEnvironment env) {
			return line.at(env.dayTimeHour);
		}
	}

	static final class Premade extends Hour {
		final String name;

		Premade(String name, double fade, double begin, double end) {
			super(begin, 0, begin + fade, 1, end - fade, 1, end, 0);
			this.name = name;
			PREMADE.put(name, this);
		}

		Premade(String name, double[] times) {
			super(times);
			this.name = name;
			PREMADE.put(name, this);
		}
	}

	static final class Multi extends AmbientTime {
		private final Premade[] times;

		Multi(Premade... times) {
			this.times = times;
		}

		@Override
		double value(AmbientEnvironment env) {
			double value = 0;
			for (Premade time : times) {
				value = Math.max(value, time.value(env));
			}
			return value;
		}
	}

	static final class SunAngle extends AmbientTime {
		private final Line line;

		SunAngle(double... times) {
			if (times.length % 2 != 0) {
				throw new IllegalArgumentException("Invalid times " + Arrays.toString(times) + " cannot calculate ambient time");
			}
			List<double[]> points = new ArrayList<>();
			points.add(new double[] {times[times.length - 2] - 360, times[times.length - 1]});
			for (int i = 0; i < times.length; i += 2) {
				points.add(new double[] {times[i], times[i + 1]});
			}
			points.add(new double[] {times[0] + 360, times[1]});
			line = new Line(points);
		}

		@Override
		double value(AmbientEnvironment env) {
			return line.at(env.sunAngle);
		}
	}

	static final class Serializer implements JsonDeserializer<AmbientTime> {
		@Override
		public AmbientTime deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			if (json.isJsonPrimitive()) {
				Premade premade = json.getAsJsonPrimitive().isString() ? PREMADE.get(json.getAsString()) : null;
				if (premade == null) {
					throw new JsonParseException("Invalid time given " + json);
				}
				return premade;
			}
			if (json.isJsonArray()) {
				JsonArray array = json.getAsJsonArray();
				if (array.isEmpty()) {
					return NONE;
				}
				Premade[] content = new Premade[array.size()];
				for (int i = 0; i < content.length; i++) {
					content[i] = PREMADE.get(array.get(i).getAsString());
					if (content[i] == null) {
						throw new JsonParseException("Invalid time given " + array.get(i));
					}
				}
				return new Multi(content);
			}
			if (!json.isJsonObject()) {
				throw new JsonParseException("Invalid json element given " + json);
			}
			JsonObject object = json.getAsJsonObject();
			List<Double> list = new ArrayList<>();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				try {
					double key = Double.parseDouble(entry.getKey());
					double value = entry.getValue().getAsDouble();
					list.add(key);
					list.add(value);
				} catch (NumberFormatException e) {
					// "type" and the like
				}
			}
			double[] times = list.stream().mapToDouble(Double::doubleValue).toArray();
			return object.has("type") && object.get("type").getAsString().equalsIgnoreCase("angle") ? new SunAngle(times) : new Hour(times);
		}
	}
}
