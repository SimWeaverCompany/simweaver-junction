/* ============================================================
   SimWeaver Rift - live updates over the backend's XrSseHub (xrest).

   The hub's model: one EventSource per page on GET /api/events?topics=a,b,c.
   The topic set is fixed for the lifetime of a connection and matched exactly.
     - publish(topic, name, json)  -> event "<name>" (not coalesced)
     - publishPatch(topic, fields) -> event "cards-changed": [{"id": topic, "patch": {...}}]
                                      (coalesced per topic and field per batch interval)
   There is no hello, no event id and no replay: after any (re)connect the page reloads
   its snapshots.

   var stream = new Rift.LiveStream({
     topics:          function () { return ['ds:...', ...]; },
     onReady:         function (reason) {},        // 'start' or 'reconnect': reload snapshots
     onTopicsChanged: function (added, removed) {},// new topic set is live
     onPatch:         function (topic, patch) {},
     onEvent:         function (name, data) {},
     onStatus:        function ('connecting'|'live'|'lost') {}
   });
   stream.start();
   stream.refreshTopics();   // debounced: call whenever the wanted topics may have changed

   Topic changes "make before break": the new connection is opened first and the old one
   closed when the new one is open, so nothing is missed in between. Events arriving twice
   during that overlap are filtered by the consumers (object rev, interaction id).
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;

	var NAMED_EVENTS = ['interaction', 'interaction.stats', 'datasource.state', 'datasource.removed'];
	var DEBOUNCE_MS = 400;

	function LiveStream(h) {
		this.h = h || {};
		this.current = null;        // { es, topics, key }
		this.pending = null;        // connection being opened
		this.status = 'connecting';
		this.needResync = true;     // first open = 'start'
		this.started = false;
		this.timer = null;
		this.retryTimer = null;
		this.retryDelay = 3000;
	}

	function keyOf(topics) { return topics.slice().sort().join(','); }

	function diff(a, b) {
		var set = {};
		b.forEach(function (t) { set[t] = true; });
		return a.filter(function (t) { return !set[t]; });
	}

	LiveStream.prototype.setStatus = function (s) {
		if (this.status === s) { return; }
		this.status = s;
		if (this.h.onStatus) { this.h.onStatus(s); }
	};

	LiveStream.prototype.start = function () {
		this.started = true;
		this.connect(this.h.topics());
	};

	LiveStream.prototype.stop = function () {
		clearTimeout(this.timer);
		clearTimeout(this.retryTimer);
		[this.current, this.pending].forEach(function (c) { if (c) { c.es.close(); } });
		this.current = this.pending = null;
	};

	/** Debounced: reconnects only when the wanted topic set actually changed. */
	LiveStream.prototype.refreshTopics = function () {
		var self = this;
		if (!this.started) { return; }
		clearTimeout(this.timer);
		this.timer = setTimeout(function () {
			var topics = self.h.topics();
			var key = keyOf(topics);
			var target = self.pending || self.current;
			if (target && target.key === key) { return; }
			self.connect(topics);
		}, DEBOUNCE_MS);
	};

	LiveStream.prototype.connect = function (topics) {
		var self = this;
		if (typeof EventSource === 'undefined') { this.setStatus('lost'); return; }
		if (this.pending) { this.pending.es.close(); }
		var url = Rift.api.endpoints.events(topics);
		var conn = { es: new EventSource(url), topics: topics.slice(), key: keyOf(topics) };
		this.pending = conn;
		if (!this.current) { this.setStatus('connecting'); }

		conn.es.addEventListener('open', function () {
			if (self.pending === conn) {
				// The new topic set is live: retire the previous connection.
				var prev = self.current;
				self.current = conn;
				self.pending = null;
				if (prev) { prev.es.close(); }
				clearTimeout(self.retryTimer);
				self.retryDelay = 3000;
				self.setStatus('live');
				Rift.api.reportLink(true, url);
				if (self.needResync) {
					var reason = self.firstDone ? 'reconnect' : 'start';
					self.needResync = false;
					self.firstDone = true;
					if (self.h.onReady) { self.h.onReady(reason); }
				} else if (prev && self.h.onTopicsChanged) {
					self.h.onTopicsChanged(diff(conn.topics, prev.topics), diff(prev.topics, conn.topics));
				}
			} else if (self.current === conn) {
				// The browser re-opened this connection after a drop: state may be stale.
				self.setStatus('live');
				Rift.api.reportLink(true, url);
				if (self.needResync) { self.needResync = false; if (self.h.onReady) { self.h.onReady('reconnect'); } }
			}
		});

		conn.es.addEventListener('error', function () {
			if (conn !== self.current && conn !== self.pending) { return; }   // retired connection
			if (conn === self.pending && self.current) {
				// Topic switch failed: keep the old connection, try again later.
				conn.es.close();
				self.pending = null;
				self.retryLater(conn.topics);
				return;
			}
			self.needResync = true;
			self.setStatus('lost');
			Rift.api.reportLink(false, url);
			// CLOSED = HTTP error (e.g. 503 while the backend restarts): the browser gives up.
			// CONNECTING = the browser retries by itself; the watchdog covers both.
			self.retryLater(conn.topics);
		});

		conn.es.addEventListener('cards-changed', function (e) {
			var batch;
			try { batch = JSON.parse(e.data); } catch (err) { return; }
			if (!Array.isArray(batch) || !self.h.onPatch) { return; }
			batch.forEach(function (item) {
				if (item && item.id && item.patch) { self.h.onPatch(String(item.id), item.patch); }
			});
		});

		NAMED_EVENTS.forEach(function (name) {
			conn.es.addEventListener(name, function (e) {
				var data;
				try { data = JSON.parse(e.data); } catch (err) { data = {}; }
				if (self.h.onEvent) { self.h.onEvent(name, data || {}); }
			});
		});
	};

	/** Reconnect with back-off (3 s x1.6, max 30 s) if no connection is live by then. */
	LiveStream.prototype.retryLater = function () {
		var self = this;
		clearTimeout(this.retryTimer);
		this.retryTimer = setTimeout(function () {
			var live = self.current && self.current.es.readyState === 1 && self.status === 'live';
			if (live && !self.pending) {
				self.refreshTopics();   // a failed topic switch: try the switch again
				return;
			}
			if (live) { return; }
			self.retryDelay = Math.min(30000, Math.round(self.retryDelay * 1.6));
			if (self.current) { self.current.es.close(); self.current = null; }
			self.needResync = true;
			self.connect(self.h.topics());
		}, this.retryDelay);
	};

	/** Server and browser clocks are assumed to be in sync (NTP); the hub sends no time. */
	LiveStream.prototype.serverNow = function () { return Date.now(); };

	Rift.LiveStream = LiveStream;
})(window);
