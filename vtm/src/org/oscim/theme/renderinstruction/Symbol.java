/*
 * Copyright 2010, 2011, 2012 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.theme.renderinstruction;

import org.oscim.renderer.atlas.TextureRegion;
import org.oscim.theme.IRenderTheme.Callback;

/**
 * Represents an icon on the map.
 */
public final class Symbol extends RenderInstruction {

	public final TextureRegion texture;

	public Symbol(TextureRegion symbol) {
		this.texture = symbol;
	}

	@Override
	public void destroy() {
	}

	@Override
	public void renderNode(Callback renderCallback) {
		renderCallback.renderPointSymbol(this);
	}

	@Override
	public void renderWay(Callback renderCallback) {
		renderCallback.renderAreaSymbol(this);
	}
}
