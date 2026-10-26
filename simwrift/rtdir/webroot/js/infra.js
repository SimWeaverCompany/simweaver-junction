/* ============================================================
   SimWeaver Infra - the page (infra.html), 0.9.20.
   The DIS 6 / DIS 7 / C-DIS exercises and HLA 1516-2010 / 1516-2025
   federations that Rift's datasources and Junction's gateways have
   contact with. A row opens to its live detail: the senders of an
   exercise, the federates of a federation (from the MOM). Marked rows
   open in a breakout tab: infra.html?watch=key1,key2
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var infra = Rift.infra;

	var FAMILIES = [
		{ id: 'dis6', title: 'DIS 6 exercises', unit: 'exercise', hint: 'A Rift datasource or a Junction endpoint on DIS shows here every DIS 6 exercise its port hears.' },
		{ id: 'dis7', title: 'DIS 7 exercises', unit: 'exercise', hint: 'A Rift datasource or a Junction endpoint on DIS shows here every DIS 7 exercise its port hears.' },
		{ id: 'cdis', title: 'C-DIS exercises', unit: 'exercise', hint: 'C-DIS datasources and endpoints show the exercises of the C-DIS they decode.' },
		{ id: 'hla3', title: 'HLA 1516-2010 federations', unit: 'federation', hint: 'Every federation a Rift datasource or a Junction endpoint has joined, with its federates from the MOM.' },
		{ id: 'hla4', title: 'HLA 1516-2025 federations', unit: 'federation', hint: 'Every federation a Rift datasource or a Junction endpoint has joined, with its federates from the MOM.' }
	];
	var BADGE = { dis6: 'DIS 6', dis7: 'DIS 7', cdis: 'C-DIS', hla3: 'HLA 3', hla4: 'HLA 4' };
	var OVERVIEW_MS = 2000;
	var DETAIL_MS = 1500;
	var MARK_KEY = 'rift-infra-marked';
	var NAMES_KEY = 'rift-infra-names';

	var params = new URLSearchParams(window.location.search);
	var watch = (params.get('watch') || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
	var watchMode = watch.length > 0;

	var state = {
		overview: null,
		open: {},              // key -> true: rows showing their detail
		openFed: {},           // key|federate -> true: federate rows showing theirs
		marked: load(MARK_KEY, {}),
		names: load(NAMES_KEY, {}),
		details: {},           // key -> last full item
		asking: {}             // key|handle -> pending declarations request
	};

	function $(id) { return document.getElementById(id); }

	// ------------------------------------------------------------------ storage (per viewer conveniences only)

	function load(k, dflt) {
		try {
			var v = window.localStorage.getItem(k);
			return v ? JSON.parse(v) : dflt;
		} catch (e) { return dflt; }
	}

	function save(k, v) {
		try { window.localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private window */ }
	}

	// ------------------------------------------------------------------ formatting

	function ago(ms, now) {
		if (!ms) { return '—'; }
		var s = Math.max(0, Math.round(((now || Date.now()) - ms) / 1000));
		if (s < 2) { return 'now'; }
		if (s < 120) { return s + ' s ago'; }
		if (s < 7200) { return Math.round(s / 60) + ' min ago'; }
		return Math.round(s / 3600) + ' h ago';
	}

	function rate(v) {
		if (v === null || v === undefined) { return '—'; }
		return v >= 100 ? String(Math.round(v)) : v >= 10 ? v.toFixed(0) : v.toFixed(1);
	}

	function bytes(perSecond) {
		if (perSecond === null || perSecond === undefined) { return '—'; }
		if (perSecond < 1024) { return Math.round(perSecond) + ' B/s'; }
		if (perSecond < 1048576) { return (perSecond / 1024).toFixed(perSecond < 10240 ? 1 : 0) + ' kB/s'; }
		return (perSecond / 1048576).toFixed(1) + ' MB/s';
	}

	function count(n) {
		if (n === null || n === undefined) { return '—'; }
		return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e4 ? Math.round(n / 1000) + 'k' : String(n);
	}

	function yesNo(v) { return v === true ? 'yes' : v === false ? 'no' : '—'; }

	function short(qualified) { return String(qualified || '').replace(/^HLA(object|interaction)Root\./, ''); }

	var SVGNS = 'http://www.w3.org/2000/svg';

	/** A sparkline of the last minute (messages per second). */
	function spark(history, w, h) {
		w = w || 72; h = h || 18;
		var svg = document.createElementNS(SVGNS, 'svg');
		svg.setAttribute('class', 'ix-spark');
		svg.setAttribute('width', w);
		svg.setAttribute('height', h);
		svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
		svg.setAttribute('aria-hidden', 'true');
		var hs = Array.isArray(history) ? history : [];
		var max = 1;
		hs.forEach(function (v) { if (v > max) { max = v; } });
		if (hs.length > 1) {
			var pts = hs.map(function (v, i) {
				return (i * (w - 1) / (hs.length - 1)).toFixed(1) + ',' + (h - 1 - v * (h - 3) / max).toFixed(1);
			});
			var area = document.createElementNS(SVGNS, 'polygon');
			area.setAttribute('points', '0,' + h + ' ' + pts.join(' ') + ' ' + (w - 1) + ',' + h);
			area.setAttribute('class', 'ix-spark-area');
			svg.appendChild(area);
			var line = document.createElementNS(SVGNS, 'polyline');
			line.setAttribute('points', pts.join(' '));
			line.setAttribute('class', 'ix-spark-line');
			svg.appendChild(line);
		}
		return svg;
	}

	function lamp(status) {
		var cls = status === 'active' ? '' : status === 'quiet' || status === 'joined' ? '.quiet' : '.off';
		return el('span.lamp.ix-lamp' + cls, { title: status === 'active' ? 'Traffic in the last seconds'
			: status === 'quiet' ? 'Nothing for a few seconds' : status === 'joined' ? 'Joined, no traffic from the others yet'
			: 'Nothing for over a minute', 'aria-label': status || '' });
	}

	function setText(node, text) {
		var t = text === null || text === undefined ? '' : String(text);
		if (node.textContent !== t) { node.textContent = t; }
	}

	function replace(node, child) {
		ui.clear(node);
		if (child) { node.appendChild(child); }
	}

	/**
	 * Keeps a container's children in step with a list, by key: new keys get a node from create(),
	 * every node is updated with update(node, item), gone keys are removed. The order follows the list.
	 */
	function sync(container, items, keyOf, create, update) {
		var existing = {};
		Array.prototype.forEach.call(container.children, function (n) {
			if (n.dataset && n.dataset.key !== undefined) { existing[n.dataset.key] = n; }
		});
		var prev = null;
		items.forEach(function (it) {
			var k = keyOf(it);
			var n = existing[k];
			if (!n) {
				n = create(it);
				n.dataset.key = k;
			}
			delete existing[k];
			update(n, it);
			var want = prev ? prev.nextSibling : container.firstChild;
			if (n !== want) { container.insertBefore(n, want); }
			prev = n;
		});
		Object.keys(existing).forEach(function (k) { container.removeChild(existing[k]); });
	}

	// ------------------------------------------------------------------ marks and breakout

	function label(item) {
		return BADGE[item.family] + ' · ' + item.name + ' · ' + ((item.source && item.source.label) || '');
	}

	function setMarked(item, on) {
		if (on) {
			state.marked[item.key] = true;
			state.names[item.key] = label(item);
		} else {
			delete state.marked[item.key];
		}
		save(MARK_KEY, state.marked);
		save(NAMES_KEY, state.names);
		updateBar();
	}

	function updateBar() {
		var keys = Object.keys(state.marked);
		var bar = $('ix-bar');
		bar.hidden = watchMode || keys.length === 0;
		setText($('ix-bar-text'), keys.length + ' marked');
		$('ix-bar-text').title = 'Marked for a breakout tab: ' + keys.map(function (k) { return state.names[k] || k; }).join(', ');
	}

	function openBreakout() {
		var keys = Object.keys(state.marked);
		if (!keys.length) { return; }
		window.open('infra.html?watch=' + keys.map(encodeURIComponent).join(','), '_blank', 'noopener');
	}

	// ------------------------------------------------------------------ overview

	function renderFamilies() {
		var host = $('ix-families');
		FAMILIES.forEach(function (f, i) {
			var list = el('div.ix-items', { id: 'ix-list-' + f.id });
			var meta = el('span', { id: 'ix-meta-' + f.id, text: '' });
			host.appendChild(el('section.panel.ix-family.fam-' + f.id, { id: 'ix-fam-' + f.id, 'aria-labelledby': 'ix-title-' + f.id }, [
				el('div.panel-head', null, [
					el('span.idx', { text: '0' + (i + 1) }),
					el('h2.title', { id: 'ix-title-' + f.id, text: f.title }),
					el('span.meta', null, meta)
				]),
				el('div.panel-body', null, list)
			]));
		});
	}

	function renderSummary(o) {
		var box = $('ix-summary');
		ui.clear(box);
		FAMILIES.forEach(function (f) {
			var n = (o[f.id] || []).length;
			var active = (o[f.id] || []).filter(function (x) { return x.status === 'active'; }).length;
			box.appendChild(el('a.ix-tile.fam-' + f.id + (n ? '' : '.none'), { href: '#ix-fam-' + f.id, title: f.title }, [
				el('span.ix-tile-k', { text: BADGE[f.id] }),
				el('span.ix-tile-v', { text: String(n) }),
				el('span.ix-tile-s', { text: n ? active + ' active' : 'no contact' })
			]));
		});
		box.appendChild(el('div.ix-tile.ix-tile-note', null, [
			el('span.ix-tile-k', { text: 'SOURCES' }),
			el('span.ix-tile-v', { text: String(o.sources || 0) }),
			el('span.ix-tile-s', { text: o.enabled === false ? 'Infra is switched off (infra.enabled)'
				: o.mom === false ? 'MOM requests off (infra.hla.mom)' : 'MOM every ' + o.pollseconds + ' s while watched' })
		]));
	}

	function renderOverview(o) {
		state.overview = o;
		renderSummary(o);
		FAMILIES.forEach(function (f) {
			var items = (o[f.id] || []).slice().sort(function (a, b) {
				return String(a.name).localeCompare(String(b.name), undefined, { numeric: true }) ||
					String(a.source && a.source.label).localeCompare(String(b.source && b.source.label));
			});
			var list = $('ix-list-' + f.id);
			setText($('ix-meta-' + f.id), items.length ? items.length + ' ' + f.unit + (items.length === 1 ? '' : 's') : '');
			if (!items.length) {
				if (!list.querySelector('.state-empty')) { replace(list, ui.emptyBlock('No contact.', f.hint)); }
				return;
			}
			var empty = list.querySelector('.state-empty');
			if (empty) { list.removeChild(empty); }
			sync(list, items, function (it) { return it.key; }, createRow, function (n, it) { updateRow(n, it, o.now); });
		});
	}

	function createRow(item) {
		var n = el('div.ix-item');
		var box = el('input', { type: 'checkbox', title: 'Mark for a breakout tab', 'aria-label': 'Mark for a breakout tab' });
		box.addEventListener('click', function (e) { e.stopPropagation(); });
		box.addEventListener('change', function () { setMarked(n._item, box.checked); });
		n._box = box;
		n._lamp = el('span.ix-lampbox');
		n._name = el('span.ix-name');
		n._where = el('span.ix-chip');
		n._source = el('a.ix-source', { on: { click: function (e) { e.stopPropagation(); } } });
		n._count = el('span.ix-num');
		n._rate = el('span.ix-num.ix-rate');
		n._spark = el('span.ix-sparkbox');
		n._last = el('span.ix-last');
		n._caret = el('span.ix-caret', { 'aria-hidden': 'true', text: '▸' });
		n._head = el('div.ix-head', { role: 'button', tabindex: '0' }, [
			el('label.ix-mark', null, box), n._lamp, n._name, n._where, n._source, n._count, n._rate, n._spark, n._last, n._caret
		]);
		var toggle = function () {
			var k = n._item.key;
			if (state.open[k]) { delete state.open[k]; } else { state.open[k] = true; }
			updateOpen(n);
			if (state.open[k]) { pollDetails(); }
		};
		n._head.addEventListener('click', toggle);
		n._head.addEventListener('keydown', function (e) {
			if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
		});
		n._detail = el('div.ix-detail');
		n.appendChild(n._head);
		n.appendChild(n._detail);
		return n;
	}

	function updateRow(n, it, now) {
		n._item = it;
		n._box.checked = !!state.marked[it.key];
		if (state.marked[it.key]) { state.names[it.key] = label(it); }
		replace(n._lamp, lamp(it.status));
		setText(n._name, it.name);
		var hla = it.family === 'hla3' || it.family === 'hla4';
		setText(n._where, hla ? 'LSD ' + it.connection : 'UDP ' + it.port + (it.family === 'dis6' && it.versions && it.versions.length
			&& it.versions[0] !== 6 ? ' · v' + it.versions.join('/') : ''));
		n._where.title = hla ? 'Joined as ' + it.ownfederate : it.where;
		setText(n._source, it.source.label);
		n._source.href = it.source.link;
		n._source.title = 'Open ' + it.source.label;
		setText(n._count, hla ? it.federates + ' federate' + (it.federates === 1 ? '' : 's')
			: it.senders + ' sender' + (it.senders === 1 ? '' : 's') + ' · ' + it.entities + ' entit' + (it.entities === 1 ? 'y' : 'ies'));
		setText(n._rate, rate(it.rate) + (hla ? ' msg/s' : ' PDU/s'));
		n._rate.title = bytes(it.octetrate) + (hla ? ' received from the other federates' : '');
		replace(n._spark, spark(it.history));
		setText(n._last, hla && it.mom !== 'ok' ? (it.mom === 'unavailable' ? 'no MOM' : 'MOM …') : ago(it.last, now));
		n._last.title = hla ? (it.momproblem || 'MOM: ' + it.mom) : 'Last PDU';
		updateOpen(n);
	}

	function updateOpen(n) {
		var open = !!state.open[n._item.key];
		n.classList.toggle('open', open);
		n._head.setAttribute('aria-expanded', open ? 'true' : 'false');
		n._detail.hidden = !open;
		var d = state.details[n._item.key];
		if (open && d && !d.missing) { renderDetail(n._detail, d); }
		else if (open && !n._detail.firstChild) { n._detail.appendChild(ui.loadingRow('Reading…')); }
	}

	// ------------------------------------------------------------------ detail

	function renderDetail(host, item) {
		if (host._family !== item.family) {
			ui.clear(host);
			host._family = item.family;
			host._parts = null;
		}
		if (item.family === 'hla3' || item.family === 'hla4') { renderHla(host, item); } else { renderDis(host, item); }
	}

	function facts(list) {
		return el('dl.ix-facts', null, list.filter(function (f) { return f && f[1] !== null && f[1] !== undefined && f[1] !== ''; })
			.reduce(function (acc, f) {
				acc.push(el('dt', { text: f[0] }));
				acc.push(el('dd', { title: f[2] || null }, f[1] instanceof Node ? f[1] : String(f[1])));
				return acc;
			}, []));
	}

	function sourceLink(item) {
		return el('a', { href: item.source.link, text: item.source.label });
	}

	// ---- DIS

	function renderDis(host, item) {
		var p = host._parts;
		if (!p) {
			p = host._parts = {
				facts: el('div'),
				types: el('div.ix-types'),
				body: el('tbody')
			};
			host.appendChild(p.facts);
			host.appendChild(el('div.ix-subhead', { text: 'PDU types' }));
			host.appendChild(p.types);
			host.appendChild(el('div.ix-subhead', { text: 'Senders' }));
			host.appendChild(el('div.ix-tablewrap', null, el('table.ix-table', null, [
				el('thead', null, el('tr', null, ['', 'Address', 'Site : application', 'Entities', 'PDU types', 'PDU/s', 'Traffic', 'Last minute', 'Last PDU']
					.map(function (h) { return el('th', { text: h }); }))),
				p.body
			])));
		}
		var now = Date.now();
		replace(p.facts, facts([
			['Protocol', item.protocol + (item.versions && item.versions.length ? ' (version ' + item.versions.join(', ') + ')' : '')],
			['Exercise', item.exercise],
			['Socket', item.where],
			['Seen by', sourceLink(item)],
			['Owner', item.note],
			['Heard', count(item.pdus) + ' PDUs of this exercise since ' + ui.timeZ(new Date(item.first))],
			['Port', count(item.datagrams) + ' datagrams heard on the port (all exercises) since '
				+ ui.timeZ(new Date(item.listeningsince)) + (item.malformed ? ', ' + count(item.malformed) + ' malformed' : '')],
			['Senders limited', item.senderslimited ? item.senderslimited + ' more senders not listed' : null]
		]));
		var types = item.pdutypes || [];
		var max = types.length ? types[0].pdus : 1;
		ui.clear(p.types);
		types.slice(0, 12).forEach(function (t) {
			p.types.appendChild(el('div.ix-type', { title: 'PDU type ' + t.type }, [
				el('span.ix-type-n', { text: t.name }),
				el('span.ix-type-bar', null, el('i', { style: 'width:' + Math.max(2, Math.round(100 * t.pdus / max)) + '%' })),
				el('span.ix-type-c', { text: count(t.pdus) })
			]));
		});
		var senders = item.senderlist || [];
		if (!senders.length) {
			replace(p.body, el('tr', null, el('td.dim', { colspan: '9', text: 'No senders now.' })));
			return;
		}
		if (p.body.firstChild && !p.body.firstChild.dataset.key) { ui.clear(p.body); }
		sync(p.body, senders, function (s) { return s.address + ':' + s.port; }, function () {
			var tr = el('tr');
			tr._c = [];
			for (var i = 0; i < 9; i++) { tr._c.push(el('td')); tr.appendChild(tr._c[i]); }
			return tr;
		}, function (tr, s) {
			var c = tr._c;
			replace(c[0], lamp(s.status));
			ui.clear(c[1]);
			c[1].appendChild(el('span.ix-mono', { text: s.address + ':' + s.port }));
			if (s.local) { c[1].appendChild(el('span.ix-tag', { text: 'THIS COMPUTER', title: 'An address of the server itself' })); }
			ui.clear(c[2]);
			(s.siteapps || []).slice(0, 8).forEach(function (sa) {
				c[2].appendChild(el('span.ix-sa', { text: sa.site + ':' + sa.app, title: count(sa.pdus) + ' PDUs' }));
			});
			if ((s.siteapps || []).length > 8) { c[2].appendChild(el('span.dim', { text: ' +' + (s.siteapps.length - 8) })); }
			setText(c[3], count(s.entities));
			setText(c[4], (s.pdutypes || []).slice(0, 3).map(function (t) { return abbreviate(t.name) + ' ' + count(t.pdus); }).join(' · '));
			c[4].title = (s.pdutypes || []).map(function (t) { return t.name + ': ' + t.pdus; }).join('\n');
			setText(c[5], rate(s.rate));
			setText(c[6], bytes(s.octetrate));
			replace(c[7], spark(s.history, 90, 18));
			setText(c[8], ago(s.last, now));
			c[8].title = 'First heard ' + ui.timeZ(new Date(s.first));
		});
	}

	function abbreviate(name) {
		return { 'Entity State': 'ES', 'Entity State Update': 'ESU', 'Electromagnetic Emission': 'EE', 'Transmitter': 'TX',
			'Receiver': 'RX', 'Detonation': 'Det' }[name] || name;
	}

	// ---- HLA

	function renderHla(host, item) {
		var p = host._parts;
		if (!p) {
			var refresh = el('button.btn', { type: 'button', text: '⟳ Ask the MOM now', title: 'Fresh MOM values now (else every few seconds while you watch)' });
			refresh.addEventListener('click', function (e) {
				e.stopPropagation();
				ui.busy(refresh, infra.refresh(host._item.key).then(function () {
					window.setTimeout(pollDetails, 600);
				}, function (err) { ui.toast('The MOM was not asked: ' + err.message, 'err'); }));
			});
			p = host._parts = {
				facts: el('div'),
				notes: el('div.ix-notes'),
				tools: el('div.ix-tools', null, refresh),
				body: el('tbody')
			};
			host.appendChild(p.facts);
			host.appendChild(p.notes);
			host.appendChild(el('div.ix-subhead', null, [el('span', { text: 'Federates' }), p.tools]));
			host.appendChild(el('div.ix-tablewrap', null, el('table.ix-table.ix-feds', null, [
				el('thead', null, [
					el('tr', null, [
						el('th', { rowspan: '2' }), el('th', { rowspan: '2', text: 'Federate' }), el('th', { rowspan: '2', text: 'Type' }),
						el('th', { rowspan: '2', text: 'Host' }), el('th', { rowspan: '2', text: 'State · time' }),
						el('th.grp', { colspan: '4', text: 'MOM, per second', title: 'From the RTI: what each federate sends and receives' }),
						el('th.grp', { colspan: '3', text: 'Received from it here', title: 'What this datasource / endpoint receives from that federate' }),
						el('th', { rowspan: '2', text: 'MOM data' })
					]),
					el('tr', null, ['Upd sent', 'Refl rcvd', 'Int sent', 'Int rcvd', 'Upd/s', 'Int/s', 'Last minute']
						.map(function (h) { return el('th.sub', { text: h }); }))
				]),
				p.body
			])));
		}
		host._item = item;
		var now = Date.now();
		var fi = item.federationinfo || {};
		replace(p.facts, facts([
			['Federation', item.federation],
			['Standard', item.protocol],
			['Seen by', sourceLink(item)],
			['Joined as', item.ownfederate, 'This datasource / endpoint\'s federate'],
			['Connection', item.connection],
			['RTI', item.rtiversion || fi.rtiversion],
			['Time', item.timeimplementation],
			['MIM', fi.mimdesignator],
			['Auto-provide', fi.autoprovide === undefined ? null : yesNo(fi.autoprovide)],
			['FOM modules', fi.fommodules && fi.fommodules.length ? fi.fommodules.join(', ') : null],
			['Last save', fi.lastsavename ? fi.lastsavename + (fi.lastsavetime ? ' at ' + fi.lastsavetime : '') : null],
			['MOM', item.mom === 'ok' ? 'asked every ' + item.pollseconds + ' s while watched' : item.mom]
		]));
		ui.clear(p.notes);
		if (item.momproblem) { p.notes.appendChild(el('div.ix-note.warn', { text: item.momproblem })); }
		if (item.mommissing && item.mommissing.length) {
			p.notes.appendChild(el('div.ix-note', { text: 'This RTI\'s MOM lacks: ' + item.mommissing.join(', ') }));
		}
		(item.momexceptions || []).forEach(function (x) { p.notes.appendChild(el('div.ix-note.warn', { text: 'MOM exception: ' + x })); });
		var feds = item.federatelist || [];
		if (!feds.length) {
			replace(p.body, el('tr', null, el('td.dim', { colspan: '13', text: item.mom === 'ok' ? 'Waiting for the MOM…' : 'No federates seen yet.' })));
			return;
		}
		if (p.body.firstChild && !p.body.firstChild.dataset.key) { ui.clear(p.body); }
		var rows = [];
		feds.forEach(function (f) {
			rows.push({ key: 'f|' + (f.handle || f.name), f: f });
			if (state.openFed[item.key + '|' + (f.handle || f.name)]) { rows.push({ key: 'd|' + (f.handle || f.name), f: f, detail: true }); }
		});
		sync(p.body, rows, function (r) { return r.key; }, function (r) {
			if (r.detail) {
				var tr = el('tr.ix-fed-detail');
				tr._td = el('td', { colspan: '13' });
				tr.appendChild(tr._td);
				return tr;
			}
			var row = el('tr.ix-fed', { tabindex: '0', title: 'Click for the MOM values, publications and subscriptions' });
			row._c = [];
			for (var i = 0; i < 13; i++) { row._c.push(el('td')); row.appendChild(row._c[i]); }
			var toggle = function () {
				var k = item.key + '|' + (row._f.handle || row._f.name);
				if (state.openFed[k]) { delete state.openFed[k]; } else { state.openFed[k] = true; }
				renderHla(host, host._item);
			};
			row.addEventListener('click', toggle);
			row.addEventListener('keydown', function (e) { if (e.key === 'Enter') { toggle(); } });
			return row;
		}, function (tr, r) {
			if (r.detail) { renderFederateDetail(tr._td, item, r.f); return; }
			var f = r.f;
			tr._f = f;
			var c = tr._c;
			var ob = f.observed || {};
			var mr = f.momrates || {};
			var open = !!state.openFed[item.key + '|' + (f.handle || f.name)];
			tr.classList.toggle('open', open);
			tr.classList.toggle('gone', !!f.resigned);
			replace(c[0], lamp(f.resigned ? 'silent' : ob.last ? (now - ob.last < 5000 ? 'active' : 'quiet') : f.frommom ? 'joined' : 'quiet'));
			ui.clear(c[1]);
			c[1].appendChild(el('span.ix-caret', { text: open ? '▾' : '▸' }));
			c[1].appendChild(el('span.ix-fedname', { text: f.name }));
			if (f.self) { c[1].appendChild(el('span.ix-tag.self', { text: 'THIS', title: 'This datasource / endpoint\'s own federate' })); }
			if (f.resigned) { c[1].appendChild(el('span.ix-tag.gone', { text: 'RESIGNED ' + ago(f.resigned, now) })); }
			if (!f.frommom) { c[1].appendChild(el('span.ix-tag', { text: 'TRAFFIC ONLY', title: 'Seen by its traffic; not (yet) in the MOM' })); }
			setText(c[2], f.type || '');
			setText(c[3], f.host || '');
			setText(c[4], [f.state, f.timestate ? 'time ' + f.timestate : null,
				f.timeregulating ? 'regulating' : null, f.timeconstrained ? 'constrained' : null].filter(Boolean).join(' · '));
			setText(c[5], rate(mr.updatessent));
			setText(c[6], rate(mr.reflectionsreceived));
			setText(c[7], rate(mr.interactionssent));
			setText(c[8], rate(mr.interactionsreceived));
			setText(c[9], ob.updaterate !== undefined ? rate(ob.updaterate) : '—');
			setText(c[10], ob.interactionrate !== undefined ? rate(ob.interactionrate) : '—');
			replace(c[11], ob.history ? spark(ob.history, 80, 18) : null);
			setText(c[12], f.momupdated ? ago(f.momupdated, now) : '—');
		});
	}

	function renderFederateDetail(td, item, f) {
		var k = item.key + '|' + f.handle;
		var p = td._parts;
		if (!p) {
			var ask = el('button.btn', { type: 'button', text: 'Ask the MOM for publications & subscriptions' });
			ask.addEventListener('click', function (e) {
				e.stopPropagation();
				var fed = td._f;
				if (!fed.handle) { return; }
				state.asking[k] = true;
				ui.busy(ask, infra.requestDeclarations(item.key, fed.handle).then(function () {
					window.setTimeout(pollDetails, 500);
				}, function (err) {
					ui.toast('The MOM was not asked: ' + err.message, 'err');
				}));
			});
			p = td._parts = { kv: el('div.ix-fedkv'), ask: ask, decl: el('div.ix-decl'), sig: '' };
			td.appendChild(p.kv);
			td.appendChild(el('div.ix-subhead', null, [el('span', { text: 'Publications and subscriptions' }), ask]));
			td.appendChild(p.decl);
		}
		td._f = f;
		p.ask.disabled = !f.handle || !item.declarationsavailable || !!f.resigned;
		if (!item.declarationsavailable) { p.ask.title = 'This RTI\'s MOM has no publication / subscription requests'; }
		var mom = f.mom || {};
		var rows = Object.keys(mom).filter(function (key) { return key !== 'HLAFOMmoduleDesignatorList'; }).map(function (key) {
			var v = mom[key];
			return [key.replace(/^HLA/, ''), v === true ? 'yes' : v === false ? 'no' : v === null ? '—' : String(v)];
		});
		if (f.handle) { rows.unshift(['handle', f.handle]); }
		var mods = mom.HLAFOMmoduleDesignatorList;
		if (mods && mods.length) { rows.push(['FOM modules', mods.join(', ')]); }
		var ob = f.observed;
		if (ob) { rows.push(['Received here', count(ob.updates) + ' updates, ' + count(ob.interactions) + ' interactions, ' + bytes(ob.octetrate)]); }
		replace(p.kv, facts(rows));
		var d = f.declarations || {};
		var sig = JSON.stringify(d);
		if (sig === p.sig) { return; }
		p.sig = sig;
		ui.clear(p.decl);
		if (!d.requested && !d.publications && !d.subscriptions) {
			p.decl.appendChild(el('div.dim', { text: f.frommom ? 'Not asked yet. The MOM answers with report interactions; nothing in the federation changes.'
				: 'Only federates the MOM lists can be asked about.' }));
			return;
		}
		if (d.requested && !d.publications && !d.subscriptions) {
			p.decl.appendChild(ui.loadingRow('Waiting for the MOM\'s reports…'));
			return;
		}
		p.decl.appendChild(el('div.ix-decl-cols', null, [
			declColumn('Publishes', d.publications, false),
			declColumn('Subscribes', d.subscriptions, true)
		]));
		if (d.received) { p.decl.appendChild(el('div.dim.ix-small', { text: 'Reported ' + ui.timeZ(new Date(d.received)) })); }
	}

	function declColumn(title, dec, sub) {
		var col = el('div.ix-decl-col', null, el('div.ix-decl-title', { text: title }));
		if (!dec) {
			col.appendChild(el('div.dim', { text: 'No report yet.' }));
			return col;
		}
		var objs = dec.objects || [];
		col.appendChild(el('div.ix-decl-k', { text: 'Object classes (' + objs.length + (dec.objectclasses && dec.objectclasses !== objs.length
			? ' of ' + dec.objectclasses : '') + ')' }));
		if (!objs.length) { col.appendChild(el('div.dim', { text: 'none' })); }
		objs.forEach(function (o) {
			col.appendChild(el('details.ix-decl-class', null, [
				el('summary', null, [
					el('span', { text: short(o['class']) }),
					sub && o.active === false ? el('span.ix-tag', { text: 'PASSIVE' }) : null,
					el('span.dim', { text: ' ' + (o.attributes || []).length + ' attributes' })
				]),
				el('div.ix-decl-attrs', { text: (o.attributes || []).join(', ') || '—' })
			]));
		});
		var ints = dec.interactions || [];
		col.appendChild(el('div.ix-decl-k', { text: 'Interaction classes (' + ints.length + ')' }));
		if (!ints.length) { col.appendChild(el('div.dim', { text: 'none' })); }
		ints.forEach(function (i) {
			col.appendChild(el('div.ix-decl-int', null, [
				el('span', { text: short(i['class']) }),
				sub && i.active === false ? el('span.ix-tag', { text: 'PASSIVE' }) : null
			]));
		});
		return col;
	}

	// ------------------------------------------------------------------ breakout tab

	function renderWatch(items) {
		var list = $('ix-watch-list');
		Array.prototype.slice.call(list.children).forEach(function (c) {
			if (!c.dataset.key) { list.removeChild(c); }            // the first "Reading…" / an error row
		});
		var present = items.filter(function (it) { return !it.missing; }).length;
		setText($('ix-watch-meta'), present + ' of ' + items.length + ' connected now');
		document.title = 'Infra watch (' + items.length + ') · SimWeaver';
		sync(list, items, function (it) { return it.key; }, function (it) {
			var card = el('article.ix-card');
			card._badge = el('span.ix-badge');
			card._lamp = el('span.ix-lampbox');
			card._name = el('span.ix-name');
			card._source = el('a.ix-source');
			card._stats = el('span.ix-num');
			card._spark = el('span.ix-sparkbox');
			var remove = el('button.btn', { type: 'button', text: '✕', title: 'Stop watching this one' });
			remove.addEventListener('click', function () {
				watch = watch.filter(function (k) { return k !== it.key; });
				var q = new URLSearchParams(window.location.search);
				if (watch.length) { q.set('watch', watch.join(',')); } else { q['delete']('watch'); }
				history.replaceState(null, '', '?' + q.toString());
				if (!watch.length) { window.location.href = 'infra.html'; return; }
				pollDetails();
			});
			card._detail = el('div.ix-detail');
			card.appendChild(el('header.ix-card-head', null, [card._badge, card._lamp, card._name, card._source, card._stats, card._spark,
				el('span.toolbar-spacer'), remove]));
			card.appendChild(card._detail);
			return card;
		}, function (card, it) {
			card.classList.toggle('missing', !!it.missing);
			if (it.missing) {
				var known = state.names[it.key];
				setText(card._badge, (it.key.split('-')[0] || '').toUpperCase());
				replace(card._lamp, lamp('silent'));
				setText(card._name, known || it.key);
				setText(card._source, '');
				setText(card._stats, '');
				ui.clear(card._spark);
				replace(card._detail, ui.emptyBlock('Not connected now.',
					'The datasource or gateway that had this contact is not running, or hears nothing of it any more. This tab picks it up again when it comes back.'));
				card._detail._parts = null;
				card._detail._family = null;
				return;
			}
			state.names[it.key] = label(it);
			setText(card._badge, BADGE[it.family]);
			card._badge.className = 'ix-badge fam-' + it.family;
			replace(card._lamp, lamp(it.status));
			setText(card._name, it.name);
			setText(card._source, it.source.label);
			card._source.href = it.source.link;
			var hla = it.family === 'hla3' || it.family === 'hla4';
			var n = hla ? it.federates : it.senders;
			setText(card._stats, n + (hla ? ' federate' : ' sender') + (n === 1 ? '' : 's') + ' · ' + rate(it.rate) + (hla ? ' msg/s' : ' PDU/s'));
			replace(card._spark, spark(it.history, 120, 22));
			if (card._detail.querySelector('.state-empty')) { ui.clear(card._detail); card._detail._parts = null; }
			renderDetail(card._detail, it);
		});
		save(NAMES_KEY, state.names);
	}

	// ------------------------------------------------------------------ polling

	var overviewTimer = null, detailTimer = null, detailBusy = false;

	function pollOverview() {
		window.clearTimeout(overviewTimer);
		infra.fetchOverview().then(function (o) {
			renderOverview(o);
			save(NAMES_KEY, state.names);
		}, function (err) {
			if (!state.overview) {
				replace($('ix-summary'), ui.errorRow('The Infra overview cannot be read', err, pollOverview));
			}
		}).then(function () {
			overviewTimer = window.setTimeout(pollOverview, document.hidden ? OVERVIEW_MS * 3 : OVERVIEW_MS);
		});
	}

	function pollDetails() {
		window.clearTimeout(detailTimer);
		var keys = watchMode ? watch.slice() : Object.keys(state.open);
		if (!keys.length || detailBusy) {
			detailTimer = window.setTimeout(pollDetails, DETAIL_MS);
			return;
		}
		detailBusy = true;
		infra.fetchItems(keys).then(function (items) {
			items.forEach(function (it) { state.details[it.key] = it; });
			if (watchMode) {
				renderWatch(items);
			} else {
				Array.prototype.forEach.call(document.querySelectorAll('.ix-item.open'), function (n) {
					var d = state.details[n._item.key];
					if (d && !d.missing) { renderDetail(n._detail, d); }
				});
			}
		}, function (err) {
			if (watchMode && !$('ix-watch-list').firstChild) {
				replace($('ix-watch-list'), ui.errorRow('The watched items cannot be read', err, pollDetails));
			}
		}).then(function () {
			detailBusy = false;
			detailTimer = window.setTimeout(pollDetails, document.hidden ? DETAIL_MS * 4 : DETAIL_MS);
		});
	}

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		$('ix-bar-open').addEventListener('click', openBreakout);
		$('ix-bar-clear').addEventListener('click', function () {
			state.marked = {};
			save(MARK_KEY, state.marked);
			Array.prototype.forEach.call(document.querySelectorAll('.ix-item input[type=checkbox]'), function (b) { b.checked = false; });
			updateBar();
		});
		if (watchMode) {
			document.body.classList.add('ix-watching');
			$('ix-watch').hidden = false;
			$('ix-summary').hidden = true;
			setText($('ix-sub'), 'Breakout tab');
			$('ix-watch-list').appendChild(ui.loadingRow('Reading…'));
			pollDetails();
		} else {
			renderFamilies();
			$('ix-summary').appendChild(ui.loadingRow('Acquiring…'));
			pollOverview();
			pollDetails();
		}
		updateBar();
	});
})(window, document);
