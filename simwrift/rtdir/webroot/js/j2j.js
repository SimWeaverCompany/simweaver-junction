/* ============================================================
   SimWeaver J2J page (0.10.0): the members of this server's J2J
   network, how each is reached, the network diagram, the sessions
   and the J2J events.

   Data: GET /api/j2j... (js/j2j-api.js) once, then the SSE topic
   "j2j" (status and members every 2 s, topology on change, events
   as they are logged). Without live updates the page polls.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jj = Rift.j2j;

	var SVGNS = 'http://www.w3.org/2000/svg';
	var HIST = 30;                     // ping samples kept per member (sparkline)
	var EVENTS_KEPT = 200;
	var STALE_MS = 6000;               // no live status for this long: poll instead
	var POLL_MS = 2000;
	var SESSIONS_MS = 5000;
	var AMBER_MS = 150, RED_MS = 400;  // fixed ping colours (design D13)

	var BADGE = {
		self:        ['◆ self', 'self'],
		direct:      ['● direct', 'direct'],
		routed:      ['◐ routed', 'routed'],
		connecting:  ['◌ connecting', 'wait'],
		waiting:     ['○ waiting', 'wait'],
		unreachable: ['✕ unreachable', 'bad'],
		refused:     ['✕ refused', 'bad'],
		notdialled:  ['○ waits for it', 'wait'],
		notstarted:  ['· not started', 'off']
	};

	var st = {
		status: null,          // GET /j2j
		members: [],           // GET /j2j/members
		topo: null,            // GET /j2j/topology
		sessions: [],
		events: [],
		details: {},           // id -> GET /j2j/members/{id}
		open: {},              // id -> expanded
		pings: {},             // id -> {busy, text, cls}
		hist: {},              // id -> [rtt ms]
		rows: {},              // id -> row nodes
		filter: '',
		level: 'all',
		overlay: 'links',
		lastLive: 0,
		lastState: null,
		eventsLoaded: false
	};

	function $(id) { return document.getElementById(id); }

	function text(node, value) {
		var v = value === null || value === undefined || value === '' ? '—' : String(value);
		if (node.textContent !== v) { node.textContent = v; }
	}

	function plain(node, value) {
		var v = value === null || value === undefined ? '' : String(value);
		if (node.textContent !== v) { node.textContent = v; }
	}

	function show(node, on) {
		if (node instanceof window.SVGElement) { node.style.display = on ? '' : 'none'; } else { node.hidden = !on; }
	}

	function configured() {
		return !!(st.status && st.status.state !== 'off' && st.status.network);
	}

	function running() { return !!(st.status && st.status.state === 'running'); }

	function pingClass(v) {
		return v === null || v === undefined ? 'jj-p-none' : v > RED_MS ? 'jj-p-red' : v > AMBER_MS ? 'jj-p-amber' : 'jj-p-green';
	}

	function ms(v) {
		return v === null || v === undefined ? '—' : (v < 100 ? v.toFixed(1) : Math.round(v)) + ' ms';
	}

	function num(v) {
		return v === null || v === undefined ? '—' : String(v).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
	}

	function mb(bytes) {
		return bytes === null || bytes === undefined ? '—' : Math.round(bytes / 1048576) + ' MB';
	}

	/** "52 min", "3 h 12 min", "6 d 2 h" since an ISO time */
	function since(iso) {
		var t = Date.parse(iso);
		if (isNaN(t)) { return null; }
		var s = Math.max(0, Math.round((Date.now() - t) / 1000));
		if (s < 60) { return s + ' s'; }
		var m = Math.floor(s / 60), h = Math.floor(m / 60), d = Math.floor(h / 24);
		return d ? d + ' d ' + (h % 24) + ' h' : h ? h + ' h ' + (m % 60) + ' min' : m + ' min';
	}

	function zt(iso) { return iso ? ui.zulu(iso, false) : '—'; }

	function svg(tag, attrs) {
		var n = document.createElementNS(SVGNS, tag);
		Object.keys(attrs || {}).forEach(function (k) {
			if (attrs[k] !== null && attrs[k] !== undefined) { n.setAttribute(k, String(attrs[k])); }
		});
		return n;
	}

	function fault(title, err, retry) {
		var host = $('jj-fault');
		ui.clear(host).appendChild(ui.errorRow(title, err, retry));
		show(host, true);
	}

	/* ============================================================
	   Loading
	   ============================================================ */

	function loadAll() {
		return jj.status().then(function (s) {
			show($('jj-fault'), false);
			applyStatus(s);
			if (!configured()) { return null; }
			var calls = [loadMembers(), loadTopology()];
			if (running()) { calls.push(loadSessions()); }
			if (!st.eventsLoaded) { calls.push(loadEvents()); }
			return Promise.all(calls);
		}, function (err) {
			show($('jj-loading'), false);
			if (err && err.status === 404) {
				fault('This server has no J2J', err, null);
			} else {
				fault('The J2J state cannot be read', err, loadAll);
			}
		});
	}

	function quiet(err) {
		// J2J_OFF while the state changes is expected: the next status shows why
		if (err && err.code !== 'J2J_OFF' && window.console) { window.console.warn('J2J:', err.message); }
	}

	function loadMembers() { return jj.members().then(applyMembers, quiet); }

	function loadTopology() { return jj.topology().then(applyTopology, quiet); }

	function loadSessions() { return jj.sessions().then(applySessions, quiet); }

	function loadEvents() {
		return jj.events(100).then(function (list) {
			st.eventsLoaded = true;
			var known = {};
			st.events.forEach(function (e) { known[e.at + e.text] = true; });
			list.forEach(function (e) { if (!known[e.at + e.text]) { st.events.push(e); } });
			st.events.sort(function (a, b) { return Date.parse(b.at) - Date.parse(a.at); });
			st.events = st.events.slice(0, EVENTS_KEPT);
			renderEvents();
		}, quiet);
	}

	function loadDetail(id) {
		return jj.member(id).then(function (m) {
			if (m) { st.details[id] = m; renderDetail(id); }
		}, function (err) {
			if (err && err.code === 'UNKNOWN_MEMBER') { delete st.details[id]; }
			quiet(err);
		});
	}

	/* ============================================================
	   Live updates
	   ============================================================ */

	function startLive() {
		var url = jj.endpoints.live();
		jj.live({
			events: {
				'j2j.status': function (d) {
					st.lastLive = Date.now();
					Rift.api.reportLink(true, url);
					if (d && typeof d === 'object') { applyStatus(d); }
				},
				'j2j.members': function (d) {
					if (d && d.members) { applyMembers(d.members); }
				},
				'j2j.topology': function (d) {
					if (d && typeof d === 'object') { applyTopology(d); }
				},
				'j2j.event': function (d) {
					if (d && typeof d === 'object') { addEvent(d); }
				}
			},
			onOpen: function () { Rift.api.reportLink(true, url); }
		});
		// the fallback: poll while live updates do not arrive
		window.setInterval(function () {
			if (document.hidden || Date.now() - st.lastLive < STALE_MS) { return; }
			jj.status().then(function (s) {
				applyStatus(s);
				if (configured()) { loadMembers(); loadTopology(); }
			}, function () { /* the link lamp shows it */ });
		}, POLL_MS);
		window.setInterval(function () {
			if (!document.hidden && running()) { loadSessions(); }
		}, SESSIONS_MS);
		window.setInterval(function () {
			if (document.hidden || !configured()) { return; }
			Object.keys(st.open).forEach(function (id) { if (st.open[id]) { loadDetail(id); } });
		}, POLL_MS);
	}

	/* ============================================================
	   Status: which panels, the network facts
	   ============================================================ */

	function applyStatus(s) {
		var prev = st.lastState;
		st.status = s;
		st.lastState = s.state + '|' + (s.network ? s.network.memberhash : '') + '|' + (s.self ? s.self.id : '');
		show($('jj-loading'), false);
		renderStatus();
		if (prev !== null && prev !== st.lastState) {
			// off <-> on, another member list or another id: read everything again
			st.details = {};
			if (configured()) {
				loadMembers();
				loadTopology();
				if (running()) { loadSessions(); }
				if (!st.eventsLoaded) { loadEvents(); }
			} else {
				st.members = [];
				st.topo = null;
			}
		}
	}

	function renderStatus() {
		var s = st.status;
		var on = configured();
		var run = running();
		show($('jj-off'), !on);
		show($('jj-net'), on);
		show($('jj-diagram'), run);
		show($('jj-members'), on);
		show($('jj-lower'), on);
		if (!on) {
			document.title = 'SimWeaver J2J - off';
			return;
		}
		var n = s.network || {};
		document.title = 'SimWeaver J2J - ' + (n.id || '');
		text($('jj-net-title'), 'Network · ' + (n.id || '?'));
		plain($('jj-net-name'), n.name && n.name !== n.id ? n.name : '');
		var badge = $('jj-net-badge');
		badge.className = 'jj-badge ' + (s.state === 'running' ? 'jj-st-direct' : 'jj-st-bad');
		text(badge, s.state === 'running' ? '● running' : '✕ error');

		var self = s.self || {};
		ui.clear($('jj-self'));
		ui.append($('jj-self'), [el('b.mono.jj-selfid', { text: self.id || '?' }), self.name && self.name !== self.id ? ' · ' + self.name : '']);

		var l = s.listen || {};
		var parts = [];
		if (l.tcp) { parts.push((l.udp ? 'tcp + udp ' : 'tcp ') + l.tcp); }
		if (l.fallback) { parts.push('tls ' + l.fallback); }
		if (l.fallbackproxy) { parts.push('proxy ' + l.fallbackproxy); }
		text($('jj-listen'), run ? (parts.join(' · ') || 'nothing') : 'not started');

		text($('jj-keyinfo'), n.keyid ? n.keyid + ' · ' + n.keyfingerprint + (n.keys > 1 ? ' (' + n.keys + ' keys)' : '') : 'no key');

		var li = $('jj-listinfo');
		ui.clear(li);
		ui.append(li, [n.memberhash || '?', ' · ']);
		if (!run) {
			ui.append(li, el('span.dim', { text: n.members + ' members' }));
		} else if (n.known <= 1) {
			ui.append(li, el('span.dim', { text: 'no other list known yet' }));
		} else if (n.agree === n.known) {
			ui.append(li, el('span.jj-ok', { text: 'all ' + n.known + ' known lists agree' }));
		} else {
			ui.append(li, el('span.jj-warn', { text: n.agree + ' of ' + n.known + ' known lists agree ⚠',
				title: 'A member with another member list may route differently: give every server the same network.settings' }));
		}

		if (!run) {
			st.sessions = [];
			text($('jj-session-count'), '');
			ui.clear($('jj-session-list')).appendChild(ui.emptyBlock('No sessions', 'J2J does not run on this server.'));
		}
		show($('jj-counts-fact'), run);
		var c = s.counts || {};
		var ce = ui.clear($('jj-counts'));
		ui.append(ce, [
			el('span.jj-ok', { text: (c.direct || 0) + ' direct' }), ' · ',
			el('span.jj-blue', { text: (c.routed || 0) + ' routed' }), ' · ',
			el(c.notreachable ? 'span.jj-warn' : 'span.dim', { text: (c.notreachable || 0) + ' not reachable' }),
			c.sessions ? ' · ' + c.sessions + (c.sessions === 1 ? ' session' : ' sessions') : ''
		]);

		var probs = (s.problems || []).concat(s.listen && s.listen.problems ? s.listen.problems : []);
		var box = $('jj-problems');
		ui.clear(box);
		if (probs.length) {
			box.className = 'jj-alert' + (run ? ' jj-alert-warn' : '');
			ui.append(box, el('span.jj-alert-tag', { text: run ? 'Warnings' : 'J2J did not start' }));
			probs.forEach(function (p) { ui.append(box, el('span', { text: p })); });
			if (!run) {
				ui.append(box, el('span.dim', { text: 'Fix the files and reload the member list. The members below are as the file lists them; nothing is connected.' }));
			}
		}
		show(box, probs.length > 0);
	}

	/* ============================================================
	   Members
	   ============================================================ */

	function applyMembers(list) {
		st.members = list || [];
		st.members.forEach(function (m) {
			if (m.rttms === null || m.rttms === undefined || m.self) { return; }
			var h = st.hist[m.id] || (st.hist[m.id] = []);
			h.push(m.rttms);
			if (h.length > HIST) { h.shift(); }
		});
		renderMembers();
		renderDiagram();
	}

	function matches(m) {
		var q = st.filter.trim().toLowerCase();
		if (!q) { return true; }
		return [m.id, m.name, m.site, m.address, (m.addresses || []).join(' '), m.fallback].join(' ').toLowerCase().indexOf(q) >= 0;
	}

	function renderMembers() {
		var host = $('jj-member-rows');
		var seen = {};
		var shown = 0;
		text($('jj-member-count'), st.members.length + ' in etc/j2j/network.settings');
		st.members.forEach(function (m) {
			seen[m.id] = true;
			var row = st.rows[m.id] || makeRow(m.id);
			host.appendChild(row.wrap);           // keeps the order; moving keeps focus
			fillRow(row, m);
			var vis = matches(m);
			show(row.wrap, vis);
			shown += vis ? 1 : 0;
		});
		Object.keys(st.rows).forEach(function (id) {
			if (!seen[id]) {
				host.removeChild(st.rows[id].wrap);
				delete st.rows[id];
				delete st.open[id];
				delete st.details[id];
			}
		});
		show($('jj-nomatch'), st.members.length > 0 && shown === 0);
	}

	function makeRow(id) {
		var r = {};
		r.caret = el('span.jj-caret', { 'aria-hidden': 'true' });
		r.badge = el('span.jj-badge');
		r.id = el('b.mono');
		r.warn = el('span.jj-warn.jj-small', { text: '  ⚠ list differs', title: 'Its member list differs from ours' });
		r.name = el('span.jj-name');
		r.reached = el('span.mono.jj-small');
		r.reachedSub = el('span.jj-subtext');
		r.lock = lockIcon();
		r.how = el('span');
		r.ping = el('span.mono.jj-ping');
		r.spark = svg('svg', { width: 56, height: 18, viewBox: '0 0 56 18', 'aria-hidden': 'true', 'class': 'jj-spark' });
		r.line = svg('polyline', { fill: 'none', 'stroke-width': 1.3 });
		r.spark.appendChild(r.line);
		r.used = el('span.mono.jj-small');
		r.btn = el('button.jj-mt-row', { type: 'button', 'aria-expanded': 'false', on: { click: function () { toggle(id); } } }, [
			r.caret,
			el('span', null, r.badge),
			el('span.jj-cell2', null, [el('span', null, [r.id, r.warn]), r.name]),
			el('span.jj-cell2', null, [r.reached, r.reachedSub]),
			el('span.mono.jj-small.jj-how', null, [r.lock, r.how]),
			el('span.jj-pingcell', null, [r.ping, r.spark]),
			r.used
		]);
		r.detail = el('div.jj-detail', { hidden: true });
		r.wrap = el('div.jj-mrow', { id: 'row-' + id }, [r.btn, r.detail]);
		st.rows[id] = r;
		return r;
	}

	function lockIcon() {
		var s = svg('svg', { width: 12, height: 12, viewBox: '0 0 12 12', role: 'img', 'aria-label': 'Encrypted', 'class': 'jj-lock' });
		s.appendChild(svg('rect', { x: 2, y: 5.5, width: 8, height: 5.5, rx: 1 }));
		s.appendChild(svg('path', { d: 'M4 5.5V4a2 2 0 0 1 4 0v1.5' }));
		var t = svg('title');
		t.textContent = 'Encrypted (AES-256-GCM)';
		s.appendChild(t);
		return s;
	}

	function reachedOf(m) {
		var first = m.addresses && m.addresses.length ? m.addresses[0] : (m.fallback || 'no address');
		switch (m.state) {
			case 'self': return [first, 'this server'];
			case 'direct': return [m.address, (m.direction === 'in' ? 'dialled in' : 'dialled') + (m.since ? ', up ' + since(m.since) : '')];
			case 'routed': return ['via ' + m.via, (m.hops || '?') + ' hops' + (m.addresses && m.addresses.length ? '' : ' · no inbound address')];
			case 'waiting': return [first, (m.retryseconds !== undefined ? 'retry in ' + m.retryseconds + ' s' : 'waiting') + (m.statemessage ? ' · ' + m.statemessage : '')];
			case 'connecting': return [first, 'connecting'];
			case 'refused': return [first, m.statemessage || 'refused'];
			case 'notdialled': return [m.addresses && m.addresses.length ? m.addresses[0] : 'no address', (m.statemessage || 'waits for it to dial in').replace(/^no address: /, '')];
			case 'unreachable': return [first, m.statemessage || 'no path'];
			default: return [first, 'not started'];
		}
	}

	function howOf(m) {
		if (m.state === 'routed') { return 'routed'; }
		if (m.state !== 'direct') { return null; }
		var kinds = (m.channels || []).filter(function (c) { return c.state === 'up'; }).map(function (c) {
			return c.kind === 'tls443' ? 'TLS ' + (String(c.address || '').split(':').pop() || '443') : c.kind.toUpperCase();
		});
		return kinds.join(' + ') || 'TCP';
	}

	function fillRow(r, m) {
		var open = !!st.open[m.id];
		text(r.caret, open ? '▾' : '▸');
		r.btn.setAttribute('aria-expanded', open ? 'true' : 'false');
		r.btn.className = 'jj-mt-row' + (open ? ' open' : '');
		var b = BADGE[m.state] || [m.state, 'off'];
		r.badge.className = 'jj-badge jj-st-' + b[1];
		text(r.badge, b[0]);
		text(r.id, m.id);
		r.id.className = 'mono' + (m.self ? ' jj-selfid' : '');
		show(r.warn, m.memberlist === 'different');
		plain(r.name, m.name !== m.id ? m.name : (m.site || ''));
		var re = reachedOf(m);
		text(r.reached, re[0]);
		r.reached.title = 'Configured: ' + ((m.addresses || []).join(', ') || 'no address') + (m.fallback ? ' · fallback ' + m.fallback : '');
		text(r.reachedSub, re[1]);
		var how = howOf(m);
		text(r.how, how);
		show(r.lock, !!m.encrypted && !!how);
		var pc = pingClass(m.rttms);
		text(r.ping, m.self ? '—' : ms(m.rttms));
		r.ping.className = 'mono jj-ping ' + pc;
		var h = st.hist[m.id];
		if (h && h.length > 1 && m.rttms !== null && m.rttms !== undefined) {
			var max = Math.max.apply(null, h) * 1.05, min = Math.min.apply(null, h) * 0.95;
			r.line.setAttribute('points', h.map(function (v, i) {
				return (56 - (h.length - 1 - i) * 56 / (HIST - 1)).toFixed(1) + ',' + (17 - (v - min) / (max - min || 1) * 16).toFixed(1);
			}).join(' '));
			r.spark.setAttribute('class', 'jj-spark ' + pc);
			show(r.spark, true);
		} else {
			show(r.spark, false);
		}
		var g = m.users && m.users.gateways ? m.users.gateways.length : 0;
		text(r.used, g ? g + (g === 1 ? ' gateway' : ' gateways') : '—');
		r.used.className = 'mono jj-small' + (g ? ' jj-accent' : ' dim');
		show(r.detail, open);
		if (open && !st.details[m.id]) { renderDetail(m.id); }
	}

	function toggle(id) {
		st.open[id] = !st.open[id];
		var m = memberById(id);
		if (m) { fillRow(st.rows[id], m); }
		if (st.open[id]) { renderDetail(id); loadDetail(id); }
	}

	function memberById(id) {
		for (var i = 0; i < st.members.length; i++) { if (st.members[i].id === id) { return st.members[i]; } }
		return null;
	}

	function openMember(id) {
		if (st.filter) { st.filter = ''; $('jj-filter').value = ''; renderMembers(); }
		if (!st.open[id]) { toggle(id); }
		var r = st.rows[id];
		if (r) {
			r.wrap.scrollIntoView({ behavior: 'smooth', block: 'start' });
			r.btn.focus({ preventScroll: true });
		}
	}

	/* ---------- the expanded row ---------- */

	function kv(k, children) {
		return [el('span.jj-k', { text: k }), el('div.jj-dl', null, children)];
	}

	function line(t, cls) { return el('span.mono.jj-small' + (cls ? '.' + cls : ''), { text: t }); }

	function renderDetail(id) {
		var r = st.rows[id];
		var m = memberById(id);
		if (!r || !m || !st.open[id]) { return; }
		var d = st.details[id] || m;
		var focused = document.activeElement && r.detail.contains(document.activeElement) ? document.activeElement.getAttribute('data-act') : null;
		var col1 = el('div.jj-dcol');
		var col2 = el('div.jj-dcol');
		var col3 = el('div.jj-dcol');

		// addresses, attempts, channels, route
		var att = (d.attempts || []).map(function (a) {
			return el('span.mono.jj-small.jj-att', null, [
				el('span', { text: a.address }), el('span.dim', { text: a.kind }),
				el('span', { 'class': a.ok ? 'jj-ok' : 'jj-warn', text: a.result + (a.at ? ' · ' + zt(a.at) : '') })
			]);
		});
		if (!att.length) {
			att.push(line(m.self ? 'this server' : (m.addresses || []).length ? (m.addresses || []).join(', ') + (m.connect === false ? ' (connect=false)' : '')
				: 'no address: waits for it to dial in', 'dim'));
		}
		if (m.fallback && !m.self) { att.push(line('fallback ' + m.fallback, 'dim')); }
		ui.append(col1, kv(d.attempts ? 'Addresses and attempts' : 'Addresses', att));
		var ch = (d.channels || []).map(function (c) {
			return c.kind + ' ' + (c.address || '') + ' ' + c.state + (c.since ? ' ' + since(c.since) : '');
		});
		if (d.lossratio !== null && d.lossratio !== undefined) { ch.push('udp loss ' + (d.lossratio * 100).toFixed(1) + '%'); }
		if (m.self) {
			var l = st.status && st.status.listen || {};
			ch = ['listening ' + [l.tcp ? 'tcp ' + l.tcp : null, l.udp ? 'udp ' + l.udp : null, l.fallback ? 'tls ' + l.fallback : null].filter(Boolean).join(', ')];
		}
		ui.append(col1, kv('Channels', [line(ch.length ? ch.join(' · ') : 'none')]));
		var route = m.self ? '—' : m.path && m.path.length > 1 ? m.path.join(' → ') + (m.state === 'direct' ? ' (direct)' : '') : 'no path';
		ui.append(col1, kv('Route', [line(route)]));
		if (d.lasterror && !m.self) {
			ui.append(col1, kv('Last error', [line(d.lasterror + (d.lasterrortime ? ' · ' + zt(d.lasterrortime) : ''), 'jj-warn')]));
		}

		// traffic per class
		var cls = d.classes;
		if (cls) {
			var grid = el('div.jj-cls', null, [el('span.dim', { text: 'class' }), el('span.dim', { text: 'out / in' }),
				el('span.dim', { text: 'queued' }), el('span.dim', { text: 'dropped' })]);
			[['admin', cls.admin, '—'], ['reliable', cls.reliable, cls.reliable && cls.reliable.unacked ? cls.reliable.unacked + ' unacked' : '—'],
				['best effort', cls.besteffort, cls.besteffort ? num(cls.besteffort.dropped) : '—']].forEach(function (c) {
				var v = c[1] || {};
				ui.append(grid, [el('span', { text: c[0] }), el('span', { text: num(v.outmsgs) + ' / ' + num(v.inmsgs) }),
					el('span', { text: num(v.queued) }), el('span', { text: c[2] })]);
			});
			ui.append(col2, kv('Traffic per class (messages)', [grid]));
			if (cls.besteffort && (cls.besteffort.superseded || cls.besteffort.udpout)) {
				ui.append(col2, line('best effort: ' + num(cls.besteffort.superseded) + ' superseded · udp ' + num(cls.besteffort.udpout) + ' out / ' + num(cls.besteffort.udpin) + ' in', 'dim'));
			}
		} else {
			ui.append(col2, kv('Traffic per class', [line(m.self ? 'see the other members' : 'no link: no traffic', 'dim')]));
		}
		var rate = d.rate || {};
		if (rate.inkbps !== null && rate.inkbps !== undefined) {
			ui.append(col2, line('in ' + rate.inkbps + ' kb/s · out ' + rate.outkbps + ' kb/s', 'dim'));
		}

		// its status, used by, ping
		var nd = d.node;
		var stTxt = m.self ? (st.status && m.version ? m.version : 'this server')
			: nd ? [nd.version, nd.started ? 'up ' + since(nd.started) : null, nd.cpu !== null && nd.cpu !== undefined ? 'CPU ' + nd.cpu + '%' : null,
				nd.heapused ? 'heap ' + mb(nd.heapused) + ' / ' + mb(nd.heapmax) : null,
				nd.gateways + (nd.gateways === 1 ? ' gateway' : ' gateways')].filter(Boolean).join(' · ')
				: 'unknown' + (m.lastseen ? ' (last seen ' + zt(m.lastseen) + ')' : '');
		var statusLines = [line(stTxt)];
		if (!m.self) {
			statusLines.push(line('clock offset ' + (m.clockoffsetms === null || m.clockoffsetms === undefined ? '—'
				: (m.clockoffsetms > 0 ? '+' : m.clockoffsetms < 0 ? '−' : '') + Math.abs(m.clockoffsetms) + ' ms'), 'dim'));
			if (m.memberlist === 'different') { statusLines.push(line('its member list differs from ours', 'jj-warn')); }
		}
		ui.append(col3, kv('Its status', statusLines));
		var users = m.users || {};
		var ul = (users.gateways || []).map(function (g) {
			var label = (g.gateway || g.id || '?') + (g.session ? ': session ' + g.session : '');
			return el('a.mono.jj-small', { href: 'junction.html', text: label });
		});
		if (!ul.length) { ul.push(line(m.self ? 'see the sessions' : 'no gateway shares a session with it', 'dim')); }
		if (users.transitfor && users.transitfor.length) { ul.push(line('carries traffic to ' + users.transitfor.join(', '), 'dim')); }
		ui.append(col3, kv('Used by', ul));
		if (!m.self) {
			var p = st.pings[id] || {};
			var btn = el('button.btn', { type: 'button', 'data-act': 'ping', disabled: p.busy || !running(),
				text: p.busy ? 'Pinging…' : '⌁ Ping', on: { click: function () { ping(id); } } });
			ui.append(col3, el('div.jj-pingrow', null, [btn, p.text ? el('span.mono.jj-small', { 'class': p.cls, text: p.text }) : null]));
		}

		ui.clear(r.detail);
		ui.append(r.detail, [col1, col2, col3]);
		if (focused) {
			var again = r.detail.querySelector('[data-act="' + focused + '"]');
			if (again) { again.focus(); }
		}
	}

	function ping(id) {
		st.pings[id] = { busy: true };
		renderDetail(id);
		jj.ping(id).then(function (res) {
			var hops = (res.path || []).map(function (h) { return h.id + ' ' + ms(h.rttms); });
			st.pings[id] = { text: hops.length > 1 ? hops.join(' → ') : ms(res.rttms), cls: pingClass(res.rttms) };
		}, function (err) {
			st.pings[id] = { text: (err.code ? err.code + ': ' : '') + err.message, cls: 'jj-p-red' };
		}).then(function () { renderDetail(id); });
	}

	/* ============================================================
	   The network diagram
	   ============================================================ */

	function applyTopology(t) {
		st.topo = t;
		renderDiagram();
	}

	/** Places the servers: one cell per site (grey separators), the servers of a site on an ellipse. */
	function layout(nodes, width) {
		var W = width;
		var groups = [], byName = {};
		nodes.forEach(function (n) {
			var g = n.site || '';
			if (!byName[g]) { byName[g] = { name: g, nodes: [] }; groups.push(byName[g]); }
			byName[g].nodes.push(n);
		});
		groups.forEach(function (g) {
			g.nodes.sort(function (a, b) { return (b.state === 'self') - (a.state === 'self'); });
		});
		var G = groups.length;
		var cols = G <= 1 ? 1 : G <= 4 ? 2 : G <= 9 ? 3 : 4;
		var rows = Math.ceil(G / cols);
		var cellW = W / cols;
		function need(k) {
			if (G === 1) { return k <= 6 ? 330 : 330 + (k - 6) * 18; }
			return k <= 1 ? 170 : k <= 4 ? 220 : k <= 8 ? 260 : 260 + (k - 8) * 16;
		}
		var rowH = [];
		for (var r = 0; r < rows; r++) {
			var h = 0;
			for (var c = 0; c < cols; c++) {
				var g = groups[r * cols + c];
				if (g) { h = Math.max(h, need(g.nodes.length)); }
			}
			rowH.push(h);
		}
		var pos = {}, labels = [], seps = [];
		var y0 = 0;
		groups.forEach(function (g, i) {
			var r = Math.floor(i / cols), c = i % cols;
			var top = 0;
			for (var k = 0; k < r; k++) { top += rowH[k]; }
			var cx = c * cellW + cellW / 2, cy = top + rowH[r] / 2 + 6;
			var n = g.nodes.length;
			var rx = cellW * (n > 6 ? 0.38 : 0.3), ry = rowH[r] / 2 - 48;
			g.nodes.forEach(function (node, j) {
				var x, y;
				if (n === 1) { x = cx; y = cy; }
				else if (n === 2) { x = cx + (j ? 1 : -1) * cellW * 0.24; y = cy + (j ? 1 : -1) * rowH[r] * 0.14; }   // diagonal: fewer lines through a node
				else {
					var a = -Math.PI / 2 + j * 2 * Math.PI / n;
					x = cx + rx * Math.cos(a);
					y = cy + ry * Math.sin(a);
				}
				pos[node.id] = [x, y];
			});
			if (g.name) {
				var right = c % 2 === 1 && c === cols - 1;
				labels.push({ x: right ? (c + 1) * cellW - 12 : c * cellW + 12, y: top + 20, anchor: right ? 'end' : 'start',
					text: (/^site\b/i.test(g.name) ? '' : 'SITE ') + g.name.toUpperCase() });
			}
		});
		var H = rowH.reduce(function (a, b) { return a + b; }, 0) || 200;
		if (G > 1) {
			for (var cc = 1; cc < cols; cc++) { seps.push([cc * cellW, 10, cc * cellW, H - 10]); }
			for (var rr = 1; rr < rows; rr++) {
				y0 = 0;
				for (var q = 0; q < rr; q++) { y0 += rowH[q]; }
				seps.push([10, y0, W - 10, y0]);
			}
		}
		return { W: W, H: H, pos: pos, labels: labels, seps: seps };
	}

	function renderOverlays() {
		var host = $('jj-overlays');
		var opts = [['links', 'Links']].concat(st.sessions.map(function (s) { return [s.name, 'Session ' + s.name]; }));
		if (st.overlay !== 'links' && !st.sessions.some(function (s) { return s.name === st.overlay; })) { st.overlay = 'links'; }
		var key = opts.map(function (o) { return o[0]; }).join('|') + '#' + st.overlay;
		if (host.getAttribute('data-key') === key) { return; }
		host.setAttribute('data-key', key);
		ui.clear(host);
		if (opts.length < 2) { return; }
		opts.forEach(function (o) {
			var on = st.overlay === o[0];
			host.appendChild(el('button.btn' + (on ? '.on' : ''), { type: 'button', 'aria-pressed': on ? 'true' : 'false', text: o[1],
				on: { click: function () { st.overlay = o[0]; renderOverlays(); renderDiagram(); } } }));
		});
	}

	function renderDiagram() {
		var box = $('jj-netbox');
		var t = st.topo;
		if (!t || !t.nodes || !t.nodes.length || !running()) { ui.clear(box); return; }
		renderOverlays();
		// the members' view is newer than the topology's (every 2 s): take states and ping from it
		var live = {};
		st.members.forEach(function (m) { live[m.id] = m; });
		var nodes = t.nodes.map(function (n) {
			var m = live[n.id];
			return m ? { id: n.id, name: m.name, site: m.site, state: m.state, via: m.via, rttms: m.rttms } : n;
		});
		var L = layout(nodes, Math.max(720, Math.round(box.clientWidth || 960)));
		var ses = null;
		st.sessions.forEach(function (s) { if (s.name === st.overlay) { ses = s; } });

		var root = svg('svg', { viewBox: '0 0 ' + L.W + ' ' + L.H, preserveAspectRatio: 'none', 'class': 'jj-net-svg', role: 'img',
			'aria-label': ariaOf(nodes, t.links) });
		L.seps.forEach(function (s) { root.appendChild(svg('line', { x1: s[0], y1: s[1], x2: s[2], y2: s[3], 'class': 'jj-sep' })); });
		L.labels.forEach(function (l) {
			var tx = svg('text', { x: l.x, y: l.y, 'text-anchor': l.anchor, 'class': 'jj-site' });
			tx.textContent = l.text;
			root.appendChild(tx);
		});
		(t.links || []).forEach(function (l) {
			var a = L.pos[l.a], b = L.pos[l.b];
			if (!a || !b) { return; }
			var up = l.state === 'up';
			if (up && l.ours) {
				// our own links: the live ping of the other end (the link-state value is older)
				var other = live[l.a === st.status.self.id ? l.b : l.a];
				if (other && other.state === 'direct' && other.rttms !== null && other.rttms !== undefined) { l = Object.assign({}, l, { rttms: other.rttms }); }
			}
			var g = svg('g', { 'class': ses ? 'jj-dimmed' : null });
			var bend = bendFor(a, b, l, L.pos);
			var cls = up ? 'jj-l-up' : l.state === 'refused' ? 'jj-l-refused' : 'jj-l-wait';
			var ln = bend ? svg('path', { d: 'M ' + a[0] + ' ' + a[1] + ' Q ' + bend[0].toFixed(0) + ' ' + bend[1].toFixed(0) + ' ' + b[0] + ' ' + b[1], 'class': cls, fill: 'none' })
				: svg('line', { x1: a[0], y1: a[1], x2: b[0], y2: b[1], 'class': cls });
			var tip = svg('title');
			var label = up ? ms(l.rttms) : l.state === 'refused' ? 'refused' : 'waiting';
			tip.textContent = l.a + ' – ' + l.b + ': ' + label + (l.channels && l.channels.length ? ' (' + l.channels.join(' + ') + ')' : '') +
				(l.reason ? ' – ' + l.reason : '') + (l.cost ? ' · cost ' + l.cost : '');
			ln.appendChild(tip);
			g.appendChild(ln);
			var lx = bend ? (a[0] + b[0]) / 4 + bend[0] / 2 : (a[0] + b[0]) / 2;      // a quadratic curve's middle
			var ly = (bend ? (a[1] + b[1]) / 4 + bend[1] / 2 : (a[1] + b[1]) / 2) + 4, w = label.length * 6.8 + 10;
			g.appendChild(svg('rect', { x: lx - w / 2, y: ly - 12, width: w, height: 16, rx: 2, 'class': 'jj-lbg' }));
			var tx = svg('text', { x: lx, y: ly, 'text-anchor': 'middle', 'class': 'jj-ltext ' + (up ? pingClass(l.rttms) : l.state === 'refused' ? 'jj-p-red' : 'jj-p-none') });
			tx.textContent = label;
			g.appendChild(tx);
			root.appendChild(g);
		});
		var gws = {};
		if (ses) {
			var ms_ = (ses.members || []).filter(function (x) { return L.pos[x.node]; });
			ms_.forEach(function (x) { (gws[x.node] = gws[x.node] || []).push(x); });
			for (var i = 0; i < ms_.length; i++) {
				for (var j = i + 1; j < ms_.length; j++) {
					if (ms_[i].node === ms_[j].node) { continue; }
					var a = L.pos[ms_[i].node], b = L.pos[ms_[j].node];
					var mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, dx = b[0] - a[0], dy = b[1] - a[1], len = Math.sqrt(dx * dx + dy * dy) || 1;
					var bad = [ms_[i].state, ms_[j].state];
					var p = svg('path', { d: 'M ' + a[0] + ' ' + a[1] + ' Q ' + (mx - dy / len * 42).toFixed(0) + ' ' + (my + dx / len * 42).toFixed(0) + ' ' + b[0] + ' ' + b[1],
						'class': 'jj-l-session' + (bad.indexOf('lost') >= 0 ? ' jj-s-lost' : bad.indexOf('mismatch') >= 0 ? ' jj-s-mismatch' : '') });
					var tt = svg('title');
					tt.textContent = ses.name + ': ' + ms_[i].node + ' ⇄ ' + ms_[j].node;
					p.appendChild(tt);
					root.appendChild(p);
				}
			}
		}
		var frag = document.createDocumentFragment();
		frag.appendChild(root);
		nodes.forEach(function (n) {
			var p = L.pos[n.id];
			var down = ['self', 'direct', 'routed'].indexOf(n.state) < 0;
			var cls = n.state === 'self' ? 'jj-n-direct jj-n-self' : n.state === 'direct' ? 'jj-n-direct' : n.state === 'routed' ? 'jj-n-routed' : 'jj-n-down' + (n.state === 'refused' ? ' jj-n-refused' : '');
			var sub = n.state === 'self' ? 'this server' : n.state === 'routed' ? 'via ' + n.via + ' · ' + ms(n.rttms) : n.state === 'direct' ? ms(n.rttms)
				: (BADGE[n.state] ? BADGE[n.state][0].replace(/^\S+ /, '') : n.state);
			var subCls = n.state === 'self' ? 'jj-selfid' : down ? (n.state === 'refused' ? 'jj-p-red' : 'jj-p-none') : pingClass(n.rttms);
			var chips = (gws[n.id] || []).map(function (x) {
				return el('span.jj-gw' + (x.state && x.state !== 'active' ? '.jj-gw-' + x.state : ''), {
					text: 'GW ' + x.gateway + (x.state && x.state !== 'active' ? ' (' + x.state + ')' : ''), title: x.statemessage || null });
			});
			frag.appendChild(el('button.jj-node', { type: 'button', style: 'left:' + (p[0] / L.W * 100).toFixed(2) + '%;top:' + (p[1] / L.H * 100).toFixed(2) + '%',
				title: n.id + ' · ' + (n.name || '') + ' · ' + n.state, on: { click: function () { openMember(n.id); } } },
				[el('span.jj-node-box.' + cls.split(' ').join('.'), { text: n.id }), el('span.jj-node-sub.' + subCls, { text: sub })].concat(chips)));
		});
		box.style.aspectRatio = L.W + ' / ' + L.H;
		box.style.minHeight = '';
		ui.clear(box).appendChild(frag);
	}

	/**
	 * A straight line from a to b that passes close to another server would look like it ends
	 * there: such a link is drawn as a curve around it. @return the curve's control point, or null
	 */
	function bendFor(a, b, l, pos) {
		var dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy;
		if (!len2) { return null; }
		var side = 0;
		Object.keys(pos).forEach(function (id) {
			if (id === l.a || id === l.b || side) { return; }
			var p = pos[id];
			var t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
			if (t <= 0.05 || t >= 0.95) { return; }
			var cross = (p[0] - a[0]) * dy - (p[1] - a[1]) * dx;
			if (Math.abs(cross) / Math.sqrt(len2) < 34) { side = cross >= 0 ? -1 : 1; }   // bend away from it
		});
		if (!side) { return null; }
		var len = Math.sqrt(len2);
		return [(a[0] + b[0]) / 2 + side * dy / len * 110, (a[1] + b[1]) / 2 - side * dx / len * 110];
	}

	function ariaOf(nodes, links) {
		var up = (links || []).filter(function (l) { return l.state === 'up'; }).length;
		return 'J2J network: ' + nodes.length + ' servers, ' + up + ' links up';
	}

	/* ============================================================
	   Sessions
	   ============================================================ */

	function applySessions(list) {
		st.sessions = list || [];
		var host = ui.clear($('jj-session-list'));
		text($('jj-session-count'), st.sessions.length + (st.sessions.length === 1 ? ' session' : ' sessions'));
		if (!st.sessions.length) {
			host.appendChild(ui.emptyBlock('No sessions', 'A gateway joins a session with a j2j endpoint (endpoint.standard=j2j); the sessions it shares with other members show here.'));
		}
		st.sessions.forEach(function (s) {
			var chips = (s.members || []).map(function (x) {
				var cls = x.state === 'lost' ? 'jj-chip-bad' : x.state === 'mismatch' ? 'jj-chip-warn' : 'jj-chip-ok';
				var label = [el('span.jj-dot', { 'aria-hidden': 'true' }), x.node + ' / ' + x.gateway];
				if (x.local) { label.push(el('span.dim', { text: ' (here)' })); }
				if (x.state && x.state !== 'active') { label.push(el('span.jj-chip-state', { text: ' ' + x.state + (x.statemessage ? ': ' + x.statemessage : '') })); }
				return x.local ? el('a.jj-chip.' + cls, { href: 'junction.html' }, label) : el('span.jj-chip.' + cls, null, label);
			});
			host.appendChild(el('div.jj-session', null, [
				el('div.jj-session-head', null, [el('b.mono.jj-selfid', { text: s.name }),
					el('span.jj-k', { text: [s.kind ? s.kind.toUpperCase() : null, s.model].filter(Boolean).join(' · ') }),
					s.since ? el('span.dim.jj-small', { text: 'since ' + zt(s.since) }) : null]),
				el('div.jj-chips', null, chips)
			]));
		});
		renderDiagram();
	}

	/* ============================================================
	   Events
	   ============================================================ */

	function addEvent(e) {
		st.events.unshift(e);
		if (st.events.length > EVENTS_KEPT) { st.events.length = EVENTS_KEPT; }
		renderEvents();
	}

	function levelCls(l) {
		return l === 'SEVERE' || l === 'ERROR' ? 'jj-p-red' : l === 'WARNING' ? 'jj-p-amber' : 'jj-p-green';
	}

	function renderEvents() {
		var host = ui.clear($('jj-event-list'));
		var list = st.events.filter(function (e) { return st.level === 'all' || e.level === 'WARNING' || e.level === 'SEVERE'; });
		if (!list.length) {
			host.appendChild(el('div.jj-empty', { text: st.level === 'all' ? 'No J2J events yet.' : 'No warnings.' }));
			return;
		}
		list.slice(0, 100).forEach(function (e) {
			host.appendChild(el('div.jj-ev', null, [
				el('span.dim', { text: zt(e.at), title: e.at }),
				el('span', { 'class': levelCls(e.level), text: e.level }),
				el('span.jj-ev-name', { text: e.event || '' }),
				el('span.jj-ev-text', { text: e.text || '' })
			]));
		});
	}

	function setLevel(level) {
		st.level = level;
		[['jj-ev-all', 'all'], ['jj-ev-warn', 'warn']].forEach(function (b) {
			$(b[0]).classList.toggle('on', level === b[1]);
			$(b[0]).setAttribute('aria-pressed', level === b[1] ? 'true' : 'false');
		});
		renderEvents();
	}

	/* ============================================================
	   Actions: reload the member list, a new key text
	   ============================================================ */

	function reload(btn) {
		ui.busy(btn, jj.reload()).then(function (r) {
			var ch = [];
			if (r.added && r.added.length) { ch.push('added ' + r.added.join(', ')); }
			if (r.removed && r.removed.length) { ch.push('removed ' + r.removed.join(', ')); }
			if (r.changed && r.changed.length) { ch.push('changed ' + r.changed.join(', ')); }
			var msg = r.state === 'off' ? 'Member list read again: no members, J2J stays off.'
				: 'Member list read again: ' + (ch.length ? ch.join('; ') : 'no changes') + ' · J2J ' + r.state + '.';
			ui.toast(msg, r.state === 'error' ? 'err' : 'ok', 6000);
			return loadAll();
		}, function (err) {
			ui.toast((err.code === 'MEMBERS_INVALID' ? 'Not applied: ' : '') + err.message, 'err', 9000);
		});
	}

	function newKey(btn) {
		ui.busy(btn, jj.newKey()).then(function (k) {
			text($('jj-key-line'), k.line);
			text($('jj-key-fp'), k.fingerprint);
			text($('jj-key-copy'), 'Copy');
			show($('jj-key'), true);
			$('jj-key').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
		}, function (err) { ui.toast(err.message, 'err'); });
	}

	function copyKey() {
		var t = $('jj-key-line').textContent;
		var done = function () { text($('jj-key-copy'), 'Copied'); };
		var fail = function () {
			var range = document.createRange();
			range.selectNodeContents($('jj-key-line'));
			var sel = window.getSelection();
			sel.removeAllRanges();
			sel.addRange(range);
			ui.toast('Copying is blocked here: the text is selected, copy it with the keyboard.', 'info');
		};
		if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(t).then(done, fail); } else { fail(); }
	}

	/* ============================================================ */

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		Array.prototype.forEach.call(document.querySelectorAll('[data-act="reload"]'), function (b) {
			b.addEventListener('click', function () { reload(b); });
		});
		Array.prototype.forEach.call(document.querySelectorAll('[data-act="newkey"]'), function (b) {
			b.addEventListener('click', function () { newKey(b); });
		});
		$('jj-key-copy').addEventListener('click', copyKey);
		$('jj-key-close').addEventListener('click', function () { show($('jj-key'), false); plain($('jj-key-line'), ''); });
		$('jj-filter').addEventListener('input', function (e) { st.filter = e.target.value; renderMembers(); });
		$('jj-ev-all').addEventListener('click', function () { setLevel('all'); });
		$('jj-ev-warn').addEventListener('click', function () { setLevel('warn'); });
		var resizeTimer = null;
		window.addEventListener('resize', function () {
			clearTimeout(resizeTimer);
			resizeTimer = setTimeout(renderDiagram, 150);
		});
		var want = /[?&]member=([^&]+)/.exec(window.location.search);
		loadAll().then(function () {
			if (want && memberById(decodeURIComponent(want[1]))) { openMember(decodeURIComponent(want[1])); }
		});
		startLive();
	});
})(window, document);
