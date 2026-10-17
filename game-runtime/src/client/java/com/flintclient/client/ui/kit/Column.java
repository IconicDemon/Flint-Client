package com.flintclient.client.ui.kit;

/**
 * Stacks its visible children top to bottom, each as wide as the column and as tall as it asks
 * ({@link Node#prefHeight}), with a gap between them and padding around.
 */
public class Column extends Node {
	private final float gap;
	private float padX;
	private float padY;

	public Column(float gap) {
		this.gap = gap;
	}

	public Column padding(float x, float y) {
		padX = x;
		padY = y;
		return this;
	}

	@Override
	protected void layout() {
		float top = y + padY;
		float inner = w - 2 * padX;
		for (int i = 0; i < childCount(); i++) {
			Node child = child(i);
			if (!child.visible()) {
				continue;
			}
			float ch = child.prefHeight(inner);
			child.setBounds(x + padX, top, inner, ch);
			top += ch + gap;
		}
	}

	@Override
	public float prefHeight(float width) {
		float inner = width - 2 * padX;
		float total = 2 * padY;
		int shown = 0;
		for (int i = 0; i < childCount(); i++) {
			Node child = child(i);
			if (child.visible()) {
				total += child.prefHeight(inner);
				shown++;
			}
		}
		return total + Math.max(0, shown - 1) * gap;
	}
}
