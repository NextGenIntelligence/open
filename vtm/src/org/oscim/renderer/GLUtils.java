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
package org.oscim.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.oscim.backend.GL20;
import org.oscim.backend.GLAdapter;
import org.oscim.utils.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility functions
 */
public class GLUtils {
	static final Logger log = LoggerFactory.getLogger(GLUtils.class);

	private static GL20 GL;

	static void init(GL20 gl) {
		GL = gl;
	}

	public static void setColor(int location, int color, float alpha) {
		if (alpha >= 1)
			alpha = ((color >>> 24) & 0xff) / 255f;
		else if (alpha < 0)
			alpha = 0;
		else
			alpha *= ((color >>> 24) & 0xff) / 255f;

		if (alpha == 1) {
			GL.glUniform4f(location,
			               ((color >>> 16) & 0xff) / 255f,
			               ((color >>> 8) & 0xff) / 255f,
			               ((color >>> 0) & 0xff) / 255f,
			               alpha);
		} else {
			GL.glUniform4f(location,
			               ((color >>> 16) & 0xff) / 255f * alpha,
			               ((color >>> 8) & 0xff) / 255f * alpha,
			               ((color >>> 0) & 0xff) / 255f * alpha,
			               alpha);
		}
	}

	public static void setColorBlend(int location, int color1, int color2, float mix) {
		float a1 = (((color1 >>> 24) & 0xff) / 255f) * (1 - mix);
		float a2 = (((color2 >>> 24) & 0xff) / 255f) * mix;
		GL.glUniform4f(location,
		               ((((color1 >>> 16) & 0xff) / 255f) * a1
		               + (((color2 >>> 16) & 0xff) / 255f) * a2),
		               ((((color1 >>> 8) & 0xff) / 255f) * a1
		               + (((color2 >>> 8) & 0xff) / 255f) * a2),
		               ((((color1 >>> 0) & 0xff) / 255f) * a1
		               + (((color2 >>> 0) & 0xff) / 255f) * a2),
		               (a1 + a2));
	}

	public static void setTextureParameter(int min_filter, int mag_filter, int wrap_s, int wrap_t) {
		GL.glTexParameterf(GL20.GL_TEXTURE_2D,
		                   GL20.GL_TEXTURE_MIN_FILTER,
		                   min_filter);
		GL.glTexParameterf(GL20.GL_TEXTURE_2D,
		                   GL20.GL_TEXTURE_MAG_FILTER,
		                   mag_filter);
		GL.glTexParameterf(GL20.GL_TEXTURE_2D,
		                   GL20.GL_TEXTURE_WRAP_S,
		                   wrap_s); // Set U Wrapping
		GL.glTexParameterf(GL20.GL_TEXTURE_2D,
		                   GL20.GL_TEXTURE_WRAP_T,
		                   wrap_t); // Set V Wrapping
	}

	public static int loadTexture(byte[] pixel, int width, int height, int format,
	        int min_filter, int mag_filter, int wrap_s, int wrap_t) {

		int[] textureIds = GLUtils.glGenTextures(1);
		GLState.bindTex2D(textureIds[0]);

		setTextureParameter(min_filter, mag_filter, wrap_s, wrap_t);

		ByteBuffer buf = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder());
		buf.put(pixel);
		buf.position(0);
		IntBuffer intBuf = buf.asIntBuffer();
		GL.glTexImage2D(GL20.GL_TEXTURE_2D, 0, format, width, height, 0, format,
		                GL20.GL_UNSIGNED_BYTE, intBuf);

		GLState.bindTex2D(0);

		return textureIds[0];
	}

	public static int loadStippleTexture(byte[] stipple) {
		int sum = 0;
		for (byte flip : stipple)
			sum += flip;

		byte[] pixel = new byte[sum];

		boolean on = true;
		int pos = 0;
		for (byte flip : stipple) {
			float max = flip;

			for (int s = 0; s < flip; s++) {
				float color = Math.abs(s / (max - 1) - 0.5f);
				if (on)
					color = 255 * (1 - color);
				else
					color = 255 * color;

				pixel[pos + s] = FastMath.clampToByte((int) color);
			}
			on = !on;
			pos += flip;
		}

		return loadTexture(pixel, sum, 1, GL20.GL_ALPHA,
		                   GL20.GL_LINEAR, GL20.GL_LINEAR,
		                   // GLES20.GL_NEAREST, GLES20.GL_NEAREST,
		                   GL20.GL_REPEAT,
		                   GL20.GL_REPEAT);
	}

	/**
	 * @param shaderType
	 *            shader type
	 * @param source
	 *            shader code
	 * @return gl identifier
	 */
	public static int loadShader(int shaderType, String source) {

		if (GLAdapter.GDX_DESKTOP_QUIRKS) {
			// Strip precision modifer
			int start = source.indexOf("precision");
			if (start >= 0) {
				int end = source.indexOf(';', start) + 1;
				if (start > 0)
					source = source.substring(0, start) + source.substring(end);
				else
					source = source.substring(end);
			}
		}

		int shader = GL.glCreateShader(shaderType);
		if (shader != 0) {
			GL.glShaderSource(shader, source);
			GL.glCompileShader(shader);
			IntBuffer compiled = MapRenderer.getIntBuffer(1);

			GL.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, compiled);
			compiled.position(0);
			if (compiled.get() == 0) {
				log.error("Could not compile shader " + shaderType + ":");
				log.error(GL.glGetShaderInfoLog(shader));
				GL.glDeleteShader(shader);
				shader = 0;
			}
		}
		return shader;
	}

	/**
	 * @param vertexSource
	 *            ...
	 * @param fragmentSource
	 *            ...
	 * @return gl identifier
	 */
	public static int createProgram(String vertexSource, String fragmentSource) {
		int vertexShader = loadShader(GL20.GL_VERTEX_SHADER, vertexSource);
		if (vertexShader == 0) {
			return 0;
		}

		int pixelShader = loadShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);
		if (pixelShader == 0) {
			return 0;
		}

		int program = GL.glCreateProgram();
		if (program != 0) {
			checkGlError("glCreateProgram");
			GL.glAttachShader(program, vertexShader);
			checkGlError("glAttachShader");
			GL.glAttachShader(program, pixelShader);
			checkGlError("glAttachShader");
			GL.glLinkProgram(program);
			IntBuffer linkStatus = MapRenderer.getIntBuffer(1);
			GL.glGetProgramiv(program, GL20.GL_LINK_STATUS, linkStatus);
			linkStatus.position(0);
			if (linkStatus.get() != GL20.GL_TRUE) {
				log.error("Could not link program: ");
				log.error(GL.glGetProgramInfoLog(program));
				GL.glDeleteProgram(program);
				program = 0;
			}
		}
		return program;
	}

	/**
	 * @param op
	 *            ...
	 */
	public static void checkGlError(String op) {
		//GL = GLAdapter.get();

		int error;
		while ((error = GL.glGetError()) != 0) { // GL20.GL_NO_ERROR) {
			log.error(op + ": glError " + error);
			// throw new RuntimeException(op + ": glError " + error);
		}
	}

	public static boolean checkGlOutOfMemory(String op) {
		int error;
		boolean oom = false;
		while ((error = GL.glGetError()) != 0) {// GL20.GL_NO_ERROR) {
			log.error(op + ": glError " + error);
			// throw new RuntimeException(op + ": glError " + error);
			if (error == 1285)
				oom = true;
		}
		return oom;
	}

	public static void setColor(int handle, float[] c, float alpha) {
		if (alpha >= 1) {
			GL.glUniform4f(handle, c[0], c[1], c[2], c[3]);
		} else {
			if (alpha < 0) {
				log.debug("setColor: " + alpha);
				alpha = 0;
				GL.glUniform4f(handle, 0, 0, 0, 0);
			}

			GL.glUniform4f(handle,
			               c[0] * alpha, c[1] * alpha,
			               c[2] * alpha, c[3] * alpha);
		}
	}

	public static float[] colorToFloat(int color) {
		float[] c = new float[4];
		c[3] = (color >> 24 & 0xff) / 255.0f;
		c[0] = (color >> 16 & 0xff) / 255.0f;
		c[1] = (color >> 8 & 0xff) / 255.0f;
		c[2] = (color >> 0 & 0xff) / 255.0f;
		return c;
	}

	// premultiply alpha
	public static float[] colorToFloatP(int color) {
		float[] c = new float[4];
		c[3] = (color >> 24 & 0xff) / 255.0f;
		c[0] = (color >> 16 & 0xff) / 255.0f * c[3];
		c[1] = (color >> 8 & 0xff) / 255.0f * c[3];
		c[2] = (color >> 0 & 0xff) / 255.0f * c[3];
		return c;
	}

	/**
	 * public-domain function by Darel Rex Finley from
	 * http://alienryderflex.com/saturation.html
	 * 
	 * @param color
	 *            The passed-in RGB values can be on any desired scale, such as
	 *            0 to 1, or 0 to 255.
	 * @param change
	 *            0.0 creates a black-and-white image. 0.5 reduces the color
	 *            saturation by half. 1.0 causes no change. 2.0 doubles the
	 *            color saturation.
	 */
	public static void changeSaturation(float color[], float change) {
		float r = color[0];
		float g = color[1];
		float b = color[2];
		double p = Math.sqrt(r * r * 0.299f + g * g * 0.587f + b * b * 0.114f);
		color[0] = FastMath.clampN((float) (p + (r - p) * change));
		color[1] = FastMath.clampN((float) (p + (g - p) * change));
		color[2] = FastMath.clampN((float) (p + (b - p) * change));
	}

	public static void glUniform4fv(int location, int count, float[] val) {
		FloatBuffer buf = MapRenderer.getFloatBuffer(count * 4);
		buf.put(val);
		buf.flip();
		GL.glUniform4fv(location, count, buf);
	}

	public static int[] glGenBuffers(int num) {
		IntBuffer buf = MapRenderer.getIntBuffer(num);
		buf.position(0);
		buf.limit(num);
		GL.glGenBuffers(num, buf);
		int[] ret = new int[num];
		buf.position(0);
		buf.limit(num);
		buf.get(ret);
		return ret;
	}

	public static void glDeleteBuffers(int num, int[] ids) {
		IntBuffer buf = MapRenderer.getIntBuffer(num);
		buf.put(ids, 0, num);
		buf.flip();
		GL.glDeleteBuffers(num, buf);
	}

	public static int[] glGenTextures(int num) {
		if (num <= 0)
			return null;

		int[] ret = new int[num];
		IntBuffer buf = MapRenderer.getIntBuffer(num);

		if (GLAdapter.GDX_WEBGL_QUIRKS) {
			for (int i = 0; i < num; i++) {
				GL.glGenTextures(num, buf);
				buf.position(0);
				ret[i] = buf.get();
				buf.position(0);
			}
		} else {
			GL.glGenTextures(num, buf);
			buf.position(0);
			buf.get(ret);
		}

		return ret;
	}

	public static void glDeleteTextures(int num, int[] ids) {
		IntBuffer buf = MapRenderer.getIntBuffer(num);
		buf.put(ids, 0, num);
		buf.flip();
		GL.glDeleteTextures(num, buf);
	}
}
