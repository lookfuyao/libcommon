package com.serenegiant.mediaeffect;
/*
 * Androusb
 * Copyright (c) 2014-2016 saki t_saki@serenegiant.com
 * Distributed under the terms of the GNU Lesser General Public License (LGPL v3.0) License.
 * License details are in the file license.txt, distributed as part of this software.
 */

import com.serenegiant.glutils.TextureOffscreen;

public class MediaEffectGLESTwoPassBase extends MediaEffectGLESBase {

	protected final MediaEffectKernel3x3Drawer mDrawer2;
	protected TextureOffscreen mOutputOffscreen2;

	public MediaEffectGLESTwoPassBase(final boolean isOES, final String fss) {
		super(isOES, fss);
		mDrawer2 = null;
	}

	public MediaEffectGLESTwoPassBase(final String vss, final String fss) {
		super(false, vss, fss);
		mDrawer2 = null;
	}

	public MediaEffectGLESTwoPassBase(final boolean isOES, final String vss, final String fss) {
		super(isOES, vss, fss);
		mDrawer2 = null;
	}

	public MediaEffectGLESTwoPassBase(final boolean isOES, final String vss1, final String fss1, final String vss2, final String fss2) {
		super(isOES, vss1, fss1);
		if (!vss1.equals(vss2) || !fss1.equals(fss2)) {
			mDrawer2 = new MediaEffectKernel3x3Drawer(isOES, vss2, fss2);
		} else {
			mDrawer2 = null;
		}
	}

	@Override
	public void release() {
		if (mDrawer2 != null) {
			mDrawer2.release();
		}
		if (mOutputOffscreen2 != null) {
			mOutputOffscreen2.release();
			mOutputOffscreen2 = null;
		}
		super.release();
	}

	@Override
	public MediaEffectGLESBase resize(final int width, final int height) {
		super.resize(width, height);
		// ISourceを使う時は出力用オフスクリーンは不要なのと
		// ISourceを使わない時は描画時にチェックして生成するのでresize時には生成しないように変更
/*		if ((mOutputOffscreen2 == null) || (width != mOutputOffscreen2.getWidth())
			|| (height != mOutputOffscreen2.getHeight())) {
			if (mOutputOffscreen2 != null)
				mOutputOffscreen2.release();
			mOutputOffscreen2 = new TextureOffscreen(width, height, false);
		} */
		if (mDrawer2 != null) {
			mDrawer2.setTexSize(width, height);
		}
		return this;
	}

	/**
	 * If you know the source texture came from MediaSource,
	 * using #apply(MediaSource) is much efficient instead of this
	 * @param src_tex_ids
	 * @param width
	 * @param height
	 * @param out_tex_id
	 */
	@Override
	public void apply(final int [] src_tex_ids, final int width, final int height, final int out_tex_id) {
		if (!mEnabled) return;
		// パス1
		if (mOutputOffscreen == null) {
			mOutputOffscreen = new TextureOffscreen(width, height, false);
		}
		mOutputOffscreen.bind();
		try {
			mDrawer.apply(src_tex_ids[0], mOutputOffscreen.getTexMatrix(), 0);
		} finally {
			mOutputOffscreen.unbind();
		}

		if (mOutputOffscreen2 == null) {
			mOutputOffscreen2 = new TextureOffscreen(width, height, false);
		}
		// パス2
		if ((out_tex_id != mOutputOffscreen2.getTexture())
			|| (width != mOutputOffscreen2.getWidth())
			|| (height != mOutputOffscreen2.getHeight())) {
			mOutputOffscreen2.assignTexture(out_tex_id, width, height);
		}
		mOutputOffscreen2.bind();
		try {
			if (mDrawer2 != null) {
				mDrawer2.apply(mOutputOffscreen.getTexture(), mOutputOffscreen2.getTexMatrix(), 0);
			} else {
				mDrawer.apply(mOutputOffscreen.getTexture(), mOutputOffscreen2.getTexMatrix(), 0);
			}
		} finally {
			mOutputOffscreen2.unbind();
		}
	}

	@Override
	public void apply(final ISource src) {
		if (!mEnabled) return;
		final TextureOffscreen output_tex = src.getOutputTexture();
		final int[] src_tex_ids = src.getSourceTexId();
		final int width = src.getWidth();
		final int height = src.getHeight();
		// パス1
		if (mOutputOffscreen == null) {
			mOutputOffscreen = new TextureOffscreen(width, height, false);
		}
		mOutputOffscreen.bind();
		try {
			mDrawer.apply(src_tex_ids[0], mOutputOffscreen.getTexMatrix(), 0);
		} finally {
			mOutputOffscreen.unbind();
		}
		// パス2
		output_tex.bind();
		try {
			if (mDrawer2 != null) {
				mDrawer2.apply(mOutputOffscreen.getTexture(), output_tex.getTexMatrix(), 0);
			} else {
				mDrawer.apply(mOutputOffscreen.getTexture(), output_tex.getTexMatrix(), 0);
			}
		} finally {
			output_tex.unbind();
		}
	}
}
