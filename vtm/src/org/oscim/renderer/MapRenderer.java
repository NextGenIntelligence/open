/*
 * Copyright 2012, 2013 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General License for more details.
 *
 * You should have received a copy of the GNU Lesser General License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.oscim.backend.GL20;
import org.oscim.backend.GLAdapter;
import org.oscim.core.MapPosition;
import org.oscim.map.Map;
import org.oscim.map.Viewport;
import org.oscim.renderer.elements.ElementLayers;
import org.oscim.tiling.MapTile;
import org.oscim.utils.pool.Inlist;
import org.oscim.utils.pool.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapRenderer {
	static final Logger log = LoggerFactory.getLogger(MapRenderer.class);

	static GL20 GL;

	/** scale factor used for short vertices */
	public static final float COORD_SCALE = 8.0f;

	private static Map mMap;
	public static int screenWidth, screenHeight;

	private static Viewport mViewport;
	private static MapPosition mMapPosition;

	public class Matrices {

		/** Do not modify! */
		public final GLMatrix viewproj = new GLMatrix();
		/** Do not modify! */
		public final GLMatrix proj = new GLMatrix();
		/** Do not modify! */
		public final GLMatrix view = new GLMatrix();
		/** Do not modify! */
		public final float[] mapPlane = new float[8];

		/** For temporary use, to setup MVP-Matrix */
		public final GLMatrix mvp = new GLMatrix();

		/**
		 * Set MVP so that coordinates are in screen pixel coordinates with 0,0
		 * being center
		 */
		public void useScreenCoordinates(boolean center, float scale) {
			float ratio = (1f / (scale * screenWidth));

			if (center)
				mvp.setScale(ratio, ratio, ratio);
			else
				mvp.setTransScale(
				                  (-screenWidth / 2) * ratio * scale,
				                  (-screenHeight / 2) * ratio * scale,
				                  ratio);

			mvp.multiplyLhs(proj);
		}
	}

	private static Matrices mMatrices;

	// private
	static float[] mClearColor = null;

	public static int mQuadIndicesID;
	private static int mQuadVerticesID;
	public final static int maxQuads = 64;

	private static boolean mUpdateColor = false;

	// drawlock to synchronize Main- and GL-Thread
	// static ReentrantLock tilelock = new ReentrantLock();
	public static Object drawlock = new Object();

	public static long frametime;

	// Do not use the same buffer to upload data within a frame twice
	// - Contrary to what the OpenGL doc says data seems *not* to be
	// *always* copied after glBufferData returns...
	// - Somehow it does always copy when using Android GL bindings
	// but not when using libgdx bindings (LWJGL or AndroidGL20)

	static class BufferPool extends Pool<BufferItem> {
		private BufferItem mUsedBuffers;

		@Override
		protected BufferItem createItem() {
			// unused;
			return null;
		}

		public BufferItem get(int size) {
			BufferItem b = pool;

			if (b == null) {
				b = new BufferItem();
			} else {
				pool = b.next;
				b.next = null;
			}
			if (b.tmpBufferSize < size)
				b.growBuffer(size);

			mUsedBuffers = Inlist.push(mUsedBuffers, b);

			return b;
		}

		public void releaseBuffers() {
			mUsedBuffers = releaseAll(mUsedBuffers);
		}
	}

	private static BufferPool mBufferPool;

	/**
	 * @param map
	 *            the MapView
	 */
	public MapRenderer(Map map) {

		mMap = map;
		mViewport = map.getViewport();
		mMapPosition = new MapPosition();

		mMatrices = new Matrices();
		mBufferPool = new BufferPool();

		// FIXME should be done in 'destroy' method
		// clear all previous vbo refs
		BufferObject.clear();
	}

	public static void setBackgroundColor(int color) {
		mClearColor = GLUtils.colorToFloat(color);
		mUpdateColor = true;
	}

	static class BufferItem extends Inlist<BufferItem> {

		ShortBuffer shortBuffer;
		FloatBuffer floatBuffer;
		IntBuffer intBuffer;
		int tmpBufferSize;

		void growBuffer(int size) {
			//log.debug("grow buffer " + size);
			// 32kb min size
			if (size < (1 << 15))
				size = (1 << 15);

			ByteBuffer buf = ByteBuffer
			    .allocateDirect(size)
			    .order(ByteOrder.nativeOrder());

			this.floatBuffer = buf.asFloatBuffer();
			this.shortBuffer = buf.asShortBuffer();
			this.intBuffer = buf.asIntBuffer();
			this.tmpBufferSize = size;
		}
	}

	/**
	 * Only use on GL Thread! Get a native ShortBuffer for temporary use.
	 */
	public static ShortBuffer getShortBuffer(int size) {
		BufferItem b = mBufferPool.get(size * 2);
		b.shortBuffer.clear();
		return b.shortBuffer;
	}

	/**
	 * Only use on GL Thread! Get a native FloatBuffer for temporary use.
	 */
	public static FloatBuffer getFloatBuffer(int size) {
		BufferItem b = mBufferPool.get(size * 4);
		b.floatBuffer.clear();
		return b.floatBuffer;
	}

	/**
	 * Only use on GL Thread! Get a native IntBuffer for temporary use.
	 */
	public static IntBuffer getIntBuffer(int size) {
		BufferItem b = mBufferPool.get(size * 4);
		b.intBuffer.clear();
		return b.intBuffer;
	}

	public void onDrawFrame() {

		// prevent main thread recreating all tiles (updateMap)
		// while rendering is going on.
		synchronized (drawlock) {
			frametime = System.currentTimeMillis();
			draw();
		}

		mBufferPool.releaseBuffers();
	}

	private static void draw() {

		if (mUpdateColor) {
			float cc[] = mClearColor;
			GL.glClearColor(cc[0], cc[1], cc[2], cc[3]);
			mUpdateColor = false;
		}

		GL.glDepthMask(true);
		GL.glStencilMask(0xFF);
		GL.glClear(GL20.GL_COLOR_BUFFER_BIT
		        | GL20.GL_DEPTH_BUFFER_BIT
		        | GL20.GL_STENCIL_BUFFER_BIT);

		GLState.blend(false);
		GL.glDisable(GL20.GL_BLEND);

		boolean changed = false;

		MapPosition pos = mMapPosition;

		synchronized (mViewport) {
			mMap.getAnimator().updateAnimation();

			// get current MapPosition
			changed = mViewport.getMapPosition(pos);

			if (changed)
				mViewport.getMapViewProjection(mMatrices.mapPlane);

			mViewport.getMatrix(mMatrices.view, mMatrices.proj, mMatrices.viewproj);

			if (debugView) {
				mMatrices.mvp.setScale(0.5f, 0.5f, 1);
				mMatrices.viewproj.multiplyLhs(mMatrices.mvp);
			}
		}

		//log.debug("begin frame");
		GLState.bindTex2D(-1);
		GLState.useProgram(-1);
		//GL.glBindTexture(GL20.GL_TEXTURE_2D, 0);

		/* update layers */
		LayerRenderer[] layers = mMap.getLayers().getLayerRenderer();

		for (int i = 0, n = layers.length; i < n; i++) {
			LayerRenderer renderer = layers[i];

			if (!renderer.isInitialized) {
				renderer.setup();
				renderer.isInitialized = true;
			}

			renderer.update(pos, changed, mMatrices);

			if (GLAdapter.debug)
				GLUtils.checkGlError(renderer.getClass().getName());

			if (renderer.isReady)
				renderer.render(mMapPosition, mMatrices);
		}

		if (GLUtils.checkGlOutOfMemory("finish")) {
			BufferObject.checkBufferUsage(true);
			// FIXME also throw out some textures etc
		}
	}

	public static int depthOffset(MapTile t) {
		return ((t.tileX % 4) + (t.tileY % 4 * 4) + 1);
	}

	public void onSurfaceChanged(int width, int height) {
		log.debug("SurfaceChanged:" + mNewSurface + " " + width + "x" + height);

		if (width <= 0 || height <= 0)
			return;

		screenWidth = width;
		screenHeight = height;

		mViewport.getMatrix(null, mMatrices.proj, null);

		if (debugView) {
			// modify this to scale only the view, to see better which tiles
			// are rendered
			mMatrices.mvp.setScale(0.5f, 0.5f, 1);
			mMatrices.proj.multiplyLhs(mMatrices.mvp);
		}

		GL.glViewport(0, 0, width, height);
		GL.glScissor(0, 0, width, height);
		GL.glEnable(GL20.GL_SCISSOR_TEST);

		GL.glClearStencil(0x00);

		GL.glDisable(GL20.GL_CULL_FACE);
		GL.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);

		GL.glFrontFace(GL20.GL_CW);
		GL.glCullFace(GL20.GL_BACK);

		if (!mNewSurface) {
			mMap.updateMap(false);
			return;
		}

		mNewSurface = false;

		// upload quad indices used by Texture- and LineTexRenderer
		int[] vboIds = GLUtils.glGenBuffers(2);

		mQuadIndicesID = vboIds[0];
		int maxIndices = maxQuads * 6;
		short[] indices = new short[maxIndices];
		for (int i = 0, j = 0; i < maxIndices; i += 6, j += 4) {
			indices[i + 0] = (short) (j + 0);
			indices[i + 1] = (short) (j + 1);
			indices[i + 2] = (short) (j + 2);

			indices[i + 3] = (short) (j + 2);
			indices[i + 4] = (short) (j + 1);
			indices[i + 5] = (short) (j + 3);
		}
		ShortBuffer buf = MapRenderer.getShortBuffer(indices.length);
		buf.put(indices);
		buf.flip();

		GL.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER,
		                mQuadIndicesID);
		GL.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER,
		                indices.length * 2, buf, GL20.GL_STATIC_DRAW);
		GL.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);

		// initialize default quad
		FloatBuffer floatBuffer = MapRenderer.getFloatBuffer(indices.length);

		float[] quad = new float[] { -1, -1, -1, 1, 1, -1, 1, 1 };
		floatBuffer.put(quad);
		floatBuffer.flip();
		mQuadVerticesID = vboIds[1];

		GL.glBindBuffer(GL20.GL_ARRAY_BUFFER, mQuadVerticesID);
		GL.glBufferData(GL20.GL_ARRAY_BUFFER,
		                quad.length * 4, floatBuffer, GL20.GL_STATIC_DRAW);
		GL.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);

		if (mClearColor != null)
			mUpdateColor = true;

		GLState.init(GL);

		mMap.updateMap(true);
	}

	public void onSurfaceCreated() {
		GL = GLAdapter.get();

		// log.debug("surface created");
		// log.debug(GL.glGetString(GL20.GL_EXTENSIONS));

		GLState.init(GL);
		GLUtils.init(GL);

		// Set up some vertex buffer objects
		BufferObject.init(GL, 200);

		// classes that require GL context for initialization
		ElementLayers.initRenderer(GL);

		LayerRenderer.init(GL);

		mNewSurface = true;
	}

	private boolean mNewSurface;

	public static final boolean debugView = false;

	public static int getQuadIndicesVBO() {
		return mQuadIndicesID;
	}

	/**
	 * Get VBO ID for a simple quad. Handy for simple custom RenderLayers
	 * Vertices: { -1, -1, -1, 1, 1, -1, 1, 1 }
	 */
	public static int getQuadVertexVBO() {
		return mQuadVerticesID;
	}

}
