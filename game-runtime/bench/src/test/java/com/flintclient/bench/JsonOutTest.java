package com.flintclient.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonOutTest {
	@Test
	void writesNestedValuesWithCommasInTheRightPlaces() {
		StringBuilder out = new StringBuilder();
		new JsonOut(out).beginObject()
			.key("a").value(1)
			.key("b").beginArray().value(1.5).value(true).nullValue().beginArray().endArray().endArray()
			.key("c").beginObject().endObject()
			.key("d").value("x")
			.endObject();
		assertEquals("{\"a\":1,\"b\":[1.5,true,null,[]],\"c\":{},\"d\":\"x\"}", out.toString());
	}

	@Test
	void escapesStringsAndWritesNonFiniteNumbersAsNull() {
		StringBuilder out = new StringBuilder();
		new JsonOut(out).beginArray()
			.value("quote\" backslash\\ newline\n tab\t bell\u0007")
			.value(Double.NaN)
			.value(Double.POSITIVE_INFINITY)
			.value((String) null)
			.endArray();
		assertEquals("[\"quote\\\" backslash\\\\ newline\\n tab\\t bell\\u0007\",null,null,null]", out.toString());
	}

	@Test
	void escapesSurrogatesSoALoneOneCannotBreakTheUtf8Writer() {
		StringBuilder out = new StringBuilder();
		new JsonOut(out).beginArray().value("pair \uD83D\uDE00 lone \uD800").endArray();
		assertEquals("[\"pair \\ud83d\\ude00 lone \\ud800\"]", out.toString());
		// Readers turn an escaped pair back into the original character.
		assertEquals("pair \uD83D\uDE00 lone \uD800", JsonParser.parseString(out.toString()).getAsJsonArray().get(0).getAsString());
	}

	@Test
	void writesMapsListsAndBoxedValuesGenerically() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("int", 3);
		map.put("long", 4L);
		map.put("double", 0.25);
		map.put("missing", null);
		map.put("list", Arrays.asList("a", 2, false));
		StringBuilder out = new StringBuilder();
		new JsonOut(out).value((Object) map);
		assertEquals("{\"int\":3,\"long\":4,\"double\":0.25,\"missing\":null,\"list\":[\"a\",2,false]}", out.toString());
	}

	@Test
	void rejectsUnbalancedClose() {
		assertThrows(IllegalStateException.class, () -> new JsonOut(new StringBuilder()).endObject());
	}
}
