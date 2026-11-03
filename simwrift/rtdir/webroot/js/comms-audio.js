/* ============================================================
   SimWeaver Rift - Comms audio (0.12.0): plays the channels the user
   ticks on the Comms page.

   One stream per page: GET /api/audio/stream?ds=<id>&channels=a,b,c,
   read with fetch() and a ReadableStream. The server sends binary
   frames (little endian, 32-octet header, then PCM16 LE mono):
     0 u8 version (1)   1 u8 type (0 keep-alive, 1 audio)
     2 u8 encoding: 1 = PCM16 LE; 2 = one Opus packet (0.12.1), decoded here with WebCodecs
     4 u16 channel (position in channels=)   8 u32 source   12 u32 sample rate
     16 u32 sequence   20 u32 payload length   24 f64 server time (ms)
   Changing the channels opens a new stream and aborts the old one.

   Playback: Web Audio. Each (channel, source) has its own play-out
   clock with a small jitter buffer; the browser resamples (an
   AudioBuffer may have any common rate) and mixes the sources. A
   gain per channel (volume) and a master gain.

   var a = new Rift.CommsAudio({ dsid, onStatus(s, detail), onLevel(channelId, level) });
   a.setChannels(['f-251000000', 't-...']);     // [] stops
   a.setVolume(channelId, 0..1); a.setMaster(0..1);
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift = window.Rift || {};

	var HEADER = 32;
	var JITTER = 0.12;           // seconds of play-out buffer when a source (re)starts
	var MAX_AHEAD = 0.8;         // more than this queued: start again from now (latency stays low)
	var RESTART_MS = 250;        // debounce of channel changes
	var STALL_MS = 8000;         // no frame (not even a keep-alive) this long: reconnect

	function CommsAudio(h) {
		this.h = h || {};
		this.ctx = null;
		this.master = null;
		this.masterLevel = 0.8;
		this.gains = {};          // channel id -> GainNode
		this.volumes = {};        // channel id -> 0..1
		this.clocks = {};         // "channel:source" -> next start time
		this.channels = [];       // what is listened to
		this.streamChannels = []; // the channels= of the open stream (frame channel numbers)
		this.abort = null;
		this.timer = null;
		this.retry = 2000;
		this.lastFrame = 0;
		this.watchdog = null;
		this.status = 'off';
		this.frames = 0;
		this.decoders = {};       // "channel:source" -> Opus decoder state (0.12.1)
		this.opusErrors = 0;
	}

	CommsAudio.supported = function () {
		return !!(window.AudioContext || window.webkitAudioContext) && !!window.fetch && !!window.ReadableStream;
	};

	/**
	 * 0.12.1: can this browser decode Opus (WebCodecs AudioDecoder)? A promise of true / false.
	 * Edge and Chrome 94 or later can; Firefox from version 130; older browsers cannot.
	 */
	CommsAudio.opusSupported = function () {
		if (!window.AudioDecoder || !window.EncodedAudioChunk) { return Promise.resolve(false); }
		try {
			return window.AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 })
				.then(function (r) { return !!(r && r.supported); }, function () { return false; });
		} catch (e) {
			return Promise.resolve(false);
		}
	};

	/** Call from a click (browsers start audio only after a user gesture). */
	CommsAudio.prototype.unlock = function () {
		if (!this.ctx) {
			var AC = window.AudioContext || window.webkitAudioContext;
			this.ctx = new AC();
			this.master = this.ctx.createGain();
			this.master.gain.value = this.masterLevel;
			this.master.connect(this.ctx.destination);
		}
		if (this.ctx.state === 'suspended') { this.ctx.resume(); }
	};

	CommsAudio.prototype.setStatus = function (s, detail) {
		this.status = s;
		if (this.h.onStatus) { this.h.onStatus(s, detail || ''); }
	};

	CommsAudio.prototype.setChannels = function (ids) {
		var self = this;
		this.channels = ids.slice();
		clearTimeout(this.timer);
		this.timer = setTimeout(function () { self.restart(); }, RESTART_MS);
	};

	CommsAudio.prototype.setMaster = function (v) {
		this.masterLevel = v;
		if (this.master) { this.master.gain.value = v; }
	};

	CommsAudio.prototype.setVolume = function (id, v) {
		this.volumes[id] = v;
		if (this.gains[id]) { this.gains[id].gain.value = v; }
	};

	CommsAudio.prototype.gainOf = function (id) {
		var g = this.gains[id];
		if (!g) {
			g = this.ctx.createGain();
			g.gain.value = this.volumes[id] === undefined ? 1 : this.volumes[id];
			g.connect(this.master);
			this.gains[id] = g;
		}
		return g;
	};

	CommsAudio.prototype.stop = function () {
		this.channels = [];
		this.restart();
	};

	CommsAudio.prototype.restart = function () {
		var self = this;
		if (this.abort) { this.abort.abort(); this.abort = null; }
		clearInterval(this.watchdog);
		this.clocks = {};
		this.closeDecoders();
		if (!this.channels.length) { this.setStatus('off'); return; }
		var list = this.channels.slice();
		var ctl = new AbortController();
		this.abort = ctl;
		this.streamChannels = list;
		this.setStatus('connecting');
		var url = '/api/audio/stream?ds=' + encodeURIComponent(this.h.dsid) + '&channels=' + list.map(encodeURIComponent).join(',');
		this.lastFrame = Date.now();
		this.watchdog = setInterval(function () {
			if (self.abort === ctl && Date.now() - self.lastFrame > STALL_MS) { self.restart(); }
		}, 2000);
		fetch(url, { credentials: 'same-origin', cache: 'no-store', signal: ctl.signal }).then(function (res) {
			if (!res.ok) {
				return res.text().then(function (t) {
					var msg = 'HTTP ' + res.status;
					try { var j = JSON.parse(t); if (j && j.error && j.error.message) { msg = j.error.message; } } catch (e) { /* not JSON */ }
					throw { http: res.status, message: msg };
				});
			}
			self.retry = 2000;
			self.setStatus('live');
			return self.read(res.body.getReader(), ctl, list);
		}).catch(function (err) {
			if (ctl.signal.aborted || self.abort !== ctl) { return; }
			var msg = err && err.message ? err.message : String(err);
			self.setStatus('error', err && err.http === 401 ? 'Not signed in' : msg);
			if (err && (err.http === 400 || err.http === 401 || err.http === 404)) { return; }    // no point retrying
			clearTimeout(self.timer);
			self.timer = setTimeout(function () { self.restart(); }, self.retry);
			self.retry = Math.min(10000, self.retry * 1.6);
		});
	};

	CommsAudio.prototype.read = function (reader, ctl, list) {
		var self = this;
		var buf = new Uint8Array(0);
		function pump() {
			return reader.read().then(function (r) {
				if (r.done) { throw new Error('the stream ended'); }
				if (self.abort !== ctl) { return; }
				buf = concat(buf, r.value);
				var off = 0;
				while (buf.length - off >= HEADER) {
					var dv = new DataView(buf.buffer, buf.byteOffset + off, HEADER);
					if (dv.getUint8(0) !== 1) { throw new Error('unknown audio frame version ' + dv.getUint8(0)); }
					var len = dv.getUint32(20, true);
					if (buf.length - off < HEADER + len) { break; }
					self.lastFrame = Date.now();
					var enc = dv.getUint8(2);
					if (dv.getUint8(1) === 1 && len >= 1) {
						var ch = list[dv.getUint16(4, true)];
						var payload = buf.subarray(off + HEADER, off + HEADER + len);
						if (ch && enc === 1 && len >= 2) {
							self.play(ch, dv.getUint32(8, true), dv.getUint32(12, true), payload);
						} else if (ch && enc === 2) {
							self.opus(ch, dv.getUint32(8, true), payload.slice());
						}
					}
					off += HEADER + len;
				}
				buf = off ? buf.slice(off) : buf;
				return pump();
			});
		}
		return pump();
	};

	function concat(a, b) {
		if (!a.length) { return b; }
		var c = new Uint8Array(a.length + b.length);
		c.set(a, 0);
		c.set(b, a.length);
		return c;
	}

	/** PCM16 LE: to float samples, then scheduled. */
	CommsAudio.prototype.play = function (ch, source, rate, bytes) {
		var n = bytes.length >> 1;
		var f = new Float32Array(n);
		var dv = new DataView(bytes.buffer, bytes.byteOffset, n * 2);
		for (var i = 0; i < n; i++) { f[i] = dv.getInt16(i * 2, true) / 32768; }
		this.schedule(ch, source, rate, f);
	};

	/* ---------- Opus (0.12.1): one WebCodecs AudioDecoder per channel and sender ---------- */

	/** The channel count of an Opus packet: its TOC byte's stereo bit (RFC 6716, 3.1). */
	function opusChannels(packet) {
		return packet.length && (packet[0] & 0x04) ? 2 : 1;
	}

	CommsAudio.prototype.opus = function (ch, source, packet) {
		if (!this.ctx || !window.AudioDecoder || !packet.length) { return; }
		var self = this;
		var key = ch + ':' + source;
		var channels = opusChannels(packet);
		var d = this.decoders[key];
		if (d && (d.channels !== channels || d.decoder.state === 'closed')) {
			try { d.decoder.close(); } catch (e) { /* closed already */ }
			d = null;
		}
		if (!d) {
			d = { channels: channels, ts: 0 };
			try {
				d.decoder = new window.AudioDecoder({
					output: function (data) { self.decoded(ch, source, data); },
					error: function (e) {
						self.opusErrors++;
						if (self.h.onDecodeError) { self.h.onDecodeError(ch, e && e.message ? e.message : String(e)); }
						delete self.decoders[key];                 // a new decoder with the next packet
					}
				});
				d.decoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: channels });
			} catch (e) {
				this.opusErrors++;
				if (this.h.onDecodeError) { this.h.onDecodeError(ch, e && e.message ? e.message : String(e)); }
				return;
			}
			this.decoders[key] = d;
		}
		try {
			d.decoder.decode(new window.EncodedAudioChunk({ type: 'key', timestamp: d.ts, data: packet }));
			d.ts += 20000;                                     // microseconds; only the order matters
		} catch (e) {
			this.opusErrors++;
			if (this.h.onDecodeError) { this.h.onDecodeError(ch, e && e.message ? e.message : String(e)); }
		}
	};

	/** A decoded Opus frame: mixed down to mono and scheduled like PCM. */
	CommsAudio.prototype.decoded = function (ch, source, data) {
		try {
			var n = data.numberOfFrames, c = data.numberOfChannels;
			var mono = new Float32Array(n);
			var plane = new Float32Array(n);
			for (var k = 0; k < c; k++) {
				// WebCodecs converts any sample format to f32-planar on copy
				data.copyTo(plane, { planeIndex: k, format: 'f32-planar' });
				for (var j = 0; j < n; j++) { mono[j] += plane[j] / c; }
			}
			this.schedule(ch, source, data.sampleRate || 48000, mono);
		} catch (e) {
			this.opusErrors++;
			if (this.h.onDecodeError) { this.h.onDecodeError(ch, e && e.message ? e.message : String(e)); }
		} finally {
			data.close();
		}
	};

	CommsAudio.prototype.closeDecoders = function () {
		var self = this;
		Object.keys(this.decoders).forEach(function (k) {
			try { self.decoders[k].decoder.close(); } catch (e) { /* closed already */ }
		});
		this.decoders = {};
	};

	/** Float samples of one channel and sender into the play-out clock. */
	CommsAudio.prototype.schedule = function (ch, source, rate, samples) {
		if (!this.ctx || rate < 3000 || rate > 192000 || !samples.length) { return; }
		var n = samples.length;
		var buffer;
		try {
			buffer = this.ctx.createBuffer(1, n, rate);
		} catch (e) {
			return;                                            // a rate this browser cannot take
		}
		buffer.getChannelData(0).set(samples);
		var sum = 0;
		for (var i = 0; i < n; i++) { sum += samples[i] * samples[i]; }
		var key = ch + ':' + source;
		var now = this.ctx.currentTime;
		var at = this.clocks[key];
		if (at === undefined || at < now + 0.01 || at > now + MAX_AHEAD) { at = now + JITTER; }
		var node = this.ctx.createBufferSource();
		node.buffer = buffer;
		node.connect(this.gainOf(ch));
		node.start(at);
		this.clocks[key] = at + buffer.duration;
		this.frames++;
		if (this.h.onLevel) { this.h.onLevel(ch, Math.sqrt(sum / Math.max(1, n))); }
	};

	Rift.CommsAudio = CommsAudio;
})(window);
