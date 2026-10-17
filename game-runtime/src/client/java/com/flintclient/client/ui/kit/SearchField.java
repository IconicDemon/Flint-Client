package com.flintclient.client.ui.kit;

/** A {@link TextField} with a search icon and a clear (×) button that appears once something is typed. */
public class SearchField extends TextField {
	public SearchField(String placeholder, int maxLength) {
		super(placeholder, maxLength, true);
	}
}
