/* ============================================================
   SimWeaver Junction - the gateway page.
   01 Running gateways  (GET /api/junction/gateways, live: SSE topic "junction")
   02 Gateway configurations (GET /api/junction/configs)
   03 Kits
   Running gateways are drawn as a patch panel: the router bus, and one
   wired port per endpoint whose in/out wires light up while traffic flows.
   They are updated in place (keyed by id) so live updates never steal
   focus or hover.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jx = Rift.junction;

	var TYPE_BADGE = {
		'ieee1516-2010': 'HLA3', 'ieee1516-2025': 'HLA4', 'ieee1516-2025-fedpro': 'FEDPRO',
		'dis6': 'DIS6', 'dis7': 'DIS7', 'cdis': 'C-DIS', 'talk': 'TALK', 'j2j': 'J2J', 'kafka': 'KAFKA'
	};

	var state = { configs: [], gateways: {}, nodes: {}, streamClose: null, vardesc: {} };

	/* ---------- group variable descriptions (0.9.16): labels for people, GROUPVARn stays the name ---------- */

	/** The description of a variable: its own, else the group's (any gateway of the group may describe it). */
	function varDesc(name, own, group) {
		if (own && own[name]) { return own[name]; }
		var g = group ? state.vardesc[group] : null;
		return g && g[name] ? g[name] : '';
	}

	/** A copy of a configuration whose variables carry the group's descriptions where they have none of their own. */
	function withGroupDescriptions(cfg) {
		if (!cfg.group || !(cfg.variables || []).length) { return cfg; }
		var c = Object.assign({}, cfg);
		c.variables = cfg.variables.map(function (v) {
			return v.description ? v : Object.assign({}, v, { description: varDesc(v.name, null, cfg.group) || undefined });
		});
		return c;
	}

	/** "Home city=Kiruna" (or "GROUPVAR2=Kiruna" without a description) for each value. */
	function varsText(vars, own, group) {
		return Object.keys(vars || {}).map(function (k) {
			var d = varDesc(k, own, group);
			return (d || k) + '=' + vars[k];
		}).join(' · ');
	}

	/** The same with the variable names, for a tooltip. */
	function varsTitle(vars, own, group) {
		return Object.keys(vars || {}).map(function (k) {
			var d = varDesc(k, own, group);
			return k + (d ? ' (' + d + ')' : '') + ' = ' + vars[k];
		}).join('\n');
	}

	/* ---------- Helpers ---------- */

	function setMeta(id, countText) {
		var node = document.getElementById(id);
		if (!node) { return; }
		ui.clear(node).appendChild(document.createTextNode(countText ? countText + ' · ' : ''));
		node.appendChild(el('span.upd', { text: 'UPD ' + ui.timeZ() }));
	}

	function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }

	function badge(standard) {
		var b = TYPE_BADGE[standard] || String(standard || '?').toUpperCase();
		return el('span.jx-type.t-' + b.toLowerCase(), { text: b });
	}

	function rate(v) {
		if (v === undefined || v === null || isNaN(v)) { return '—'; }
		return (v >= 100 ? Math.round(v) : v.toFixed(1)) + '/s';
	}

	function count(v) {
		if (v === undefined || v === null) { return '—'; }
		if (v >= 1e6) { return (v / 1e6).toFixed(1) + 'M'; }
		if (v >= 1e4) { return (v / 1e3).toFixed(1) + 'k'; }
		return String(v);
	}

	function targetLabel(ep) {
		var std = ep.standard || '';
		if (std === 'dis6' || std === 'dis7' || std === 'cdis') {
			return String(ep.target) === '0' ? 'exercise 0 (all)' : 'exercise ' + ep.target;
		}
		if (std === 'j2j') { return 'session ' + (ep.target || '—'); }
		if (std === 'kafka') { return 'topic ' + (ep.target || '—'); }
		return ep.target || '—';
	}

	/** RUNNING -> ok, DEGRADED/STARTING/STOPPING -> warn, FAILED/STOPPED -> err */
	function stateClass(s) {
		if (s === 'RUNNING' || s === 'JOINED') { return 'ok'; }
		if (s === 'FAILED' || s === 'STOPPED' || s === 'LOST' || s === 'CLOSED') { return 'err'; }
		return 'warn';
	}

	/* ============================================================
	   01 Running gateways
	   ============================================================ */

	function renderRunning(list) {
		var target = document.getElementById('running-list');
		var ids = {};
		list.forEach(function (g) { ids[g.id] = true; });

		// remove gateways that are gone
		Object.keys(state.nodes).forEach(function (id) {
			if (!ids[id]) {
				var n = state.nodes[id];
				if (n.root.parentNode) { n.root.parentNode.removeChild(n.root); }
				delete state.nodes[id];
				delete state.gateways[id];
			}
		});

		if (list.length === 0) {
			ui.clear(target).appendChild(ui.emptyBlock('No gateways are running',
				'Start one from a gateway configuration below. Running gateways report in here, live.'));
			setMeta('running-meta', '0 RUNNING');
			return;
		}
		var empty = target.querySelector('.state');
		if (empty) { ui.clear(target); }
		var host = target.querySelector('.jx-gw-list');
		if (!host) { host = el('div.jx-gw-list'); target.appendChild(host); }

		var perConfig = {};
		list.forEach(function (g) { perConfig[g.configid] = (perConfig[g.configid] || 0) + 1; });
		list.forEach(function (g) {
			g.siblings = perConfig[g.configid] - 1;              // other instances of the same configuration
			state.gateways[g.id] = g;
			var n = state.nodes[g.id];
			if (!n || n.endpointCount !== (g.endpoints || []).length) {
				var fresh = buildGateway(g);
				if (n && n.root.parentNode) { n.root.parentNode.replaceChild(fresh.root, n.root); }
				else { host.appendChild(fresh.root); }
				state.nodes[g.id] = fresh;
				n = fresh;
			}
			updateGateway(n, g);
		});
		var running = list.filter(function (g) { return g.state === 'RUNNING'; }).length;
		setMeta('running-meta', plural(list.length, 'GATEWAY', 'GATEWAYS') + (running !== list.length ? ' · ' + running + ' RUNNING' : ''));
	}

	/** Builds the DOM of one gateway; values are filled in by updateGateway. */
	function buildGateway(g) {
		var n = { endpoints: {}, endpointCount: (g.endpoints || []).length };
		n.lamp = el('span.lamp', { 'aria-hidden': 'true' });
		n.state = el('span.jx-state');
		n.objects = el('span.jx-objs');
		n.started = el('span.dim');
		n.group = el('span.jx-chip.jx-grp', { hidden: true });
		n.compatBtn = el('button.btn', { type: 'button', title: 'Routes, conversions and what is dropped',
			on: { click: function () { Rift.junctionCompat.openGateway(g.id, g.name); } } }, '◇ Compatibility');
		n.removeBtn = el('button.btn.btn-danger', { type: 'button', title: 'Stop and remove ' + g.name,
			on: { click: function () { confirmRemove(g.id); } } }, '✕ Remove');

		var ports = el('ul.jx-ports');
		(g.endpoints || []).forEach(function (ep) {
			var p = buildPort(g, ep);
			n.endpoints[ep.id] = p;
			ports.appendChild(p.root);
		});
		n.bus = el('div.jx-bus', { title: 'Router' }, [
			el('span.jx-bus-label', { text: 'ROUTER' }),
			el('span.jx-bus-kind', { text: String(g.router || 'iem').toUpperCase() })
		]);
		n.stats = el('div.jx-gw-stats');
		n.problems = el('details.jx-problems', { hidden: true }, [el('summary', { text: 'Problems' }), el('ul')]);
		n.channels = el('details.jx-chpanel', { hidden: true }, [el('summary', { text: 'Channels' }), el('div.jx-ch-list')]);
		n.message = el('div.jx-gw-msg', { hidden: true });

		n.root = el('article.jx-gw', { 'data-id': g.id }, [
			el('header.jx-gw-head', null, [
				n.lamp,
				el('div.jx-gw-title', null, [
					el('h3.jx-gw-name', { text: g.name || g.configid }),
					el('span.jx-gw-cfg', { text: g.configid })
				]),
				n.state,
				n.group,
				n.objects,
				n.started,
				el('span.toolbar-spacer'),
				n.compatBtn,
				n.removeBtn
			]),
			n.message,
			el('div.jx-board', null, [n.bus, ports]),
			el('footer.jx-gw-foot', null, [n.stats, n.channels, n.problems])
		]);
		return n;
	}

	function buildPort(g, ep) {
		var p = {};
		p.wireIn = el('span.jx-lane.in', { title: 'Received from ' + ep.name + ', to the router' });
		p.wireOut = el('span.jx-lane.out', { title: 'From the router, sent to ' + ep.name });
		p.lamp = el('span.lamp', { 'aria-hidden': 'true' });
		p.state = el('span.jx-ep-state');
		p.target = el('span.jx-ep-target');
		p.rateIn = el('span.jx-rate.in');
		p.rateOut = el('span.jx-rate.out');
		p.counts = el('span.jx-ep-counts');
		p.lib = el('span.jx-ep-lib');
		p.fom = el('span.jx-ep-fom', { hidden: true });
		p.lsd = el('span.jx-ep-lsd', { hidden: true });
		p.cdis = el('span.jx-ep-cdis', { hidden: true });
		p.notice = el('div.jx-ep-msg.warn', { hidden: true });
		p.msg = el('div.jx-ep-msg', { hidden: true });
		p.inspect = ep.standard === 'j2j'
			// 0.10.0: a J2J session has nothing Rift could join; the J2J page shows the session
			? el('a.btn.jx-inspect', { href: 'j2j.html', title: 'The J2J page: the session with all its members' }, 'J2J page ↗')
			: ep.standard === 'kafka'
			// 0.13.0: a Kafka topic has nothing Rift could join
			? el('span.jx-inspect', { hidden: true })
			: el('button.btn.jx-inspect', { type: 'button',
				title: 'Open a Rift datasource on ' + ep.name + "'s target in a new tab",
				on: { click: function () { inspect(g.id, ep.id, p.inspect); } } }, 'Inspect ↗');
		p.root = el('li.jx-port', { 'data-ep': ep.id }, [
			el('div.jx-wire', { 'aria-hidden': 'true' }, [p.wireIn, p.wireOut]),
			el('div.jx-ep', null, [
				el('div.jx-ep-head', null, [
					p.lamp, badge(ep.standard),
					el('span.jx-ep-name', { text: ep.name || ep.id }),
					el('span.jx-ep-id', { text: ep.id }),
					p.state
				]),
				el('div.jx-ep-line', null, [p.target, p.lib, p.lsd, p.fom, p.cdis]),
				el('div.jx-ep-line.jx-ep-traffic', null, [
					el('span.jx-arrow', { text: '▼ IN' }), p.rateIn,
					el('span.jx-arrow', { text: '▲ OUT' }), p.rateOut,
					p.counts
				]),
				p.notice,
				p.msg,
				el('div.jx-ep-actions', null, p.inspect)
			])
		]);
		return p;
	}

	/** C-DIS endpoints (0.9.18): update mode, compression and decoder counters. */
	function cdisLine(node, dis) {
		var c = dis && dis.cdis, cs = dis && dis.cdissettings;
		node.hidden = !c;
		if (!c) { node.textContent = ''; return; }
		var ratio = c.encodeddisoctets ? Math.round(100 * c.encodedcdisoctets / c.encodeddisoctets) + ' %' : '—';
		node.textContent = 'C-DIS ' + ((cs && cs.updatemode) || 'full').toUpperCase() + ' · SENT AT ' + ratio + ' OF DIS'
			+ (c.waitingforfull ? ' · ' + c.waitingforfull + ' WAITING FOR A FULL UPDATE' : '')
			+ (c.encodeerrors + c.decodeerrors ? ' · ' + (c.encodeerrors + c.decodeerrors) + ' ERRORS' : '');
		node.title = 'C-DIS (SISO-STD-023-2024)\n'
			+ 'Encoded: ' + count(c.encoded) + ' PDUs, ' + count(c.encodeddisoctets) + ' DIS octets → ' + count(c.encodedcdisoctets) + ' C-DIS octets'
			+ ' (' + count(c.fullupdates) + ' full, ' + count(c.partialupdates) + ' partial updates)\n'
			+ 'Decoded: ' + count(c.decoded) + ' PDUs, ' + count(c.decodedcdisoctets) + ' C-DIS octets → ' + count(c.decodeddisoctets) + ' DIS octets\n'
			+ 'Not sent (no C-DIS form): ' + count(c.notconvertible) + ' · unknown C-DIS PDUs: ' + count(c.unknownpdus)
			+ ' · timed out: ' + count(c.timedout)
			+ (c.lasterror ? '\nLast error: ' + c.lasterror : '');
	}

	/** 0.10.0: a J2J endpoint's session: its model and the other members (J2J page for more). */
	function j2jLine(node, j) {
		var ms = j.members || [];
		var lost = ms.filter(function (m) { return m.state === 'lost'; }).length;
		var bad = ms.filter(function (m) { return m.state === 'mismatch'; }).length;
		node.hidden = false;
		node.textContent = (j.model || '').toUpperCase() + ' · ' + (ms.length ? ms.length + (ms.length === 1 ? ' OTHER MEMBER' : ' OTHER MEMBERS') : 'NO OTHER MEMBER YET')
			+ (lost ? ' · ' + lost + ' LOST' : '') + (bad ? ' · ' + bad + ' OTHER MODEL' : '') + (j.loopsdropped ? ' · ' + count(j.loopsdropped) + ' LOOPED BACK, NOT SENT' : '')
			+ (j.kind === 'objects' ? ' · ' + count(j.ownobjects) + ' OWN / ' + count(j.remoteobjects) + ' REMOTE OBJECTS' : '');
		node.title = 'J2J session ' + j.session + ' (' + (j.model || '') + ', transport ' + (j.transport || 'auto') + ')\n'
			+ (ms.length ? ms.map(function (m) {
				return (m.local ? 'here' : m.node) + ' / ' + m.gateway + ' · ' + m.state + (m.statemessage ? ': ' + m.statemessage : '');
			}).join('\n') : 'No other member: the PDUs reach nobody yet') + '\n'
			+ (j.kind === 'objects' ? 'Objects: ' + count(j.ownobjects) + ' of this gateway in the session, ' + count(j.remoteobjects) + ' of the others here'
				+ (j.unknownids ? ' · ' + count(j.unknownids) + ' ids not in the model' : '') + '\n' : '')
			+ 'Sent ' + count(j.sent) + (j.kind === 'objects' ? ' messages (' : ' PDUs (') + count(j.copies) + ' copies to other servers, ' + count(j.notsent) + ' not sent), received ' + count(j.received)
			+ '\nThe J2J page shows the whole session.';
	}

	/** 0.13.0: a Kafka endpoint: its topics, the consumer group and lag, the producer. */
	function kafkaLine(node, k) {
		var p = k.producer, c = k.consumer;
		var failing = (p && p.state === 'failing') || (c && c.state === 'failing');
		node.hidden = false;
		node.classList.toggle('warn', !!failing);
		// the group name is long and comes last: the line is cut at the card's width
		node.textContent = (k.kind === 'objects' ? 'JSON OBJECTS' : 'DIS PDUS')
			+ (failing ? ' · ' + (p && p.state === 'failing' ? 'PRODUCER' : 'CONSUMER') + ' FAILING' : '')
			+ (c ? ' · LAG ' + count(c.lag || 0) : '')
			+ (p && p.waiting ? ' · ' + count(p.waiting) + ' WAITING' : '')
			+ (p && (p.dropped || p.expired || p.failed) ? ' · ' + count((p.dropped || 0) + (p.expired || 0) + (p.failed || 0)) + ' NOT SENT' : '')
			+ (k.kind === 'objects' ? ' · ' + count(k.ownobjects) + ' OWN / ' + count(k.topicobjects) + ' FROM THE TOPIC' : '')
			+ (c ? ' · ' + (k.group ? 'GROUP ' + k.group : 'NO GROUP') : '');
		node.title = 'Kafka ' + k.bootstrap + ', topics ' + (k.topics || []).join(', ') + ' (' + k.direction + ')\n'
			+ (p ? 'Producer: ' + p.state + ' · sent ' + count(p.sent) + ' records in ' + count(p.requests) + ' requests · waiting ' + count(p.waiting)
				+ ' · dropped ' + count(p.dropped) + ' · expired ' + count(p.expired) + ' · failed ' + count(p.failed)
				+ (p.lasterror ? '\n  last error: ' + p.lasterror : '') + '\n' : '')
			+ (c ? 'Consumer: ' + c.state + (k.group ? ' · group ' + k.group + ' generation ' + c.generation : ' · no group')
				+ ' · partitions ' + (c.partitions || []).join(' ') + ' · lag ' + count(c.lag || 0) + ' · received ' + count(c.received)
				+ ' · rebalances ' + count(c.rebalances) + ' · commits ' + count(c.commits)
				+ (c.unsupportedbatches ? ' · ' + count(c.unsupportedbatches) + ' batches not readable (zstd?)' : '')
				+ (c.lasterror ? '\n  last error: ' + c.lasterror : '') + '\n' : '')
			+ 'Own records dropped: ' + count(k.owndropped) + ' · malformed: ' + count(k.malformed)
			+ (k.kind === 'objects' ? ' · unknown classes: ' + count(k.unknownclasses) + ' · value issues: ' + count(k.valueissues)
				+ (k.lastissue ? '\nLast issue: ' + k.lastissue : '') : '');
	}

	function updateGateway(n, g) {
		var sc = stateClass(g.state);
		n.root.className = 'jx-gw st-' + sc;
		n.lamp.className = 'lamp' + (sc === 'ok' ? '' : sc === 'err' ? ' err' : ' warn');
		n.state.textContent = g.state;
		n.state.className = 'jx-state ' + sc;
		var o = g.objects || {};
		if (g.router === 'pdu') {
			// a PDU relay has no objects: the PDUs it passed on
			var relayed = 0;
			Object.keys(o.relayed || {}).forEach(function (k) { relayed += o.relayed[k]; });
			n.objects.textContent = count(relayed) + ' PDUS RELAYED' + (o.filtered ? ' · ' + count(o.filtered) + ' FILTERED' : '');
			n.objects.title = Object.keys(o.relayed || {}).map(function (k) { return k + ': ' + o.relayed[k]; }).join('\n');
		} else {
			n.objects.textContent = count(o.bridged) + ' OBJECTS · ' + count(o.copies) + ' COPIES'
				+ (o.pending ? ' · ' + o.pending + ' PENDING' : '') + (o.failed ? ' · ' + o.failed + ' FAILED' : '')
				+ (o.netn ? ' · NETN ' + count(o.netn.objects) : '');
			// 0.12.0: router.netn: what the NETN enrichment added
			n.objects.title = o.netn ? 'NETN properties into ' + (o.netn.into || []).join(', ') + ': ' + count(o.netn.objects)
				+ ' objects, ' + count(o.netn.uniqueids) + ' UniqueIds (' + o.netn.uniqueid + '), ' + count(o.netn.callsigns)
				+ ' Callsigns (' + o.netn.callsign + ')' : '';
		}
		n.started.textContent = 'SINCE ' + ui.zulu(g.created, false);
		var vars = g.vars || {};
		var varText = varsText(vars, g.vardescriptions, g.group);
		var inst = g.instance && (g.group || g.instance !== '1' || g.siblings > 0) ? 'INSTANCE ' + g.instance : '';
		n.group.hidden = !g.group && !varText && !inst;
		n.group.textContent = [g.group ? 'GROUP ' + g.group : '', inst, varText || ''].filter(Boolean).join(' · ') || 'VARS';
		n.group.title = (g.instance ? 'Instance ' + g.instance + '. ' : '') + 'Group variables:' + (varText ? '\n' + varsTitle(vars, g.vardescriptions, g.group) : ' none');
		n.started.title = 'Started ' + g.created;
		n.message.hidden = !g.message;
		n.message.textContent = g.message || '';

		(g.endpoints || []).forEach(function (ep) {
			var p = n.endpoints[ep.id];
			if (!p) { return; }
			var es = stateClass(ep.state);
			p.root.className = 'jx-port st-' + es;
			p.lamp.className = 'lamp' + (es === 'ok' ? '' : es === 'err' ? ' err' : ' warn');
			p.state.textContent = ep.state;
			p.state.className = 'jx-ep-state ' + es;
			p.target.textContent = targetLabel(ep);
			p.target.title = (ep.standardlabel || '') + (ep.federatename ? ' · federate ' + ep.federatename : '');
			p.lib.textContent = ep.library ? (ep.library === 'class path' ? 'RTI: class path' : 'RTI: ' + shortPath(ep.library)) : '';
			p.lib.title = ep.library || '';
			p.lsd.hidden = ep.lsd === undefined;
			p.lsd.textContent = ep.lsd === undefined ? '' : 'LSD ' + (ep.lsd || '(RTI default)') + (ep.lsdoverridden ? ' ✎' : '');
			p.lsd.title = ep.lsdoverridden ? 'Chosen at the start (not the configured default)' : 'The configured default';
			p.lsd.classList.toggle('chosen', !!ep.lsdoverridden);
			p.fom.hidden = !ep.dynamicfom;
			p.fom.textContent = ep.dynamicfom ? 'FOM: FEDERATION' : '';
			p.fom.title = ep.fom || '';
			p.notice.hidden = !ep.fomnotice;
			p.notice.textContent = ep.fomnotice || '';
			if (ep.j2j) { j2jLine(p.cdis, ep.j2j); } else if (ep.kafka) { kafkaLine(p.cdis, ep.kafka); } else { cdisLine(p.cdis, ep.dis); }
			var st = ep.stats || {}, si = st['in'] || {}, so = st.out || {};
			p.rateIn.textContent = rate(si.rate);
			p.rateOut.textContent = rate(so.rate);
			p.counts.textContent = count(si.messages) + ' in · ' + count(so.messages) + ' out'
				+ (st.senderrors ? ' · ' + st.senderrors + ' errors' : '')
				+ (st.registrationfailures ? ' · ' + st.registrationfailures + ' not created' : '')
				+ (st.reconnects ? ' · ' + st.reconnects + ' reconnects' : '');
			p.wireIn.classList.toggle('flowing', !!ep.flowingin && es === 'ok');
			p.wireOut.classList.toggle('flowing', !!ep.flowingout && es === 'ok');
			p.wireIn.classList.toggle('dead', es !== 'ok');
			p.wireOut.classList.toggle('dead', es !== 'ok');
			p.msg.hidden = !ep.message;
			p.msg.textContent = ep.message || '';
			if (ep.standard !== 'j2j' && ep.standard !== 'kafka') {
				p.inspect.classList.toggle('opened', !!ep.inspectdatasource);
				p.inspect.textContent = ep.inspectdatasource ? 'Inspect ↗ (open)' : 'Inspect ↗';
			}
		});

		var r = g.routerstats || {};
		ui.clear(n.stats);
		[['UPDATES', r.updatesrouted], ['INTERACTIONS', r.interactionsrouted], ['PROVIDED', r.providesanswered],
			['TRANSCODED', r.transcoded], ['REFS', r.referencesrewritten], ['RENAMED', r.renamed],
			['QUEUE', g.queue], ['DROPS', r.queuedrops], ['FAILED CONV.', r.conversionfailures]]
			.concat(r.valuerules ? [['VALUES REPLACED', r.valuesreplaced]] : [])
			.concat(r.providesforwarded ? [['ASKED ORIGIN', r.providesforwarded]] : [])
			.forEach(function (kv) {
				var warn = (kv[0] === 'DROPS' || kv[0] === 'FAILED CONV.') && kv[1] > 0;
				n.stats.appendChild(el('span.jx-stat' + (warn ? '.warn' : ''), null, [
					el('span.k', { text: kv[0] }), el('span.v', { text: count(kv[1] || 0) })
				]));
			});
		updateTalk(n, o.talk);

		var probs = r.problems || [];
		n.problems.hidden = probs.length === 0;
		n.problems.querySelector('summary').textContent = probs.length + ' recent ' + (probs.length === 1 ? 'problem' : 'problems');
		var ul = n.problems.querySelector('ul');
		ui.clear(ul);
		probs.forEach(function (p) { ul.appendChild(el('li', { text: p })); });
	}

	/** Talk router: message counters and the channel tables (which channel is bridged where). */
	function updateTalk(n, t) {
		n.channels.hidden = !t || !t.endpoints;
		if (!t || !t.endpoints) { return; }
		var radio = t.radio;
		[['MESSAGES', t.messagesrouted, ''], ['NO CHANNEL', t.droppednocounterpart, 'warn'],
			['FILTERED', t.droppedfiltered, ''], ['UNKNOWN CH.', t.droppedunknownchannel, 'warn'],
			['CREATED CH.', t.channelscreated, 'note']]
			.concat(t.mode === 'messages' && t.trackchannels ? [['UNSEEN CH.', t.passedunknownchannel, 'note']] : [])
			.concat(radio ? [['TO RADIO', radio.framestorpr, ''], ['FROM RADIO', radio.framestotalk, ''],
				['RADIOS', radio.transmitters, ''], ['AUDIO DROPS', radio.framesdropped, 'warn']] : [])
			.forEach(function (kv) {
				var cls = kv[2] && kv[1] > 0 ? '.' + kv[2] : '';
				n.stats.appendChild(el('span.jx-stat' + cls, null, [
					el('span.k', { text: kv[0] }), el('span.v', { text: count(kv[1] || 0) })
				]));
			});
		if (t.mode === 'messages') {                  // talk.mode=messages: channels watched read-only, or none
			n.stats.appendChild(el('span.jx-stat.note', null, [el('span.k', { text: 'MODE' }),
				el('span.v', { text: t.trackchannels ? 'MESSAGES · BY NAME' : 'MESSAGES · BY UUID' })]));
			if (!t.trackchannels) {
				n.channels.hidden = true;
				return;
			}
		}
		var list = n.channels.querySelector('.jx-ch-list');
		ui.clear(list);
		var total = 0, matched = 0;
		t.endpoints.forEach(function (ep) {
			var rows = (ep.channels || []).map(function (c) {
				total++;
				var m = (c.matchedin || []).length > 0;
				if (m) { matched++; }
				return el('li' + (m ? '.ok' : c.bridged ? '.miss' : '.off'), { title: c.bridged ? '' : 'not in talk.channels' }, [
					el('span.jx-ch-name', { text: c.name || '(no name yet)' }),
					el('span.jx-ch-class', { text: c['class'] + (c.ours ? ' · created' : '') }),
					el('span.jx-ch-match', { text: !c.bridged ? 'not bridged' : m ? '⇄ ' + c.matchedin.join(', ') : 'no counterpart' }),
					c.radiohz ? el('span.jx-ch-radio', { text: (c.radiohz / 1e6).toFixed(3) + ' MHz' }) : null
				]);
			});
			list.appendChild(el('div.jx-ch-ep', null, [
				el('div.jx-ch-head', { text: ep.endpoint + (ep.radioclient ? ' · radio client online' : '') }),
				rows.length ? el('ul', null, rows) : el('div.dim', { text: 'no channels discovered' })
			]));
		});
		n.channels.querySelector('summary').textContent = 'Channels · ' + matched + ' of ' + total + ' matched';
	}

	function shortPath(p) {
		var parts = String(p).split(/[\\\/]/);
		return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : p;
	}

	function loadRunning() {
		var target = document.getElementById('running-list');
		if (!target.firstChild) { target.appendChild(ui.loadingRow('Polling running gateways…')); }
		var req = jx.fetchGateways().then(renderRunning, function (err) {
			ui.clear(target).appendChild(ui.errorRow('Could not load running gateways', err, loadRunning));
			state.nodes = {};
			setMeta('running-meta', '');
		});
		return ui.busy(document.getElementById('btn-reload-running'), req);
	}

	function confirmRemove(gid) {
		var g = state.gateways[gid] || { name: gid };
		Rift.confirmDialog.open({
			title: 'Remove gateway',
			tag: 'DEL',
			subject: g.name,
			message: 'The gateway stops: its copies of objects are deleted in every federation, its federates resign ' +
				'and its DIS sockets close. Rift datasources opened with Inspect are removed too.',
			confirmLabel: '✕ Stop & remove',
			busyLabel: 'Stopping…',
			failTitle: 'Gateway was not removed',
			action: function () { return jx.stopGateway(gid); },
			onDone: function () {
				ui.toast('Gateway ' + g.name + ' stopped', 'ok');
				loadRunning();
				loadConfigs();
			}
		});
	}

	/** Opens the tab first (no popup blocker), then points it at the datasource. */
	function inspect(gid, epId, button) {
		var tab = window.open('', '_blank');
		if (tab) {
			try {
				tab.document.write('<!DOCTYPE html><title>Junction · inspecting…</title><body style="background:#070a08;color:#8fc45a;' +
					'font:13px monospace;padding:24px">Creating a Rift datasource for endpoint ' + String(epId).replace(/[<>&]/g, '') + '…</body>');
			} catch (e) { /* cross-origin or blocked: fine */ }
		}
		button.disabled = true;
		button.classList.add('busy');
		jx.inspect(gid, epId).then(function (r) {
			button.disabled = false;
			button.classList.remove('busy');
			if (!r.page) { throw new Error('The server gave no page for the datasource'); }
			if (tab) { tab.location.href = r.page; } else { window.open(r.page, '_blank'); }
			if (r.created) { ui.toast('Rift datasource created for ' + epId, 'ok'); }
		}).catch(function (err) {
			button.disabled = false;
			button.classList.remove('busy');
			if (tab) { tab.close(); }
			ui.toast('Inspect ' + epId + ': ' + (err && err.message ? err.message : err), 'err', 8000);
		});
	}

	/* ============================================================
	   02 Gateway configurations
	   ============================================================ */

	/** The configured LSD (default first), with the other choices in the tooltip. */
	function lsdChip(ep) {
		var opts = Array.isArray(ep.lsds) && ep.lsds.length ? ep.lsds : [ep.lsd || ''];
		var label = function (v) { return v ? v : '(RTI default)'; };
		var what = ep.hlaversion === 'IEEE 1516-2025' ? 'RTI configuration name' : 'Local settings designator';
		return el('span.jx-chip.jx-lsd', {
			title: what + ': ' + label(opts[0]) + (opts.length > 1 ? '\nOther choices at the start: ' + opts.slice(1).map(label).join(', ') : '')
				+ '\nAnother value can be typed at the start.',
			text: 'LSD ' + label(opts[0]) + (opts.length > 1 ? ' +' + (opts.length - 1) : '')
		});
	}

	function configCard(cfg) {
		var avail = cfg.availability || {};
		var ports = el('ul.jx-cfg-ports', null, (cfg.endpoints || []).map(function (ep) {
			var a = avail[ep.id] || {};
			var missing = a.available === false;
			return el('li.jx-cfg-port' + (missing ? '.missing' : ''), null, [
				badge(ep.standard),
				el('span.jx-ep-name', { text: ep.name || ep.id }),
				el('span.jx-ep-id', { text: ep.id }),
				el('span.jx-cfg-target', { title: 'Default target', text: ep.isdis
					? (String(ep.defaulttarget) === '0' ? 'exercise 0 (all)' : 'exercise ' + ep.defaulttarget)
					: ep.isj2j ? 'session ' + (ep.defaulttarget || '(at start)') : ep.iskafka ? 'topic ' + (ep.defaulttarget || '—')
					: (ep.defaulttarget || '(federation at start)') }),
				ep.dynamicfom ? el('span.jx-chip', { title: "The FOM is taken from the federation's FDD when the gateway starts", text: 'DYNAMIC FOM' }) : null,
				ep.ishla ? lsdChip(ep) : null,
				ep.rtijar ? el('span.jx-chip', { title: 'Own RTI library: ' + ep.rtijar, text: 'JAR' }) : null,
				ep.rtiaddress ? el('span.jx-chip', { title: 'Federate Protocol server', text: ep.rtiaddress }) : null,
				missing ? el('span.jx-chip.err', { title: a.reason || '', text: 'RTI MISSING' }) : null
			]);
		}));
		var r = cfg.router || {};
		var rows = [
			{ label: 'ROUTER', node: el('span', null, [el('span.hl', { text: String(r.kind || 'iem').toUpperCase() }),
				el('span.dim', { text: r.source && r.source !== cfg.file ? '  ' + r.source : '' })]) },
			{ label: 'FILTER', node: el('span', { text: r.filtertext || 'all' }) },
			{ label: 'NAMES', node: el('span', { text: (r.namepolicy || '') + (r.promote ? ' · promote' : '') }) }
		];
		if (r.kind === 'pdu') {
			// a PDU relay has no class filter or names: the PDU types it passes instead
			rows.splice(1, 2, { label: 'PASSES', node: el('span', { text: (r.pdutypes && r.pdutypes.length ? 'PDU types ' + r.pdutypes.join(', ') : 'every DIS PDU') + ', as it is' }) });
		}
		if (cfg.group) { rows.unshift({ label: 'GROUP', node: el('span.hl', { text: cfg.group }) }); }
		if ((cfg.variables || []).length) {
			rows.push({ label: 'VARIABLES', node: el('span.jx-var-chips', null, cfg.variables.map(function (v) {
				var d = v.description || varDesc(v.name, null, cfg.group);
				return el('span.jx-chip.jx-var-chip' + (v['default'] ? '' : '.warn'), {
					title: v.name + (d ? ' · ' + d : '') + '\n' + (v['default'] ? 'default ' + v['default'] : 'a value is asked at the start'),
					text: (d ? d + ' ' : '') + v.name + (v['default'] ? '=' + v['default'] : '') });
			})) });
		}
		if (r.kind === 'talk') {
			rows.push({ label: 'TALK', node: ui.valueNode((r.talkmode === 'messages'
				? 'messages only, channels ' + (r.talktrackchannels ? 'by name' : 'by UUID') : 'full') + ' · ' + (r.talkmessages || []).join(', ')) });
			if (r.talkmode !== 'messages' || r.talktrackchannels) { rows.push({ label: 'CHANNELS', node: ui.valueNode(r.talkchannels) }); }
			if (r.talkradioendpoint) { rows.push({ label: 'RADIO TO', node: ui.valueNode(r.talkradioendpoint) }); }
		}
		var problems = [];
		(cfg.errors || []).forEach(function (e) { problems.push(el('li.err', { text: e })); });
		(cfg.warnings || []).forEach(function (w) { problems.push(el('li.warn', { text: w })); });
		(cfg.variableerrors || []).forEach(function (w) { problems.push(el('li.warn', { text: 'until the variables have values: ' + w })); });
		var anyMissing = Object.keys(avail).some(function (k) { return avail[k].available === false; });

		return el('article.card.jx-cfg' + (cfg.valid ? '' : '.invalid'), { 'data-config-id': cfg.id }, [
			el('header.card-head', null, [
				el('span.family.jx-family', { text: 'GW' }),
				el('h3.card-name', { text: cfg.name || cfg.id }),
				el('span.std-chip', { text: (cfg.endpoints || []).length + ' ENDPOINTS' })
			]),
			cfg.description ? el('div.card-sub', { text: cfg.description }) : null,
			ports,
			ui.kvList(rows),
			problems.length ? el('ul.jx-cfg-problems', null, problems) : null,
			el('footer.card-foot', null, [
				el('span.file', { title: 'Gateway file' }, [el('span.file-glyph', { 'aria-hidden': 'true' }), String(cfg.file || '')]),
				cfg.running ? el('span.jx-chip.ok', { text: cfg.running + ' RUNNING',
					title: 'Running instances: ' + (cfg.instances || []).join(', ') }) : null,
				cfg.autostart ? el('span.jx-chip', { text: 'AUTOSTART' }) : null
			]),
			el('div.card-actions', null, [
				el('button.btn', { type: 'button', title: 'Which classes and members will pass, from the FOMs (no connection)',
					on: { click: function () { Rift.junctionCompat.openConfig(cfg.id, cfg.name); } } }, '◇ Compatibility'),
				el('button.primary', {
					type: 'button',
					disabled: !cfg.valid || anyMissing,
					title: !cfg.valid ? 'The configuration has errors' : anyMissing ? 'An RTI library is missing' : 'Start this gateway',
					on: { click: function () { Rift.junctionStart.open(withGroupDescriptions(cfg), onStarted); } }
				}, '▶ Start gateway')
			])
		]);
	}

	function kitCards(data) {
		var target = document.getElementById('kit-list');
		ui.clear(target);
		var eps = data.endpointkits || [], rts = data.routerkits || [];
		setMeta('kit-meta', plural(eps.length, 'ENDPOINT', 'ENDPOINTS') + ' · ' + plural(rts.length, 'ROUTER', 'ROUTERS'));
		if (eps.length + rts.length === 0) {
			target.appendChild(ui.emptyBlock('No kits', 'Put *.endpoint.settings in endpoints/ and *.router.settings in routers/.'));
			return;
		}
		var grid = el('div.jx-kits');
		eps.forEach(function (ep) {
			grid.appendChild(el('div.jx-kit' + (ep.available === false ? '.missing' : ''), { title: ep.reason || '' }, [
				badge(ep.standard),
				el('div.jx-kit-body', null, [
					el('div.jx-kit-name', { text: ep.name || ep.id }),
					el('div.jx-kit-sub', { text: (ep.isdis ? 'exercise ' + ep.defaulttarget : ep.isj2j ? 'session ' + (ep.defaulttarget || '—')
						: ep.iskafka ? 'topic ' + (ep.defaulttarget || '—') : (ep.defaulttarget || '—')) + ' · ' + ep.source }),
					(ep.errors || []).length ? el('div.jx-kit-err', { text: ep.errors.join('; ') }) : null,
					ep.available === false ? el('div.jx-kit-err', { text: 'RTI missing' }) : null
				])
			]));
		});
		rts.forEach(function (r) {
			grid.appendChild(el('div.jx-kit.router', { title: r.settings || '' }, [
				el('span.jx-type.t-router', { text: String(r.kind || 'iem').toUpperCase() }),
				el('div.jx-kit-body', null, [
					el('div.jx-kit-name', { text: r.id }),
					el('div.jx-kit-sub', { text: (r.kind === 'pdu' ? (r.pdutypes && r.pdutypes.length ? 'PDU types ' + r.pdutypes.join(', ') : 'every PDU') : (r.filtertext || 'all')) + ' · ' + r.source })
				])
			]));
		});
		target.appendChild(grid);
	}

	function loadConfigs() {
		var target = document.getElementById('config-list');
		ui.clear(target).appendChild(ui.loadingRow('Reading gateway configurations…'));
		var req = jx.fetchConfigs().then(function (data) {
			var list = data.gateways || [];
			state.configs = list;
			ui.clear(target);
			if (data.directory) { document.getElementById('cfg-dir').textContent = data.directory; }
			setMeta('config-meta', plural(list.length, 'CONFIG', 'CONFIGS'));
			(data.problems || []).forEach(function (p) {
				target.appendChild(ui.errorRow('Configuration folder', new Error(p)));
			});
			if (list.length === 0) {
				target.appendChild(ui.emptyBlock('No gateway configurations',
					'Add *.junction.settings files to the configuration folder and reload.'));
			} else {
				var groups = data.groups || [];
				state.vardesc = {};
				groups.forEach(function (gr) {
					var m = state.vardesc[gr.group] = {};
					(gr.variables || []).forEach(function (v) { if (v.description) { m[v.name] = v.description; } });
				});
				var byId = {};
				list.forEach(function (c) { byId[c.id] = c; });
				var grouped = {};
				groups.forEach(function (gr) {
					var cfgs = (gr.gateways || []).map(function (id) { grouped[id] = true; return byId[id]; }).filter(Boolean);
					target.appendChild(groupSection(gr, cfgs));
				});
				var rest = list.filter(function (c) { return !grouped[c.id]; });
				if (rest.length) {
					if (groups.length) {
						target.appendChild(el('div.subhead.jx-ungrouped', null, [el('span.subhead-title', { text: 'Not in a group' })]));
					}
					var grid = el('div.card-grid');
					rest.forEach(function (c) { grid.appendChild(configCard(c)); });
					target.appendChild(grid);
				}
			}
			kitCards(data);
		}, function (err) {
			ui.clear(target).appendChild(ui.errorRow('Could not load gateway configurations', err, loadConfigs));
			setMeta('config-meta', '');
		});
		return ui.busy(document.getElementById('btn-reload-configs'), req);
	}

	/** A group: header with start / stop for all its gateways, then their cards. */
	function groupSection(gr, cfgs) {
		var anyInvalid = cfgs.some(function (c) { return !c.valid; });
		var vars = (gr.variables || []).map(function (v) {
			return (v.description ? v.description + ' (' + v.name + ')' : v.name) + (v['default'] ? '=' + v['default'] : '');
		});
		var instances = gr.instances || [];
		var stopBtn = el('button.btn.btn-danger', { type: 'button', disabled: !gr.running,
			title: !gr.running ? 'Nothing of this group runs' : instances.length > 1
				? 'Stop every instance of ' + gr.group : 'Stop the running gateways of ' + gr.group,
			on: { click: function () { confirmStopGroup(gr, null); } } }, instances.length > 1 ? '■ Stop all' : '■ Stop group');
		var startBtn = el('button.primary', { type: 'button', disabled: anyInvalid || cfgs.length === 0,
			title: anyInvalid ? 'A gateway of the group has errors'
				: 'Start every gateway of ' + gr.group + (instances.length ? ' as another instance' : ''),
			on: { click: function () { Rift.junctionGroup.open(gr, cfgs, onGroupStarted); } } }, '▶ Start group');
		var grid = el('div.card-grid');
		cfgs.forEach(function (c) { grid.appendChild(configCard(c)); });
		return el('section.jx-group', { 'data-group': gr.group }, [
			el('header.jx-group-head', null, [
				el('span.jx-group-mark', { text: 'GROUP' }),
				el('h3.jx-group-name', { text: gr.group }),
				el('span.jx-group-meta', { text: plural(cfgs.length, 'GATEWAY', 'GATEWAYS')
					+ (instances.length ? ' · ' + plural(instances.length, 'INSTANCE', 'INSTANCES') + ' RUNNING' : '')
					+ (vars.length ? ' · ' + vars.join(' · ') : '') }),
				el('span.toolbar-spacer'),
				stopBtn,
				startBtn
			]),
			instances.length ? el('ul.jx-instances', null, instances.map(function (i) { return instanceRow(gr, i, cfgs.length); })) : null,
			grid
		]);
	}

	/** One running instance of a group: its name, values, how many of its gateways run, and a stop. */
	function instanceRow(gr, inst, total) {
		var vars = inst.vars || {};
		var varText = varsText(vars, null, gr.group);
		var states = (inst.gateways || []).map(function (g) { return (g.name || g.configid) + ': ' + g.state; }).join('\n');
		return el('li.jx-inst' + (inst.complete ? '' : '.partial'), { 'data-instance': inst.instance }, [
			el('span.jx-inst-mark', { text: 'INSTANCE' }),
			el('span.jx-inst-name', { text: inst.instance }),
			el('span.jx-inst-count', { title: states, text: inst.running + '/' + total + ' RUNNING' }),
			el('span.jx-inst-vars', { title: varsTitle(vars, null, gr.group), text: varText || 'no variables' }),
			el('span.toolbar-spacer'),
			el('button.btn.btn-danger.jx-inst-stop', { type: 'button', title: 'Stop instance ' + inst.instance + ' of ' + gr.group,
				on: { click: function () { confirmStopGroup(gr, inst.instance); } } }, '■ Stop')
		]);
	}

	function confirmStopGroup(gr, instance) {
		Rift.confirmDialog.open({
			title: instance ? 'Stop instance' : 'Stop group',
			tag: 'GRP',
			subject: gr.group + (instance ? ' · instance ' + instance : ''),
			message: (instance ? 'Every running gateway of instance ' + instance + ' stops'
					: (gr.instances || []).length > 1 ? 'Every running gateway of every instance of the group stops'
					: 'Every running gateway of the group stops') +
				': its copies of objects are deleted in every federation, its federates resign and its DIS sockets close.',
			confirmLabel: instance ? '■ Stop instance' : '■ Stop group',
			busyLabel: 'Stopping…',
			failTitle: instance ? 'The instance was not stopped' : 'The group was not stopped',
			action: function () { return jx.stopGroup(gr.group, instance); },
			onDone: function () {
				ui.toast('Group ' + gr.group + (instance ? ' instance ' + instance : '') + ' stopped', 'ok');
				loadRunning();
				loadConfigs();
			}
		});
	}

	function onGroupStarted(r) {
		ui.toast('Group ' + r.group + ' instance ' + (r.instance || '1') + ': ' + r.started + ' of ' + r.total + ' started',
			r.allstarted ? 'ok' : 'err', r.allstarted ? 4000 : 10000);
		loadRunning();
		loadConfigs();
	}

	function onStarted(result) {
		var g = result && result.gateway;
		ui.toast('Gateway ' + (g ? g.name : '') + ' started', 'ok');
		if (result && result.warning) { ui.toast(result.warning, 'err', 10000); }
		loadRunning().then(function () {
			var n = g && state.nodes[g.id];
			if (n) {
				n.root.classList.add('fresh');
				if (n.root.scrollIntoView) { n.root.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
			}
		});
		loadConfigs();
	}

	/* ============================================================
	   Live updates
	   ============================================================ */

	function startStream() {
		var badgeNode = document.getElementById('jx-stream');
		function setLive(s) {
			badgeNode.textContent = s === 'live' ? 'LIVE' : s === 'lost' ? 'LIVE LOST' : 'LIVE …';
			badgeNode.className = 'jx-stream ' + s;
		}
		setLive('connecting');
		state.streamClose = Rift.api.subscribe(jx.endpoints.events(['junction']), {
			onOpen: function () { setLive('live'); loadRunning(); },
			onError: function () { setLive('lost'); },
			events: {
				gateways: function (data) {
					if (data && Array.isArray(data.gateways)) { renderRunning(data.gateways); }
				}
			}
		});
	}

	/* ---------- Start-up ---------- */

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		ui.showFlash();
		document.getElementById('btn-reload-configs').addEventListener('click', loadConfigs);
		document.getElementById('btn-reload-running').addEventListener('click', loadRunning);
		loadConfigs();
		loadRunning();
		startStream();
	});

	Rift.junctionPage = { loadConfigs: loadConfigs, loadRunning: loadRunning };
})(window, document);
