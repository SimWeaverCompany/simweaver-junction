/* ============================================================
   SimWeaver Rift - interaction log, "comms tape" (right panel).

   - Rate ticker: one row per interaction class, rate/s and a 60 s sparkline from
     'interaction.stats'. Click = filter on that class (toggle, several allowed).
   - Live tape, newest first: Zulu time with ms, delta to the previous row, class badge,
     TSO + logical time, summary, object chips (cross-link to the object list).
   - Burst folding: same class + producer within 1 s -> one row "x23".
   - Scroll lock / pause: new rows wait in a buffer behind a "N new" banner.
   - Focus mode: only interactions that reference one object.
   - Row click expands the full parameters (GET .../interactions/{id}).

   var log = new Rift.InteractionLog({ dsid, stream, els: {...}, onSelectObject: fn(target) });
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var vv = Rift.valueView;

	var HISTORY = 60;          // seconds in the sparkline
	var FOLD_MS = 1000;        // burst folding window
	var MAX_ROWS = 400;        // rows kept in the tape

	function hue(s) {
		var h = 0;
		for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) % 3600; }
		return Math.round(h / 10);
	}

	function shortName(q) {
		var p = String(q || '').split('.');
		return p[p.length - 1];
	}

	function deltaText(ms) {
		if (ms == null || isNaN(ms)) { return ''; }
		if (ms < 0) { ms = 0; }
		if (ms < 10000) { return '+' + (ms / 1000).toFixed(3) + 's'; }
		if (ms < 60000) { return '+' + (ms / 1000).toFixed(1) + 's'; }
		if (ms < 3600000) { return '+' + Math.round(ms / 60000) + 'm'; }
		return '+' + Math.round(ms / 3600000) + 'h';
	}

	function InteractionLog(opts) {
		this.dsid = opts.dsid;
		this.stream = opts.stream;
		this.els = opts.els;     // ticker, scroller, tape, banner, pause, focus, meta, clear
		this.opts = opts;
		this.ticker = {};        // classnameq -> { hist:[], total }
		this.selected = new Set();
		this.focusTarget = null;
		this.paused = false;
		this.pending = [];
		this.groups = [];        // newest first
		this.loading = false;
		this.queue = [];
		this.snapSeq = 0;
		this.token = 0;
		this.rate = 0;
		this.dropped = 0;
		this.seen = new Set();   // interaction ids: drops duplicates (connection overlap)
		this.seenOrder = [];
		this.wire();
	}

	InteractionLog.prototype.wire = function () {
		var self = this, e = this.els;
		e.pause.addEventListener('click', function () { self.setPaused(!self.paused); });
		e.banner.addEventListener('click', function () { self.setPaused(false); self.backToLive(); });
		e.clear.addEventListener('click', function () {
			self.selected.clear();
			self.focusTarget = null;
			self.renderFocus();
			self.renderTicker();
			self.filterChanged();
		});
		// Cache "at the top" from scroll events: reading scrollTop per incoming
		// interaction would force a layout of the whole page every time.
		this.atTop = true;
		e.scroller.addEventListener('scroll', function () {
			var top = e.scroller.scrollTop < 8;
			if (top === self.atTop) { return; }
			self.atTop = top;
			if (top && !self.paused && self.pending.length) { self.backToLive(); }
			self.updateMeta();
		}, { passive: true });
	};

	/*
	 * The hub sends every interaction of the datasource (topic "ds:<id>:interactions");
	 * class and object filters are applied here. History reloads use the same filters
	 * server-side (GET .../interactions?class=...&object=...).
	 */
	InteractionLog.prototype.matches = function (it) {
		if (this.selected.size) {
			var q = String(it.classnameq || '');
			var hit = false;
			this.selected.forEach(function (c) { if (q === c || q.indexOf(c + '.') === 0) { hit = true; } });
			if (!hit) { return false; }
		}
		if (this.focusTarget) {
			var id = this.focusTarget.instanceid;
			return (it.parameters || []).some(function (p) {
				var r = p.value && p.value.ref;
				return r && Array.isArray(r.targets) && r.targets.some(function (t) { return t && t.instanceid === id; });
			});
		}
		return true;
	};

	InteractionLog.prototype.filterChanged = function () {
		this.els.clear.hidden = !(this.selected.size || this.focusTarget);
		this.reload();
	};

	InteractionLog.prototype.setFocus = function (target) {
		this.focusTarget = target;
		this.renderFocus();
		this.filterChanged();
	};

	InteractionLog.prototype.renderFocus = function () {
		var self = this, box = ui.clear(this.els.focus);
		box.hidden = !this.focusTarget;
		if (!this.focusTarget) { return; }
		box.appendChild(el('span.dim', { text: 'Only with ' }));
		box.appendChild(el('button.chip', {
			type: 'button', title: 'Go to this object',
			on: { click: function () { if (self.opts.onSelectObject) { self.opts.onSelectObject(self.focusTarget); } } }
		}, [el('span.chip-dot', { 'aria-hidden': 'true' }), this.focusTarget.label || this.focusTarget.instanceid,
			el('span.chip-class', { text: this.focusTarget.classname || '' })]));
		box.appendChild(el('button.btn', {
			type: 'button', 'aria-label': 'Remove object filter',
			on: { click: function () { self.setFocus(null); } }
		}, '✕'));
	};

	/* ---------- history ---------- */

	InteractionLog.prototype.reload = function () {
		var self = this;
		var token = ++this.token;
		this.loading = true;
		this.queue = [];
		this.pending = [];
		this.groups = [];
		this.updateBanner();
		var tape = ui.clear(this.els.tape);
		tape.appendChild(el('li', null, ui.loadingRow('Acquiring interaction log…')));
		var p = { limit: 200, 'class': Array.from(this.selected), object: this.focusTarget ? this.focusTarget.instanceid : null };
		Rift.api.fetchInteractions(this.dsid, p).then(function (reply) {
			if (token !== self.token) { return; }
			ui.clear(tape);
			self.snapSeq = reply.seq || 0;
			var list = (reply.interactions || []).slice().reverse();   // oldest first
			self.seen = new Set(); self.seenOrder = [];
			list.forEach(function (it) { self.remember(it); self.add(it, false); self.noteClass(it.classnameq); });
			self.loading = false;
			self.renderEmpty();
			var q = self.queue;
			self.queue = [];
			q.forEach(function (it) { if (!it.seq || it.seq > self.snapSeq) { self.incoming(it); } });
			self.renderTicker();
			self.updateMeta();
		}, function (err) {
			if (token !== self.token) { return; }
			self.loading = false;
			ui.clear(tape).appendChild(el('li', null, err.status === 404
				? ui.emptyBlock('Interaction log not available', 'The server does not provide the interaction log yet.')
				: ui.errorRow('Could not load the interaction log', err, function () { self.reload(); })));
		});
	};

	InteractionLog.prototype.renderEmpty = function () {
		var tape = this.els.tape;
		var empty = tape.querySelector('.ix-empty');
		if (this.groups.length) { if (empty) { tape.removeChild(empty); } return; }
		if (empty) { return; }
		var filtered = this.selected.size || this.focusTarget;
		tape.appendChild(el('li.ix-empty', null, ui.emptyBlock(
			filtered ? 'No interactions match the filter' : 'No interactions received yet',
			filtered ? 'New ones appear here as they arrive.' : 'The tape fills as soon as the federation sends interactions.')));
	};

	/* ---------- live ---------- */

	InteractionLog.prototype.onEvent = function (name, data) {
		if (name === 'interaction.stats') { this.onStats(data); return; }
		if (name !== 'interaction') { return; }
		var it = data.interaction || data;   // publish(topic, "interaction", {...}) - wrapped or bare
		if (!it || !it.classnameq) { return; }
		if (this.loading) { this.queue.push(it); return; }
		if (it.seq && this.snapSeq && it.seq <= this.snapSeq) { return; }
		this.incoming(it);
	};

	InteractionLog.prototype.remember = function (it) {
		var id = it.interactionid || it.seq;
		if (id == null) { return true; }
		if (this.seen.has(id)) { return false; }
		this.seen.add(id);
		this.seenOrder.push(id);
		if (this.seenOrder.length > 3000) { this.seen.delete(this.seenOrder.shift()); }
		return true;
	};

	InteractionLog.prototype.incoming = function (it) {
		if (!this.remember(it)) { return; }
		this.noteClass(it.classnameq);
		if (!this.matches(it)) { return; }
		if (this.paused || !this.atTop) {
			this.pending.push(it);
			if (this.pending.length > 2000) { this.pending.shift(); }
			this.updateBanner();
			return;
		}
		this.add(it, true);
		this.renderEmpty();
	};

	InteractionLog.prototype.setPaused = function (p) {
		this.paused = p;
		this.els.pause.textContent = p ? '▶ Live' : '❚❚ Pause';
		this.els.pause.classList.toggle('on', p);
		this.els.pause.setAttribute('aria-pressed', p ? 'true' : 'false');
		this.updateMeta();
		if (!p && this.atTop) { this.backToLive(); }
	};

	InteractionLog.prototype.backToLive = function () {
		var self = this, list = this.pending;
		this.pending = [];
		list.forEach(function (it) { self.add(it, false); });
		this.updateBanner();
		this.els.scroller.scrollTop = 0;
		this.atTop = true;
		this.renderEmpty();
	};

	InteractionLog.prototype.updateBanner = function () {
		var b = this.els.banner, n = this.pending.length;
		b.hidden = n === 0;
		b.textContent = '▲ ' + n + ' new — back to live';
	};

	InteractionLog.prototype.updateMeta = function () {
		var m = ui.clear(this.els.meta);
		var live = !this.paused && this.atTop;
		m.appendChild(el('span.ix-live' + (live ? '.on' : ''), { text: live ? 'LIVE' : 'HOLD' }));
		m.appendChild(el('span', { text: ' · ' + this.rate + '/s' }));
		if (this.dropped) {
			m.appendChild(el('span.ix-sampling', { text: ' · sampling', title: this.dropped + ' interactions/s not sent to this page (rate cap)' }));
		}
	};

	/* ---------- folding + rows ---------- */

	/** Adds an interaction at the top; folds it into the newest row when it is a burst. */
	InteractionLog.prototype.add = function (it, animate) {
		var t = Date.parse(it.received);
		var top = this.groups[0];
		if (top && top.classnameq === it.classnameq && top.producer === it.producer && !it.correlation &&
			!top.correlation && !isNaN(t) && t - top.lastT <= FOLD_MS) {
			top.entries.push(it);
			top.lastT = t;
			this.fillRow(top);
			if (animate) { this.flash(top.li); }
			return;
		}
		var g = { classnameq: it.classnameq, producer: it.producer, correlation: it.correlation,
			entries: [it], firstT: t, lastT: t, prevT: top ? top.lastT : null, open: false };
		g.li = el('li.ix-row', { style: '--h:' + hue(it.classnameq || '') });
		this.fillRow(g);
		this.groups.unshift(g);
		this.els.tape.insertBefore(g.li, this.els.tape.firstChild);
		while (this.groups.length > MAX_ROWS) {
			var old = this.groups.pop();
			if (old.li.parentNode) { old.li.parentNode.removeChild(old.li); }
		}
		if (animate) { this.flash(g.li); }
	};

	InteractionLog.prototype.flash = function (node) { ui.pulse(node); };

	function chipsFor(it) {
		var seen = {}, wrap = el('span.chips');
		(it.parameters || []).forEach(function (p) {
			var ref = p.value && p.value.ref;
			if (!ref || !Array.isArray(ref.targets)) { return; }
			ref.targets.forEach(function (t) {
				if (!t || seen[t.instanceid]) { return; }
				seen[t.instanceid] = true;
				wrap.appendChild(el('button.chip', {
					type: 'button', title: p.name + ': ' + t.classname,
					on: { click: function (e) { e.stopPropagation(); if (vv.onSelect) { vv.onSelect(t); } } }
				}, [el('span.chip-dot', { 'aria-hidden': 'true' }), t.label || t.instanceid]));
			});
		});
		return wrap.firstChild ? wrap : null;
	}

	function summaryOf(it) {
		if (it.summary) { return it.summary; }
		return (it.parameters || []).slice(0, 3).map(function (p) {
			return p.name + ' ' + vv.text(p.value);
		}).join(' · ');
	}

	InteractionLog.prototype.fillRow = function (g) {
		var self = this;
		var it = g.entries[g.entries.length - 1];   // newest of the group
		var li = ui.clear(g.li);
		var n = g.entries.length;
		var main = el('div.ix-main', {
			role: 'button', tabindex: '0', 'aria-expanded': g.open ? 'true' : 'false',
			on: {
				click: function () { self.toggleRow(g); },
				keydown: function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); self.toggleRow(g); } }
			}
		}, [
			el('span.ix-time', { text: ui.zulu(it.received).replace(/Z$/, ''), title: it.received }),
			el('span.ix-delta', { text: g.prevT != null ? deltaText(g.lastT - g.prevT) : '', title: 'Time since the previous row' }),
			el('span.ix-badge', { text: it.classname || shortName(it.classnameq), title: it.classnameq }),
			n > 1 ? el('span.ix-count', { text: '×' + n, title: n + ' within ' + ((g.lastT - g.firstT) / 1000).toFixed(2) + ' s' }) : null,
			it.sentorder === 'TSO' ? el('span.ix-order', { text: 'TSO' + (it.logicaltime != null ? ' ' + it.logicaltime : ''), title: 'Sent time-stamp ordered' }) : null,
			it.correlation ? el('span.ix-corr', { text: it.correlation.label || it.correlation.key, title: 'Correlation ' + it.correlation.key }) : null,
			el('span.ix-summary', { text: summaryOf(it) }),
			chipsFor(it)
		]);
		li.appendChild(main);
		if (g.open) { li.appendChild(this.renderDetail(g)); }
	};

	InteractionLog.prototype.toggleRow = function (g) {
		g.open = !g.open;
		this.fillRow(g);
	};

	InteractionLog.prototype.renderDetail = function (g) {
		var box = el('div.ix-detail');
		var it = g.entries[g.entries.length - 1];
		box.appendChild(el('div.obj-meta', null, [
			el('span.mi', null, [el('span.mi-label', { text: 'CLASS' }), el('code', { text: it.classnameq })]),
			it.producer ? el('span.mi', null, [el('span.mi-label', { text: 'PRODUCER' }), el('span', { text: it.producer })]) : null,
			el('span.mi', null, [el('span.mi-label', { text: 'RECEIVED' }), el('span', { text: ui.zulu(it.received) })]),
			it.sentorder ? el('span.mi', null, [el('span.mi-label', { text: 'ORDER' }), el('span', { text: it.sentorder + (it.logicaltime != null ? ' @ ' + it.logicaltime : '') })]) : null
		]));
		if (g.entries.length > 1) {
			var times = el('div.ix-burst');
			times.appendChild(el('span.mi-label', { text: 'BURST · ' + g.entries.length }));
			g.entries.slice().reverse().slice(0, 50).forEach(function (e) {
				times.appendChild(el('span.ix-burst-t', { text: ui.zulu(e.received).replace(/Z$/, '') }));
			});
			box.appendChild(times);
		}
		var params = el('div.at-table');
		params.appendChild(el('div', null, ui.loadingRow('Acquiring parameters…')));
		box.appendChild(params);
		Rift.api.fetchInteraction(this.dsid, it.interactionid).then(function (reply) {
			render(reply.interaction);
		}, function () {
			render(it);   // fall back to the shallow nodes we have
		});
		function render(full) {
			ui.clear(params);
			(full.parameters || []).forEach(function (p) {
				params.appendChild(el('div.at-row', { 'data-name': p.name }, [
					el('div.at-name', { title: p.datatype }, [p.name, el('span.at-type', { text: p.datatype })]),
					el('div.at-value', null, vv.tree(p.value, null, p.name, new Set())),
					el('div.at-side', null, p.rawsize != null ? el('span.dim', { text: p.rawsize + ' B' }) : null)
				]));
			});
			if (!(full.parameters || []).length) { params.appendChild(el('div.dim', { text: 'No parameters.' })); }
		}
		return box;
	};

	/* ---------- ticker ---------- */

	InteractionLog.prototype.noteClass = function (q) {
		if (!q || this.ticker[q]) { return; }
		var hist = [];
		for (var i = 0; i < HISTORY; i++) { hist.push(0); }
		this.ticker[q] = { hist: hist, total: 0 };
		this.tickerDirty = true;
	};

	InteractionLog.prototype.onStats = function (d) {
		var counts = d.counts || {}, self = this;
		Object.keys(counts).forEach(function (q) { self.noteClass(q); });
		var sum = 0;
		Object.keys(this.ticker).forEach(function (q) {
			var c = Number(counts[q]) || 0;
			var t = self.ticker[q];
			t.hist.push(c);
			if (t.hist.length > HISTORY) { t.hist.shift(); }
			t.total = t.hist.reduce(function (a, b) { return a + b; }, 0);
			sum += c;
		});
		this.rate = Math.round(sum / (d.window || 1));
		this.dropped = d.dropped || 0;
		this.renderTicker();
		this.updateMeta();
	};

	function spark(hist) {
		var max = Math.max.apply(null, hist.concat([1]));
		var w = 120, h = 18, step = w / (hist.length - 1);
		var pts = hist.map(function (v, i) { return (i * step).toFixed(1) + ',' + (h - 1 - (v / max) * (h - 2)).toFixed(1); }).join(' ');
		var NS = 'http://www.w3.org/2000/svg';
		var svg = document.createElementNS(NS, 'svg');
		svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
		svg.setAttribute('preserveAspectRatio', 'none');
		svg.setAttribute('class', 'ix-spark');
		svg.setAttribute('aria-hidden', 'true');
		var area = document.createElementNS(NS, 'polygon');
		area.setAttribute('points', '0,' + h + ' ' + pts + ' ' + w + ',' + h);
		area.setAttribute('class', 'ix-spark-area');
		var line = document.createElementNS(NS, 'polyline');
		line.setAttribute('points', pts);
		line.setAttribute('class', 'ix-spark-line');
		svg.appendChild(area);
		svg.appendChild(line);
		return svg;
	}

	InteractionLog.prototype.renderTicker = function () {
		var self = this, box = ui.clear(this.els.ticker);
		var qs = Object.keys(this.ticker);
		if (!qs.length) {
			box.appendChild(el('div.ix-ticker-empty', { text: 'Rates per interaction class appear here.' }));
			return;
		}
		qs.sort(function (a, b) { return (self.ticker[b].total - self.ticker[a].total) || a.localeCompare(b); });
		qs.forEach(function (q) {
			var t = self.ticker[q];
			var now = t.hist[t.hist.length - 1];
			var sel = self.selected.has(q);
			box.appendChild(el('button.ix-tick' + (sel ? '.sel' : '') + (self.selected.size && !sel ? '.dimmed' : ''), {
				type: 'button', style: '--h:' + hue(q), 'aria-pressed': sel ? 'true' : 'false',
				title: q + ' — ' + t.total + ' in the last ' + HISTORY + ' s. Click to filter.',
				on: { click: function () {
					if (self.selected.has(q)) { self.selected.delete(q); } else { self.selected.add(q); }
					self.renderTicker();
					self.filterChanged();
				} }
			}, [
				el('span.ix-swatch', { 'aria-hidden': 'true' }),
				el('span.ix-tname', { text: shortName(q) }),
				spark(t.hist),
				el('span.ix-rate', { text: String(now) }),
				el('span.ix-unit', { text: '/s' })
			]));
		});
	};

	InteractionLog.prototype.resync = function () { this.reload(); };

	Rift.InteractionLog = InteractionLog;
})(window);
