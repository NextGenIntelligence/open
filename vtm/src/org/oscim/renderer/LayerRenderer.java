/*
 * Copyright 2012, Hannes Janetzek
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
package org.oscim.renderer;

import org.oscim.backend.GL20;
import org.oscim.core.MapPosition;
import org.oscim.renderer.MapRenderer.Matrices;

public abstract class LayerRenderer {

	protected static GL20 GL;

	static void init(GL20 gl) {
		GL = gl;
	}

	/** flag to set when layer is ready for rendering */
	boolean isReady;

	/** set by MapRenderer */
	boolean isInitialized;

	/**
	 * Set 'ready for render' state when layer data is ready for rendering.
	 * 
	 * @param true if render() should be called, false otherwise.
	 */
	protected void setReady(boolean ready) {
		isReady = ready;
	}

	protected boolean isReady() {
		return isReady;
	}

	/**
	 * 0. Called on GL Thread before first update().
	 */
	protected boolean setup() {
		return true;
	}

	/**
	 * 1. Called first by MapRenderer: Update the state here, compile
	 * vertex-data and set setReady(true).
	 * 
	 * @param position current MapPosition
	 * @param changed
	 *            true when MapPosition has changed since last frame
	 * @param matrices contains the current view- and projection-matrices
	 *            and 'mvp' matrix for temporary use.
	 */
	protected abstract void update(MapPosition position, boolean changed,
	        Matrices matrices);

	/**
	 * 2. Draw layer: called by MapRenderer when isReady == true.
	 * 
	 * @param position current MapPosition
	 * @param matrices contains the current view- and projection-matrices.
	 *            'matrices.mvp' is for temporary use to build the model-
	 *            view-projection to set as uniform.
	 */
	protected abstract void render(MapPosition position, Matrices matrices);

}
