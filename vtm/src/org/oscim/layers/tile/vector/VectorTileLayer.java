/*
 * Copyright 2013 Hannes Janetzek
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
package org.oscim.layers.tile.vector;

import org.oscim.layers.tile.TileLayer;
import org.oscim.map.Map;
import org.oscim.theme.IRenderTheme;
import org.oscim.tiling.TileLoader;
import org.oscim.tiling.TileManager;
import org.oscim.tiling.source.ITileDataSource;
import org.oscim.tiling.source.TileSource;
import org.oscim.tiling.source.TileSource.OpenResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vector-tile-map layer. This class manages instances of
 * {@link VectorTileLoader} that load and assemble vector tiles
 * for rendering.
 */
public class VectorTileLayer extends TileLayer<VectorTileLoader> {
	static final Logger log = LoggerFactory.getLogger(VectorTileLayer.class);

	private TileSource mTileSource;

	public VectorTileLayer(Map map) {
		super(map);
	}

	@Override
	protected VectorTileLoader createLoader(TileManager tm) {
		return new VectorTileLoader(tm);
	}

	/**
	 * Sets the {@link TileSource} used by {@link TileLoader}.
	 * 
	 * @return true when new TileSource was set (has changed)
	 */
	public boolean setTileSource(TileSource tileSource) {

		pauseLoaders(true);

		mTileManager.clearJobs();

		if (mTileSource != null) {
			mTileSource.close();
			mTileSource = null;
		}

		OpenResult msg = tileSource.open();

		if (msg != OpenResult.SUCCESS) {
			log.debug(msg.getErrorMessage());
			return false;
		}

		mTileSource = tileSource;

		for (int i = 0; i < mNumTileLoader; i++) {
			ITileDataSource tileDataSource = tileSource.getDataSource();
			mTileLoader.get(i).setTileDataSource(tileDataSource);
		}

		//mTileManager.setZoomTable(mTileSource.getMapInfo().zoomLevel);

		mMap.clearMap();

		resumeLoaders();

		return true;
	}

	/**
	 * Set {@link IRenderTheme} used by {@link TileLoader}
	 */
	public void setRenderTheme(IRenderTheme theme) {
		pauseLoaders(true);

		for (VectorTileLoader g : mTileLoader)
			g.setRenderTheme(theme);

		resumeLoaders();
	}
}
