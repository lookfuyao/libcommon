package com.serenegiant.net;
/*
 * Androusb
 * Copyright (c) 2014-2016 saki t_saki@serenegiant.com
 * Distributed under the terms of the GNU Lesser General Public License (LGPL v3.0) License.
 * License details are in the file license.txt, distributed as part of this software.
 */

import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;

import com.serenegiant.utils.HandlerThreadHandler;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * LAN内でのアドレス解決の為にUDPでビーコンをブロードキャストするためのクラス
 */
public class UdpBeacon {
//	private static final boolean DEBUG = BuildConfig.DEBUG && false;
	private static final String TAG = "UdpBeacon"; // UdpBeacon.class.getSimpleName();

	private static final int BEACON_UDP_PORT = 9999;
	private static final byte BEACON_VERSION = 0x01;
	public static final int BEACON_SIZE = 23;
	private static final long DEFAULT_BEACON_SEND_INTERVALS_MS = 3000;
	private static final Charset CHARSET = Charset.forName("UTF-8");

	/**
	 * ビーコン受信時のコールバック
	 */
	public interface UdpBeaconCallback {
		/**
		 * ビーコンを受信した時の処理
		 * @param uuid 受信したビーコンのUUID
		 * @param remote ビーコンのアドレス文字列
		 * @param remote_port ビーコンのポート番号
		 */
		public void onReceiveBeacon(final UUID uuid, final String remote, final int remote_port);
		/**
		 * エラー発生時の処理
		 * @param e
		 */
		public void onError(final Exception e);
	}

	/**
	 * S A K I     4 bytes
	 * version     1 byte, %x01
	 * UUID        16 bytes
	 * port        2 bytes in network order = big endian = same as usual byte order of Java
	 */
	private static class Beacon {
		public static final String BEACON_IDENTITY = "SAKI";

		private final UUID uuid;
		private final int listenPort;
		public Beacon(final ByteBuffer buffer) {
			uuid = new UUID(buffer.getLong(), buffer.getLong());
			final int port = buffer.getShort();
			listenPort = port < 0 ? (0xffff) & port : port;
		}

		public Beacon(final UUID uuid, final int port) {
			this.uuid = uuid;
			listenPort = port;
		}

		public byte[] asBytes() {
			final byte[] bytes = new byte[BEACON_SIZE];
			final ByteBuffer buffer = ByteBuffer.wrap(bytes);
			buffer.put(BEACON_IDENTITY.getBytes());
			buffer.put(BEACON_VERSION);
			buffer.putLong(uuid.getMostSignificantBits());
			buffer.putLong(uuid.getLeastSignificantBits());
			buffer.putShort((short) listenPort);
			buffer.flip();
			return bytes;
		}

		public String toString() {
			return String.format(Locale.US, "Beacon(%s,port=%d)", uuid.toString(), listenPort);
		}
	}

	private final Object mSync = new Object();
	private final CopyOnWriteArraySet<UdpBeaconCallback> mCallbacks = new CopyOnWriteArraySet<UdpBeaconCallback>();
	private Handler mAsyncHandler;
	private final UUID uuid;
	private final Beacon beacon;
	private final byte[] beaconBytes;
	private final long mBeaconIntervalsMs;
	private Thread mBeaconThread;
	private boolean mReceiveOnly;
	private volatile boolean mIsRunning;
	private volatile boolean mReleased;

	/**
	 * コンストラクタ
	 * ビーコンポート番号は9999
	 * ビーコン送信周期は3000ミリ秒
	 * @param callback
	 */
	public UdpBeacon(@Nullable final UdpBeaconCallback callback) {
		this(callback, BEACON_UDP_PORT, DEFAULT_BEACON_SEND_INTERVALS_MS, false);
	}

	/**
	 * コンストラクタ
	 * ビーコンポート番号は9999
	 * @param callback
	 * @param beacon_intervals_ms ビーコン送信周期[ミリ秒]
	 */
	public UdpBeacon(@Nullable final UdpBeaconCallback callback, final long beacon_intervals_ms) {
		this(callback, BEACON_UDP_PORT, beacon_intervals_ms, false);
	}

	/**
	 * コンストラクタ
	 * ビーコンポート番号は9999
	 * ビーコン送信周期は3000ミリ秒
	 * @param callback
	 * @param receiveOnly ビーコンを送信せずに受信だけ行うかどうか, true:ビーコン送信しない
	 */
	public UdpBeacon(@Nullable final UdpBeaconCallback callback, final boolean receiveOnly) {
		this(callback, BEACON_UDP_PORT, DEFAULT_BEACON_SEND_INTERVALS_MS, false);
	}

	/**
	 * コンストラクタ
	 * ビーコンポート番号は9999
	 * @param callback
	 * @param beacon_intervals_ms
	 * @param receiveOnly ビーコンを送信せずに受信だけ行うかどうか, true:ビーコン送信しない
	 */
	public UdpBeacon(@Nullable final UdpBeaconCallback callback, final long beacon_intervals_ms, final boolean receiveOnly) {
		this(callback, BEACON_UDP_PORT, beacon_intervals_ms, receiveOnly);
	}

	/**
	 * コンストラクタ
	 * @param callback
	 * @param port ビーコン用のポート番号
	 * @param beacon_intervals_ms ビーコン送信周期[ミリ秒], receiveOnly=trueなら無効
	 * @param receiveOnly ビーコンを送信せずに受信だけ行うかどうか, true:ビーコン送信しない
	 */
	public UdpBeacon(@Nullable final UdpBeaconCallback callback, final int port, final long beacon_intervals_ms, final boolean receiveOnly) {
//		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		if (callback != null) {
			mCallbacks.add(callback);
		}
		mAsyncHandler = HandlerThreadHandler.createHandler("UdpBeaconAsync");
		uuid = UUID.randomUUID();
		beacon = new Beacon(uuid, port);
		beaconBytes = beacon.asBytes();
		mBeaconIntervalsMs = beacon_intervals_ms;
		mReceiveOnly = receiveOnly;
	}

	public void finalize() throws Throwable {
		release();
		super.finalize();
	}

	/**
	 * ビーコンの送信・受信を停止して関係するリソースを破棄する, 再利用は出来ない
	 */
	public void release() {
//		if (DEBUG) Log.v(TAG, "release:");
		if (!mReleased) {
			mReleased = true;
			stop();
			mCallbacks.clear();
			synchronized (mSync) {
				if (mAsyncHandler != null) {
					try {
						mAsyncHandler.getLooper().quit();
					} catch (final Exception e) {
						// ignore
					}
					mAsyncHandler = null;
				}
			}
		}
	}

	public void addCallback(final UdpBeaconCallback callback) {
		if (callback != null) {
			mCallbacks.add(callback);
		}
	}

	public void removeCallback(final UdpBeaconCallback callback) {
		mCallbacks.remove(callback);
	}

	/**
	 * ビーコンの送信(receiveOnly=falseの時のみ)・受信を開始する
	 * @throws IllegalStateException 既に破棄されている
	 */
	public void start() {
//		if (DEBUG) Log.v(TAG, "start:");
		checkReleased();
		synchronized (mSync) {
			if (mBeaconThread == null) {
				mIsRunning = true;
				mBeaconThread = new Thread(mBeaconTask, "UdpBeaconTask");
				mBeaconThread.start();
			}
		}
	}

	/**
	 * ビーコンの送受信を停止する
	 */
	public void stop() {
		mIsRunning = false;
		Thread thread;
		synchronized (mSync) {
			thread = mBeaconThread;
			mBeaconThread = null;
			mSync.notifyAll();
		}
		if ((thread != null) && thread.isAlive()) {
			thread.interrupt();
			try {
				thread.join();
			} catch (final Exception e) {
				Log.d(TAG, e.getMessage());
			}
		}
	}

	/**
	 * 1回だけビーコンを送信
	 * @throws IllegalStateException 既に破棄されている
	 */
	public void shot() throws IllegalStateException {
		shot(1);
	}

	/**
	 * 指定回数だけビーコンを送信
	 * @throws IllegalStateException 既に破棄されている
	 */
	public void shot(final int n) throws IllegalStateException {
		checkReleased();
		synchronized (mSync) {
			new Thread(new BeaconShotTask(n), "UdpOneShotBeaconTask").start();
		}
	}

	/**
	 * ビーコン送受信中かどうか
	 * @return
	 */
	public boolean isActive() {
		return mIsRunning;
	}

	/**
	 * ビーコンを送信せずに受信だけ行うかどうかをセット
	 * ビーコン送受信中には変更できない。stopしてから呼ぶこと
	 * @param receiveOnly
	 * @throws IllegalStateException 破棄済みまたはビーコン送受信中ならIllegalStateExceptionを投げる
	 */
	public void setReceiveOnly(final boolean receiveOnly) throws IllegalStateException {
		checkReleased();
		synchronized (mSync) {
			if (mIsRunning) {
				throw new IllegalStateException("beacon is already active");
			}
			mReceiveOnly = receiveOnly;
		}
	}

	/**
	 * ビーコンを送信せずに受信だけ行うかどうか
	 * @return
	 */
	public boolean isReceiveOnly() {
		return mReceiveOnly;
	}

	/**
	 * 既に破棄されているかどうかをチェックして破棄済みならIllegalStateExceptionを投げる
	 * @throws IllegalStateException
	 */
	private void checkReleased() throws IllegalStateException {
		if (mReleased) {
			throw new IllegalStateException("already released");
		}
	}

	private final void callOnError(final Exception e) {
		if (mReleased) {
			Log.w(TAG, e);
			return;
		}
		synchronized (mSync) {
			if (mAsyncHandler != null) {
				mAsyncHandler.post(new Runnable() {
					@Override
					public void run() {
						for (final UdpBeaconCallback callback: mCallbacks) {
							try {
								callback.onError(e);
							} catch (final Exception e) {
								mCallbacks.remove(callback);
								Log.w(TAG, e);
							}
						}
					}
				});
			}
		}
	}

	private final class BeaconShotTask implements Runnable {
		private final int shotNums;

		public BeaconShotTask(final int shotNums) {
			this.shotNums = shotNums;
		}

		@Override
		public void run() {
			try {
				final UdpSocket socket = new UdpSocket(BEACON_UDP_PORT);
				socket.setReuseAddress(true);	// 他のソケットでも同じアドレスを利用可能にする
				socket.setSoTimeout(200);		// タイムアウト200ミリ秒
				try {
					for (int i = 0; i < shotNums; i++) {
						if (mReleased) break;
						sendBeacon(socket);
						synchronized (mSync) {
							try {
								mSync.wait(mBeaconIntervalsMs);
							} catch (final InterruptedException e) {
								break;
							}
						}
					}
				} finally {
					socket.release();
				}
			} catch (final SocketException e) {
				callOnError(e);
			}
		}
	};

	private final Runnable mBeaconTask = new Runnable() {
		@Override
		public void run() {
			final ByteBuffer buffer = ByteBuffer.allocateDirect(256);
			try {
				final UdpSocket socket = new UdpSocket(BEACON_UDP_PORT);
				socket.setReceiveBufferSize(256);
				socket.setReuseAddress(true);	// 他のソケットでも同じアドレスを利用可能にする
				socket.setSoTimeout(200);		// タイムアウト200ミリ秒
				long next_send = System.currentTimeMillis();
				try {
					for ( ; mIsRunning && !mReleased ; ) {
						// 受信のみでなければ指定時間毎にビーコン送信
						if (!mReceiveOnly && (System.currentTimeMillis() >= next_send)) {
							next_send = System.currentTimeMillis() + mBeaconIntervalsMs;
							sendBeacon(socket);
						}
						// ゲスト端末からのブロードキャストを受け取る,
						// 受け取るまでは待ち状態になる...けどタイムアウトで抜けてくる
						try {
							buffer.clear();
							final int length = socket.receive(buffer);
							if (!mIsRunning) break;
							buffer.rewind();
							if (length == BEACON_SIZE) {
								if (buffer.get() != 'S'
									|| buffer.get() != 'A'
									|| buffer.get() != 'K'
									|| buffer.get() != 'I'
									|| buffer.get() != BEACON_VERSION) {
									continue;
								}
								final Beacon remote_beacon = new Beacon(buffer);
								if (!uuid.equals(remote_beacon.uuid)) {
									// 自分のuuidと違う時
									final String remoteAddr = socket.remote();
									final int remotePort = socket.remotePort();
									synchronized (mSync) {
										if (mAsyncHandler == null) break;
										mAsyncHandler.post(new Runnable() {
											@Override
											public void run() {
												for (final UdpBeaconCallback callback: mCallbacks) {
													try {
														callback.onReceiveBeacon(remote_beacon.uuid, remoteAddr, remotePort);
													} catch (final Exception e) {
														mCallbacks.remove(callback);
														Log.w(TAG, e);
													}
												}
											}
										});
									}
								}
							}
						} catch (final IOException e) {
							// タイムアウトで抜けてきた時, 無視する
						}
					}
				} finally {
					socket.release();
				}
			} catch (final SocketException e) {
				callOnError(e);
			}
			mIsRunning = false;
			synchronized (mSync) {
				mBeaconThread = null;
			}
		}
	};

	/**
	 * UDPでビーコンをブロードキャストする
	 */
	private void sendBeacon(final UdpSocket socket) {
//		if (DEBUG) Log.v(TAG, "sendBeacon");
		try {
			socket.broadcast(beaconBytes);
		} catch (final IOException e) {
			Log.w(TAG, e);
		}
	}

}
