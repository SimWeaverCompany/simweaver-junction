/* ============================================================
   SimWeaver Config Assistant - the Junction part:
   gateways (etc/junctions/*.junction.settings), endpoint kits
   (endpoints/*.endpoint.settings), router kits (routers/*.router.settings)
   and groups with group variables (GROUPVARn).
   Keys as read by com.simweaver.junction.config (EndpointConfig, RouterConfig,
   ConfigLoader); see doc/junction.md.
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var ca = Rift.ca;

	/* ============================================================
	   Endpoint standards and the kits delivered with Junction
	   ============================================================ */

	var TYPES = [
		{ v: 'ieee1516-2010', l: 'HLA Evolved (IEEE 1516-2010)', badge: 'HLA 3', fam: 'hla',
			help: 'An HLA Evolved federation, joined as a federate. RTI from Rift\'s class path or its own library.' },
		{ v: 'ieee1516-2025', l: 'HLA 4 (IEEE 1516-2025)', badge: 'HLA 4', fam: 'hla',
			help: 'An HLA 4 federation. RTI from Rift\'s class path or its own library.' },
		{ v: 'ieee1516-2025-fedpro', l: 'HLA 4 over the Federate Protocol', badge: 'FEDPRO', fam: 'hla',
			help: 'An HLA 4 federation reached through a Federate Protocol server, with the vendor\'s FedPro client library.' },
		{ v: 'dis7', l: 'DIS 7 network', badge: 'DIS 7', fam: 'dis', help: 'DIS 7 on UDP (receives DIS 6 and 7), bridged as an RPR FOM v2.0 federation.' },
		{ v: 'dis6', l: 'DIS 6 network', badge: 'DIS 6', fam: 'dis', help: 'DIS 6 on UDP (receives DIS 5 and 6), bridged as an RPR FOM v2.0 federation.' },
		{ v: 'cdis', l: 'C-DIS network', badge: 'C-DIS', fam: 'dis', help: 'Compressed DIS (SISO-STD-023-2024) on UDP: a DIS 7 endpoint with C-DIS on the wire, bridged as an RPR FOM v2.0 federation.' },
		{ v: 'talk', l: 'Pitch Talk federation', badge: 'TALK', fam: 'talk', help: 'A Pitch Talk federation (pitchtalk.xml + pitchtalkeffects.xml). Use it with the Pitch Talk router.' }
	];
	function typeOf(std) { return TYPES.filter(function (t) { return t.v === std; })[0] || { v: std, l: std || '?', badge: '?', fam: 'hla', help: '' }; }

	/*
	 * The kits delivered with Junction. "sets" is what the kit file holds (shown on the endpoint card,
	 * so it is clear what comes from the kit); "target" is the kit's default federation or exercise.
	 */
	var RPR2_FILE = 'RPR_FOM_v2.0_Link16_1516-2010.xml';
	var RPR2_HLA4_FILE = 'RPR_FOM_v2.0_Link16_1516-2025.xml (the same, in the HLA 4 format)';
	var DIST_ONLY = ['Where', 'etc/junctions.dist: copy it to etc/junctions to use it'];
	var EP_KITS = [
		{ source: 'endpoints/hla3-rpr2.endpoint.settings', name: 'HLA3-RPR2', standard: 'ieee1516-2010', target: 'Blue',
			sets: [['FOM', RPR2_FILE + ' (Link 16 included)'], ['RTI', 'the RTI on Rift\'s class path, LSD "default"']] },
		{ source: 'endpoints/hla4-rpr2.endpoint.settings', name: 'HLA4-RPR2', standard: 'ieee1516-2025', target: 'Green',
			sets: [['FOM', RPR2_HLA4_FILE], ['RTI', 'the RTI on Rift\'s class path']] },
		{ source: 'endpoints/hla3-rpr3.endpoint.settings', name: 'HLA3-RPR3', standard: 'ieee1516-2010', target: 'Rpr3',
			sets: [['FOM', 'every RPR FOM v3.0 module (the rpr3 folder)'], ['RTI', 'the RTI on Rift\'s class path']] },
		{ source: 'endpoints/hla3-dynamic-fom.endpoint.settings', name: 'HLA3-dynamic-FOM', standard: 'ieee1516-2010', target: 'Blue',
			sets: [['FOM', 'taken from the running federation (its FDD)'], ['RTI', 'the RTI on Rift\'s class path, LSD "default"']] },
		{ source: 'endpoints/hla4-dynamic-fom.endpoint.settings', name: 'HLA4-dynamic-FOM', standard: 'ieee1516-2025', target: 'Green',
			sets: [['FOM', 'taken from the running federation (its FDD)'], ['RTI', 'the RTI on Rift\'s class path']] },
		{ source: 'endpoints/dis7.endpoint.settings', name: 'DIS7', standard: 'dis7', target: '1',
			sets: [['Network', 'all interfaces, UDP port 3000'], ['DIS ids', 'site 900, application 1; HLA entities keep their ids when free']] },
		{ source: 'endpoints/dis6.endpoint.settings', name: 'DIS6', standard: 'dis6', target: '1',
			sets: [['Network', 'all interfaces, UDP port 3000'], ['DIS ids', 'site 900, application 2']] },
		{ source: 'endpoints/cdis.endpoint.settings', name: 'CDIS', standard: 'cdis', target: '1',
			sets: [['Network', 'all interfaces, UDP port 3100'], ['C-DIS', 'full updates; the DIS 7 heartbeats'], ['DIS ids', 'site 900, application 2']] },
		{ source: 'endpoints/talk.endpoint.settings', name: 'Talk', standard: 'talk', target: 'PitchTalk',
			sets: [['FOM', 'pitchtalk.xml + pitchtalkeffects.xml'], ['RTI', 'the RTI on Rift\'s class path']] },
		{ source: 'endpoints/hla3-rpr2-own-rti.endpoint.settings', name: 'HLA3-RPR2-own-RTI', standard: 'ieee1516-2010', target: 'Red',
			sets: [['FOM', RPR2_FILE], ['RTI', 'its own library lib/rti/prti6/prti1516e.jar, CRC localhost:8989'],
				['Use', 'bridges a second RTI or RTI version in one gateway'], DIST_ONLY] },
		{ source: 'endpoints/hla4-fedpro-rpr2.endpoint.settings', name: 'HLA4-FedPro-RPR2', standard: 'ieee1516-2025-fedpro', target: 'Remote',
			sets: [['FOM', RPR2_HLA4_FILE], ['RTI', 'FedPro client lib/rti/fedpro/fedpro-client.jar'],
				['Server', 'fedpro.example.org:15164 - an example: edit the kit, or override hla.rtiaddress below'], DIST_ONLY] }
	];
	var RT_KITS = [
		{ source: 'routers/hla3-all.router.settings', name: 'HLA3-all', kind: 'iem',
			sets: [['Passes', 'every object and interaction class both sides have'], ['Names', 'the same, else name@endpoint']] },
		{ source: 'routers/hla4-all.router.settings', name: 'HLA4-all', kind: 'iem',
			sets: [['Passes', 'every object and interaction class both sides have'], ['Names', 'the same, else name@endpoint']] },
		{ source: 'routers/hla3-class-filter.router.settings', name: 'HLA3-class-filter', kind: 'iem',
			sets: [['Objects', 'BaseEntity.* except aggregates'], ['Interactions', 'weapon fire and detonations only'],
				['Not', 'radios, simulation management, anything else']] },
		{ source: 'routers/hla3-entitytype-filter.router.settings', name: 'HLA3-EntityType-filter', kind: 'iem',
			sets: [['Passes', 'everything'], ['Value rules', 'entity types rewritten in passing (router.valuemap.EntityType.*)']] },
		{ source: 'routers/pdu-relay.router.settings', name: 'PDU-relay', kind: 'pdu',
			sets: [['Passes', 'every DIS PDU as it is, with the receiving side\'s version and exercise'], ['Endpoints', 'DIS 6, DIS 7 and C-DIS only']] },
		{ source: 'routers/talk-messages.router.settings', name: 'Talk-Messages', kind: 'talk', mode: 'messages',
			sets: [['Messages', 'audio, radio, text, image - nothing else'], ['Channels', 'watched read-only, matched by name'], ['Mirrored', 'nothing']] },
		{ source: 'routers/talk-lobby-only.router.settings', name: 'Talk-Lobby-Only', kind: 'talk', mode: 'messages',
			sets: [['Messages', 'audio, text, image in the lobby only'], ['Channels', 'none'], ['Mirrored', 'nothing']] },
		{ source: 'routers/talk-full.router.settings', name: 'Talk-Full', kind: 'talk', mode: 'full',
			sets: [['Channels', 'matched by name, all of them'], ['Clients', 'mirrored (UUIDs kept)'], ['Messages', 'audio, radio, text, image'], DIST_ONLY] }
	];

	function copy(k) { var c = {}; Object.keys(k).forEach(function (x) { c[x] = k[x]; }); return c; }

	/** Delivered kits, the server's (when it answered) and the new kits made on this page. */
	function endpointKits(server) {
		var list = EP_KITS.map(function (k) { var c = copy(k); c.where = 'Delivered with Junction'; return c; });
		((server && server.endpointKits) || []).forEach(function (k) {
			var src = normSource(k.source, 'endpoints/');
			var known = list.filter(function (x) { return x.source === src; })[0];
			if (known) { known.onServer = true; return; }
			var sets = [];
			if (k.foms && k.foms.length) { sets.push(['FOM', k.foms.join(', ')]); }
			if (k.isdis) { sets.push(['Network', (k.disinterface || 'all interfaces') + ', UDP port ' + (k.disport || 3000)]); }
			else if (k.rtijar) { sets.push(['RTI', 'its own library ' + k.rtijar]); }
			else if (k.ishla || k.istalk) { sets.push(['RTI', 'the RTI on Rift\'s class path' + (k.lsd ? ', LSD ' + k.lsd : '')]); }
			list.push({ source: src, name: k.name || src, standard: k.standard || '', target: k.defaulttarget || '',
				sets: sets, where: 'On the server', onServer: true });
		});
		newKits().eps.forEach(function (n) {
			if (list.some(function (x) { return x.source === n.source; })) { return; }
			list.push(n);
		});
		return list;
	}
	function routerKits(server) {
		var list = RT_KITS.map(function (k) { var c = copy(k); c.where = 'Delivered with Junction'; return c; });
		((server && server.routerKits) || []).forEach(function (k) {
			var src = normSource(k.source, 'routers/');
			var known = list.filter(function (x) { return x.source === src; })[0];
			if (known) { known.onServer = true; return; }
			list.push({ source: src, name: src.replace(/^routers\//, '').replace(/\.router\.settings$/, ''), kind: k.kind || 'iem',
				mode: k.talkmode || 'full', sets: k.filtertext ? [['Passes', k.filtertext]] : [], where: 'On the server', onServer: true });
		});
		newKits().rts.forEach(function (n) {
			if (list.some(function (x) { return x.source === n.source; })) { return; }
			list.push(n);
		});
		return list;
	}

	/** The kits that gateways on this page save ("Also save as a kit"), so other gateways can pick them. */
	function newKits() {
		var st = ca.state && ca.state.junction;
		var out = { eps: [], rts: [] };
		if (!st) { return out; }
		st.gws.forEach(function (g) {
			g.eps.forEach(function (ep) {
				if (ep.src !== 'new' || !ep.saveKit) { return; }
				var t = typeOf(ep.standard);
				var sets = [];
				if (t.fam === 'dis') { sets.push(['Network', (ep.disIf === 'all' ? 'all interfaces' : ep.disIf === 'address' ? ep.disAddr : ep.disGroup) + ', UDP port ' + ep.disPort]); }
				else {
					if (ca.splitList(ep.foms).length) { sets.push(['FOM', ca.splitList(ep.foms).join(', ')]); }
					sets.push(['RTI', ep.rti === 'jar' || ep.standard === 'ieee1516-2025-fedpro' ? 'its own library ' + (ep.rtijar || '?') : 'the RTI on Rift\'s class path']);
				}
				out.eps.push({ source: 'endpoints/' + kitFileName(ep), name: (ep.name || t.l) + ' (new)', standard: ep.standard,
					target: t.fam === 'dis' ? ep.disExid : ep.federation, sets: sets, where: 'New kits made on this page',
					madeBy: gwId(g) + ' / ' + ep.id, isNew: true });
			});
			if (g.rt.src === 'new' && g.rt.saveKit) {
				out.rts.push({ source: 'routers/' + rkitFileName(g.rt, g), name: rkitFileName(g.rt, g).replace(/\.router\.settings$/, '') + ' (new)',
					kind: g.rt.kind, mode: g.rt.mode, sets: [['Made by', 'gateway ' + gwId(g)]], where: 'New kits made on this page', madeBy: gwId(g), isNew: true });
			}
		});
		return out;
	}

	/** Who uses a kit: gateways on the server, and the other gateways on this page. */
	function kitUsers(source, server, exceptGw) {
		var users = [];
		((server && server.gateways) || []).forEach(function (g) {
			(g.endpoints || []).forEach(function (e) {
				if (e.source && normSource(e.source, source.split('/')[0] + '/') === source) { users.push(g.id + ' (server)'); }
			});
			if (g.router && g.router.source && normSource(g.router.source, 'routers/') === source) { users.push(g.id + ' (server)'); }
		});
		var st = ca.state && ca.state.junction;
		(st ? st.gws : []).forEach(function (g) {
			if (g === exceptGw) { return; }
			var uses = g.eps.some(function (e) { return e.src === 'kit' && e.kit === source; }) || (g.rt.src === 'kit' && g.rt.kit === source);
			if (uses) { users.push(gwId(g)); }
		});
		return users.filter(function (u, i) { return users.indexOf(u) === i; });
	}

	/** "…/etc/junctions/endpoints/x.endpoint.settings" -> "endpoints/x.endpoint.settings" */
	function normSource(s, dir) {
		var t = String(s || '').replace(/\\/g, '/');
		var i = t.lastIndexOf('/' + dir);
		if (i >= 0) { return t.slice(i + 1); }
		return t.indexOf(dir) === 0 ? t : dir + t.split('/').pop();
	}

	/* ============================================================
	   State
	   ============================================================ */

	function newEndpoint(over) {
		var e = {
			id: 'a', src: 'kit', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: '', name: '', extra: '',
			standard: 'ieee1516-2010', foms: 'RPR_FOM_v2.0_Link16_1516-2010.xml', federation: '',
			rti: 'classpath', lsd: '', rtijar: '', rtiaddress: '', rtisettings: '', rtifactory: '',
			createfed: true, destroyfed: false, fedname: '', fedtype: '', reconnect: true, reconnectSecs: '5', reftypes: '', talkhla: 'ieee1516-2010',
			disIf: 'all', disAddr: '', disGroup: '239.1.2.3', disNic: '', disPort: '3000', disExid: '1', sendaddr: '', ttl: '8',
			siteid: '900', appid: '1', entityids: 'keep', heartbeat: '5', timeout: '12',
			cdisMode: 'full', cdisMult: '2.4', cdisTimeout: '2.4', hbEs: '5', hbEe: '10', hbDes: '5', hbTx: '2', hbIff: '10',
			filters: { inoa: '', inob: '', inia: '', inib: '', outoa: '', outob: '', outia: '', outib: '' },
			saveKit: false, kitName: ''
		};
		Object.keys(over || {}).forEach(function (k) { e[k] = over[k]; });
		return e;
	}

	function newRouter(over) {
		var r = {
			kind: 'iem', src: 'kit', kit: 'routers/hla3-all.router.settings', extra: '',
			preset: 'all', oa: '*', ob: '', ia: '*', ib: '', names: 'same-or-suffix', prefix: '', promote: true, del: true, queue: '50000', mapping: '',
			mode: 'full', msgs: { audio: true, radio: true, text: true, image: true }, track: true, match: 'name', channels: '*', mirror: true, create: false,
			cmap: [], umap: [], radio: false, radioEp: '', rmap: [{ ch: '', mhz: '' }], entityid: '900-99-1',
			sr: '16000', order: 'little', enc: 'pcm16be', rprsr: '', hang: '500', pduTypes: '',
			saveKit: false, kitName: ''
		};
		Object.keys(over || {}).forEach(function (k) { r[k] = over[k]; });
		return r;
	}

	function newGateway(over) {
		var g = { id: '', idAuto: true, name: 'New gateway', description: '', inGroup: false, autostart: false,
			eps: [newEndpoint({ id: 'a', src: 'new' }), newEndpoint({ id: 'b', src: 'new', standard: 'dis7' })],
			rt: newRouter() };
		Object.keys(over || {}).forEach(function (k) { g[k] = over[k]; });
		return g;
	}

	var BLANK = JSON.stringify(newGateway());

	ca.junctionDefaults = function () {
		return { gws: [newGateway()], sel: 0, group: { name: '', vars: [] } };
	};

	var SCENARIOS = [
		{ t: 'HLA ⇄ DIS 7', s: 'An HLA Evolved RPR federation and a DIS 7 network.', g: function () {
			return newGateway({ name: 'HLA - DIS 7', eps: [
				newEndpoint({ id: 'hla', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'dis', kit: 'endpoints/dis7.endpoint.settings', target: '1' })] }); } },
		{ t: 'HLA ⇄ C-DIS', s: 'An HLA Evolved RPR federation and a Compressed DIS network.', g: function () {
			return newGateway({ name: 'HLA - C-DIS', eps: [
				newEndpoint({ id: 'hla', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'cdis', kit: 'endpoints/cdis.endpoint.settings', target: '1' })] }); } },
		{ t: 'DIS 7 ⇄ C-DIS relay', s: 'A DIS 7 and a C-DIS network, PDU by PDU (all 22 C-DIS PDU types).', g: function () {
			return newGateway({ name: 'DIS 7 - C-DIS', eps: [
				newEndpoint({ id: 'dis', kit: 'endpoints/dis7.endpoint.settings', target: '1' }),
				newEndpoint({ id: 'cdis', kit: 'endpoints/cdis.endpoint.settings', target: '1' })],
				rt: newRouter({ kind: 'pdu', kit: 'routers/pdu-relay.router.settings' }) }); } },
		{ t: 'HLA Evolved ⇄ HLA 4', s: 'An IEEE 1516-2010 and an IEEE 1516-2025 federation, both RPR v2.', g: function () {
			return newGateway({ name: 'HLA 3 - HLA 4', eps: [
				newEndpoint({ id: 'hla3', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'hla4', kit: 'endpoints/hla4-rpr2.endpoint.settings', target: 'Green' })] }); } },
		{ t: 'Two RTI versions', s: 'E.g. pRTI 5 (class path) and pRTI 6 (own library) in one gateway.', g: function () {
			return newGateway({ name: 'pRTI 5 - pRTI 6', eps: [
				newEndpoint({ id: 'prti5', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'prti6', kit: 'endpoints/hla3-rpr2-own-rti.endpoint.settings', target: 'Red' })] }); } },
		{ t: 'RPR v2 ⇄ RPR v3', s: 'Converts between the RPR FOM v2.0 and v3.0.', g: function () {
			return newGateway({ name: 'RPR v2 - RPR v3', eps: [
				newEndpoint({ id: 'v2', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'v3', kit: 'endpoints/hla3-rpr3.endpoint.settings', target: 'Rpr3' })] }); } },
		{ t: 'Remote over FedPro', s: 'A local federation and a remote HLA 4 one through a Federate Protocol server.', g: function () {
			return newGateway({ name: 'Local - remote (FedPro)', eps: [
				newEndpoint({ id: 'local', kit: 'endpoints/hla3-rpr2.endpoint.settings', target: 'Blue' }),
				newEndpoint({ id: 'remote', src: 'new', standard: 'ieee1516-2025-fedpro', name: 'Remote site', federation: 'Remote',
					foms: ca.RPR2_2025_CORE, rti: 'jar', rtijar: 'lib/rti/fedpro/fedpro-client.jar', rtiaddress: 'fedpro.example.org:15164' })],
				rt: newRouter({ kit: 'routers/hla3-class-filter.router.settings' }) }); } },
		{ t: 'Pitch Talk sites + radio', s: 'Two Talk federations, channels by name, a channel on DIS radio.', g: function () {
			return newGateway({ name: 'Talk sites + DIS radio', eps: [
				newEndpoint({ id: 'site1', kit: 'endpoints/talk.endpoint.settings', target: 'TalkSite1' }),
				newEndpoint({ id: 'site2', kit: 'endpoints/talk.endpoint.settings', target: 'TalkSite2' }),
				newEndpoint({ id: 'radio', kit: 'endpoints/dis7.endpoint.settings', target: '1' })],
				rt: newRouter({ kind: 'talk', kit: 'routers/talk-full.router.settings', radio: true, radioEp: 'radio',
					rmap: [{ ch: 'Battalion', mhz: '251.000' }] }) }); } },
		{ t: 'Talk messages only', s: 'Only audio, radio and text; channel X there is channel Y at home.', g: function () {
			return newGateway({ name: 'Talk messages', eps: [
				newEndpoint({ id: 'home', kit: 'endpoints/talk.endpoint.settings', target: 'TalkHome' }),
				newEndpoint({ id: 'remote', kit: 'endpoints/talk.endpoint.settings', target: 'TalkRemote' })],
				rt: newRouter({ kind: 'talk', kit: 'routers/talk-messages.router.settings', mode: 'messages',
					cmap: [{ scope: 'home', from: 'Net Alpha', to: 'Bravo Net' }] }) }); } }
	];

	/* ============================================================
	   Derived values
	   ============================================================ */

	function gwId(g) {
		var id = g.idAuto || !g.id ? ca.slug(g.name, 'gateway') : g.id;
		return id.replace(/\.junction\.settings$/, '');
	}

	function kitOf(ep, server) {
		return endpointKits(server).filter(function (k) { return k.source === ep.kit; })[0] || null;
	}
	function rkitOf(rt, server) {
		return routerKits(server).filter(function (k) { return k.source === rt.kit; })[0] || null;
	}

	/** The standard of an endpoint, from its kit or its own settings. */
	function stdOf(ep, server) {
		if (ep.src === 'kit') { var k = kitOf(ep, server); return k ? k.standard : ''; }
		return ep.standard;
	}
	function famOf(ep, server) { return typeOf(stdOf(ep, server)).fam; }

	/** The target the endpoint starts with (federation or exercise). */
	function targetOf(ep, server) {
		if (ep.src === 'kit') {
			if (String(ep.target).trim()) { return String(ep.target).trim(); }
			var k = kitOf(ep, server); return k ? String(k.target || '') : '';
		}
		return famOf(ep, server) === 'dis' ? String(ep.disExid).trim() : String(ep.federation).trim();
	}

	function routerKind(rt, server) {
		if (rt.src === 'kit') { var k = rkitOf(rt, server); return k ? k.kind : rt.kind; }
		return rt.kind;
	}
	function routerMode(rt, server) {
		if (rt.src === 'kit') { var k = rkitOf(rt, server); return k && k.mode ? k.mode : 'full'; }
		return rt.mode;
	}

	function kitFileName(ep) { return ca.slug(ep.kitName || ep.name || ep.id, 'endpoint').replace(/\.endpoint\.settings$/, '') + '.endpoint.settings'; }
	function rkitFileName(rt, g) { return ca.slug(rt.kitName || gwId(g) + '-router', 'router').replace(/\.router\.settings$/, '') + '.router.settings'; }

	function mhzToHz(s) {
		var t = String(s || '').trim().replace(',', '.');
		if (!/^\d+(\.\d+)?$/.test(t)) { return null; }
		return Math.round(parseFloat(t) * 1e6);
	}

	/* ============================================================
	   Form
	   ============================================================ */

	function panel(idx, title, meta, body) {
		return el('section.panel', null, [
			el('div.panel-head', null, [el('span.idx', { text: idx }), el('h2.title', { text: title }),
				meta ? el('span.meta', null, meta) : null]),
			el('div.panel-body', null, body)
		]);
	}

	ca.renderJunction = function (host, st, server) {
		ui.clear(host);
		if (!st.gws.length) { st.gws.push(newGateway()); }
		if (st.sel >= st.gws.length) { st.sel = st.gws.length - 1; }
		host.appendChild(scenarioPanel(st));
		host.appendChild(gatewayPanel(st, server));
		host.appendChild(groupPanel(st, server));
	};

	function scenarioPanel(st) {
		return panel('J1', 'What do you want to bridge?', null, [
			el('p.ca-lead', null, ca.rich('A **gateway** connects two or more **endpoints** (HLA federations, DIS networks, Pitch Talk federations) through one **router**. Pick a starting point: it adds a gateway you can then change. Pick several to build a **group**.')),
			anatomy(),
			el('div.ca-scen', null, SCENARIOS.map(function (sc) {
				return el('button', { type: 'button', on: { click: function () { addGateway(st, sc.g()); } } },
					[el('span.t', { text: sc.t }), el('span.s', { text: sc.s })]);
			}).concat([el('button.blank', { type: 'button', on: { click: function () { addGateway(st, newGateway()); } } },
				[el('span.t', { text: 'From scratch' }), el('span.s', { text: 'Two endpoints defined here, you choose everything.' })])]))
		]);
	}

	/** How the files fit together: a gateway file, endpoint kits, router kits. */
	function anatomy() {
		function box(cls, tag, path, text) {
			return el('div.ca-anat-box.' + cls, null, [el('span.ca-anat-tag', { text: tag }), el('code', { text: path }), el('span.ca-anat-text', null, ca.rich(text))]);
		}
		return ca.more('How the files fit together: gateway files and kits', [
			el('div.ca-anat', null, [
				box('gw', 'Gateway file', 'etc/junctions/<name>.junction.settings',
					'One per gateway. Names its endpoints and its router, and holds what is **this gateway\'s own**: e.g. the federation or exercise each endpoint joins.'),
				el('div.ca-anat-arrow', { 'aria-hidden': 'true' }, [el('span', { text: 'uses' }), el('span.a', { text: '\u2192' })]),
				el('div.ca-anat-kits', null, [
					box('ep', 'Endpoint kit', 'endpoints/<kit>.endpoint.settings',
						'**One kind of endpoint**: the standard, FOM modules, RTI library and connection, or the DIS network. Shared by every gateway that picks it.'),
					box('rt', 'Router kit', 'routers/<kit>.router.settings',
						'**What passes, and how**: class filters, naming, or the Talk settings. Shared too.')
				])
			]),
			ca.note('For each endpoint and for the router you choose:\n**Use a kit**: the gateway file only names the kit, and sets its own federation or exercise.\n**Write them in this gateway file**: everything is in the one file, nothing is shared.\nKits pay off when several gateways use the same RTI, FOM or network: change the kit once, and every gateway that uses it follows. For one or two gateways, writing the settings in the gateway file is simpler. Junction delivers ready kits; the page can also save new ones.')
		], true);
	}

	function addGateway(st, g) {
		if (st.group.name && st.gws.some(function (x) { return x.inGroup; })) { g.inGroup = true; }
		if (st.gws.length === 1 && JSON.stringify(st.gws[0]) === BLANK) { st.gws[0] = g; st.sel = 0; }
		else { st.gws.push(g); st.sel = st.gws.length - 1; }
		ca.changed(true);
	}

	function gatewayPanel(st, server) {
		var g = st.gws[st.sel];
		var tabs = el('div.ca-tabs', null, st.gws.map(function (x, i) {
			return el('button.tab', { type: 'button', 'aria-pressed': i === st.sel ? 'true' : 'false', title: gwId(x) + '.junction.settings',
				on: { click: function () { st.sel = i; ca.changed(true); } } }, [x.name || gwId(x),
				x.inGroup && st.group.name ? el('span.grp', { text: '◆ ' + st.group.name }) : null]);
		}).concat([
			el('span.ca-spacer'),
			el('button.btn', { type: 'button', title: 'A copy of this gateway', on: { click: function () {
				var c = JSON.parse(JSON.stringify(g)); c.name = (c.name || 'Gateway') + ' copy'; c.idAuto = true;
				st.gws.push(c); st.sel = st.gws.length - 1; ca.changed(true);
			} } }, 'Duplicate'),
			st.gws.length > 1 ? el('button.btn.btn-danger', { type: 'button', on: { click: function () {
				st.gws.splice(st.sel, 1); st.sel = Math.max(0, st.sel - 1); ca.changed(true);
			} } }, 'Remove') : null
		]));

		var body = [tabs, el('p.ca-help', { style: 'margin:-6px 0 10px 0' }, ca.rich('Each tab is **one gateway** and becomes **one gateway file**. Add more with the starting points above.'))];
		var gwFile = 'etc/junctions/' + gwId(g) + '.junction.settings';
		var gbox = el('div.ca-card.gw', { id: 'ca-box-gw' }, [
			el('div.ca-card-head', null, [
				el('span.ca-boxrole', { text: 'Gateway' }),
				el('span.ca-card-title', { text: g.name || gwId(g) }),
				el('span.ca-card-sub', { text: 'its name, group and options; its endpoints and router follow below' })
			]),
			ca.goesInto([{ role: 'gateway', path: gwFile }])
		]);
		body.push(gbox);
		gbox.appendChild(ca.grid([
			ca.field('Gateway name', ca.input(g, 'name', { placeholder: 'e.g. Blue - Red', validate: function (v) { return v.trim() ? null : 'A name is needed'; } }),
				'Shown on the Junction page.', { req: true }),
			ca.field('File name', ca.input(g, 'id', { placeholder: gwId(g), onInput: function (v) { g.idAuto = !v; },
				validate: function (v) { return v && !/^[A-Za-z0-9_.-]+$/.test(v) ? 'Letters, digits, . _ - only' : null; } }),
				ca.rich('Saved as `etc/junctions/' + gwId(g) + '.junction.settings`; the name part is the gateway\'s id in the API.'), { opt: true }),
			ca.field('Description', ca.input(g, 'description', { placeholder: 'what it is for', vars: true }), null, { opt: true }),
			ca.field('Options', el('div.ca-checks', null, [
				ca.check(g, 'inGroup', 'Member of the group' + (st.group.name ? ' **' + st.group.name + '**' : ' (Group panel below)')),
				ca.check(g, 'autostart', 'Start with the server', { title: 'junction.autostart=true' })
			]))
		]));

		body.push(ca.sub('Endpoints of this gateway (' + g.eps.length + ') - one box each'));
		g.eps.forEach(function (ep, i) { body.push(endpointCard(g, ep, i, server)); });
		body.push(el('div', { style: 'margin:-4px 0 14px 0' }, el('button.btn', { type: 'button', on: { click: function () {
			var used = g.eps.map(function (e) { return e.id; });
			var n = 'abcdefghijklmnopqrstuvwxyz'.split('').filter(function (c) { return used.indexOf(c) < 0; })[0] || 'ep' + (g.eps.length + 1);
			g.eps.push(newEndpoint({ id: n }));
			ca.changed(true);
		} } }, '+ Add endpoint')));

		body.push(ca.sub('Router of this gateway'));
		body.push(routerCard(g, server));
		return panel('J2', 'Gateways', [el('span', { text: st.gws.length + ' gateway' + (st.gws.length > 1 ? 's' : '') })], body);
	}

	/* ---------- what a kit holds, and who shares it ---------- */

	/**
	 * @param {Object} k   the kit (endpointKits / routerKits entry)
	 * @param {string} kind 'endpoint' | 'router'
	 */
	function kitBox(k, kind, server, g) {
		var rows = [];
		if (kind === 'endpoint') { rows.push(['Standard', typeOf(k.standard).l]); }
		(k.sets || []).forEach(function (r) { rows.push(r); });
		if (kind === 'endpoint' && k.target) { rows.push([typeOf(k.standard).fam === 'dis' ? 'Exercise' : 'Federation', k.target + '  (default; this gateway can set its own below)']); }
		var users = kitUsers(k.source, server, g);
		var status = k.isNew ? 'NEW, made on this page by ' + k.madeBy : k.onServer ? 'ON THE SERVER' : k.where === 'Delivered with Junction' ? 'DELIVERED WITH JUNCTION' : '';
		return el('div.ca-kitbox', null, [
			el('div.ca-kitbox-head', null, [
				el('span.k', { text: 'In the kit file' }),
				el('code', { text: 'etc/junctions/' + k.source }),
				status ? el('span.ca-kit-status' + (k.isNew ? '.new' : ''), { text: status }) : null
			]),
			el('dl.ca-kitbox-sets', null, rows.reduce(function (acc, r) {
				acc.push(el('dt', { text: r[0] }));
				acc.push(el('dd', { text: r[1] }));
				return acc;
			}, [])),
			el('div.ca-kitbox-foot', null, ca.rich(users.length
				? '**Shared:** also used by ' + users.slice(0, 6).join(', ') + (users.length > 6 ? ' and ' + (users.length - 6) + ' more' : '') + '. A change to the kit changes all of them.'
				: 'No other gateway uses this kit yet. A change to the kit would change every gateway that picks it.')),
			!k.onServer && !k.isNew && server && server.junction
				? el('div.ca-kitbox-foot.warn', null, ca.rich('Not on the server: copy `' + k.source + '` from the kits delivered with Junction.')) : null
		]);
	}

	/* ---------- which files a box ends up in ---------- */

	function endpointTargets(g, ep, server) {
		var gwFile = 'etc/junctions/' + gwId(g) + '.junction.settings';
		var fam = famOf(ep, server);
		var tgt = fam === 'dis' ? 'exercise' : 'federation';
		if (ep.src === 'kit') {
			var k = kitOf(ep, server);
			var out = [{ role: 'gateway', path: gwFile, note: 'names the kit, sets the ' + tgt }];
			if (k) {
				out.push(k.isNew ? { role: 'epkit', path: 'etc/junctions/' + k.source, note: 'made by ' + k.madeBy }
					: { role: 'existing', path: 'etc/junctions/' + k.source, note: 'nothing to create' });
			}
			return out;
		}
		if (ep.saveKit) {
			return [{ role: 'epkit', path: 'etc/junctions/endpoints/' + kitFileName(ep), note: 'its settings' },
				{ role: 'gateway', path: gwFile, note: 'names the kit, sets the ' + tgt }];
		}
		return [{ role: 'gateway', path: gwFile, note: 'all its settings' }];
	}

	function routerTargets(g, server) {
		var rt = g.rt;
		var gwFile = 'etc/junctions/' + gwId(g) + '.junction.settings';
		var talk = routerKind(rt, server) === 'talk';
		var extra = talk ? ', the channel names and radio bridge' : '';
		if (rt.src === 'kit') {
			var k = rkitOf(rt, server);
			var out = [{ role: 'gateway', path: gwFile, note: 'names the kit' + extra }];
			if (k) {
				out.push(k.isNew ? { role: 'rtkit', path: 'etc/junctions/' + k.source, note: 'made by ' + k.madeBy }
					: { role: 'existing', path: 'etc/junctions/' + k.source, note: 'nothing to create' });
			}
			return out;
		}
		if (rt.saveKit) {
			return [{ role: 'rtkit', path: 'etc/junctions/routers/' + rkitFileName(rt, g), note: 'its settings' },
				{ role: 'gateway', path: gwFile, note: 'names the kit' + extra }];
		}
		return [{ role: 'gateway', path: gwFile, note: 'all its settings' }];
	}

	/* ---------- one endpoint ---------- */

	function endpointCard(g, ep, idx, server) {
		var t = typeOf(stdOf(ep, server));
		var kits = endpointKits(server);
		var card = el('div.ca-card.ep-' + t.fam, { id: 'ca-box-ep-' + idx });
		card.appendChild(el('div.ca-card-head', null, [
			el('span.ca-boxrole', { text: 'Endpoint' }),
			el('span.ca-badge', { text: t.badge }),
			el('span.ca-card-title', { text: ep.id || '(no id)' }),
			el('span.ca-card-sub', { text: ep.src === 'kit' ? (kitOf(ep, server) || { name: 'choose a kit' }).name : (ep.name || t.l) }),
			el('span.ca-spacer'),
			el('button.btn.btn-danger', { type: 'button', title: 'Remove this endpoint', on: { click: function () {
				g.eps.splice(idx, 1); ca.changed(true);
			} } }, 'Remove')
		]));
		card.appendChild(ca.goesInto(endpointTargets(g, ep, server)));
		card.appendChild(ca.grid([
			ca.field('Endpoint id', ca.input(ep, 'id', { structural: true,
				validate: function (v) { return /^[A-Za-z0-9_-]+$/.test(v) ? null : 'Letters, digits, - and _'; } }),
				'Used in the settings keys and on the page, e.g. blue, dis, site1.', { req: true }),
			ca.field('Where its settings live', ca.seg(ep, 'src', [
				{ v: 'kit', l: 'Use a kit (shared file)', title: 'A reusable endpoint file in etc/junctions/endpoints' },
				{ v: 'new', l: 'Write them in this gateway file' }
			]), ep.src === 'kit'
				? ca.rich('The **kit** holds the standard, FOM, RTI or network settings. This gateway file only names the kit and sets its own federation or exercise.')
				: ca.rich('All settings of this endpoint go into **this gateway\'s file**. Nothing is shared with other gateways (unless you also save them as a kit, at the bottom).'), { wide: true })
		]));

		if (ep.src === 'kit') {
			var opts = kits.map(function (k) { return { v: k.source, l: k.name + ' (' + typeOf(k.standard).badge + ')', g: k.where }; });
			var k = kitOf(ep, server);
			var fam = k ? typeOf(k.standard).fam : 'hla';
			card.appendChild(ca.grid([
				ca.field('Kit', ca.select(ep, 'kit', opts), k ? null : 'Choose a kit.', { req: true, wide: true })
			]));
			if (k) { card.appendChild(kitBox(k, 'endpoint', server, g)); }
			card.appendChild(ca.sub('Set in this gateway file only'));
			card.appendChild(ca.grid([
				ca.field(fam === 'dis' ? 'DIS exercise' : 'Federation', ca.input(ep, 'target', {
					placeholder: k && k.target ? k.target + ' (the kit\'s)' : '', vars: true,
					validate: function (v) { return fam === 'dis' && v && !ca.hasVar(v) && !ca.isInt(v, 0, 255) ? '0 - 255' : null; } }),
					fam === 'dis' ? 'The exercise id, 0-255 (0 = every exercise). Empty: the kit\'s default.' : 'The federation this gateway joins. Empty: the kit\'s default. It can also be changed when starting.'),
				ca.field('Display name', ca.input(ep, 'name', { placeholder: k ? k.name : '' }), null, { opt: true })
			]));
			card.appendChild(ca.more('More: change other kit settings for this gateway only, class filters', [
				ca.grid([ca.field('Other settings', ca.area(ep, 'extra', { rows: 3, placeholder: fam === 'dis' ? 'dis.port=3001\ndis.sendaddress=192.168.1.255' : 'hla.lsd=crcAddress=rti-host:8989\nhla.rtijar=lib/rti/other/prti1516e.jar' }),
					ca.rich('One `key=value` per line, keys as in the kit without `endpoint.` (e.g. `hla.lsd`). They override the kit for this gateway only.'), { wide: true })]),
				filterBlock(ep)
			]));
		} else {
			card.appendChild(ca.grid([
				ca.field('Standard', ca.select(ep, 'standard', TYPES.map(function (x) { return { v: x.v, l: x.l }; }), { onChange: function (v) {
					if (v === 'ieee1516-2025' || v === 'ieee1516-2025-fedpro') { if (/RPR_FOM_v2\.0_Link16/.test(ep.foms) || !ep.foms) { ep.foms = ca.RPR2_2025_CORE; } }
					else if (v === 'ieee1516-2010' && /RPR-Base_v2\.0/.test(ep.foms)) { ep.foms = 'RPR_FOM_v2.0_Link16_1516-2010.xml'; }
					if (v === 'talk') { ep.foms = ''; }
					if (v === 'ieee1516-2025-fedpro') { ep.rti = 'jar'; }
				} }), t.help, { req: true }),
				ca.field('Display name', ca.input(ep, 'name', { placeholder: t.l }), null, { opt: true })
			]));
			if (t.fam === 'dis') { disBlock(card, ep); } else { hlaBlock(card, ep); }
			card.appendChild(ca.more('More: class filters for this endpoint', [filterBlock(ep)]));
			card.appendChild(ca.sub('Reuse'));
			card.appendChild(ca.grid([
				ca.field('Kit', ca.check(ep, 'saveKit', '**Also save as a kit**: a shared file that other gateways can pick'), null, { wide: !ep.saveKit }),
				ep.saveKit ? ca.field('Kit file name', ca.input(ep, 'kitName', { placeholder: kitFileName(ep).replace(/\.endpoint\.settings$/, '') }),
					ca.rich('`etc/junctions/endpoints/' + kitFileName(ep) + '`')) : null
			]));
			if (ep.saveKit) {
				card.appendChild(ca.note('The settings above go into the **kit file** `endpoints/' + kitFileName(ep) + '`. This gateway file then only names the kit and sets its '
					+ (t.fam === 'dis' ? 'exercise' : 'federation') + '. Other gateways can pick it under **Use a kit** (listed as a new kit).'));
			}
		}
		return card;
	}

	function hlaBlock(card, ep) {
		var talk = ep.standard === 'talk';
		var fedpro = ep.standard === 'ieee1516-2025-fedpro';
		var folder = talk ? ca.fomFolder(ep.talkhla) : ca.fomFolder(ep.standard);
		card.appendChild(ca.grid([
			ca.field('Federation', ca.input(ep, 'federation', { placeholder: talk ? 'e.g. PitchTalk' : 'e.g. Blue', vars: true }),
				'The default target. The Start dialog lists the federations the RTI knows.', { req: true }),
			ca.field('FOM modules', ca.fomPicker(ep, 'foms', folder, { vars: true }),
				talk ? ca.rich('Empty: `pitchtalk.xml` and `pitchtalkeffects.xml`, the Talk FOM.')
					: ca.rich('Files or folders in `etc/fom/' + folder + '`, or full paths. Must match the federation\'s FOM.'), { req: !talk, wide: true })
		]));
		card.appendChild(ca.grid([
			ca.field('RTI library', fedpro ? el('div.ca-help', null, ca.rich('The **vendor\'s Federate Protocol client** library, loaded on its own.'))
				: ca.seg(ep, 'rti', [{ v: 'classpath', l: 'Rift\'s class path' }, { v: 'jar', l: 'Its own library' }]),
				ep.rti === 'jar' || fedpro ? 'Loaded in isolation: one gateway can join federations of two different RTIs or RTI versions.'
					: 'The RTI that Rift itself runs with (RTI_JAR in the start script).', { wide: true }),
			ep.rti !== 'jar' && !fedpro ? ca.field('Local settings designator', ca.input(ep, 'lsd', { placeholder: 'e.g. crcAddress=localhost:8989 (empty: the RTI\'s default)' }),
				'Passed to the RTI\'s connect(). Pitch pRTI: crcAddress=host:port.', { opt: true }) : null,
			ep.rti === 'jar' || fedpro ? ca.field('Library jar(s)', ca.input(ep, 'rtijar', { placeholder: fedpro ? 'lib/rti/fedpro/fedpro-client.jar' : 'lib/rti/prti6/prti1516e.jar',
				validate: function (v) { return v.trim() ? null : 'Give the jar'; } }),
				'Relative to Rift\'s folder; several separated by ;. The jar\'s manifest Class-Path is followed.', { req: true }) : null,
			ep.rti === 'jar' || fedpro ? ca.field(fedpro ? 'Federate Protocol server' : 'RTI address', ca.input(ep, 'rtiaddress', {
				placeholder: fedpro ? 'host:15164' : 'empty: from the settings below',
				validate: function (v) { return fedpro && !v.trim() ? 'The server address is needed' : null; } }),
				fedpro ? 'host:port of the FedPro server (the RtiConfiguration rtiAddress).' : 'For HLA 4 libraries: the RtiConfiguration rtiAddress.', { req: fedpro }) : null,
			ep.rti === 'jar' || fedpro ? ca.field('Connect settings', ca.area(ep, 'rtisettings', { rows: 2, placeholder: fedpro ? 'vendor settings, one per line' : 'crcAddress=localhost:8989' }),
				'Replace the LSD for this library (one per line).', { opt: true }) : null
		]));
		card.appendChild(ca.more('More: federate name and type, create / destroy, reconnect', [ca.grid([
			ca.field('Federate name', ca.input(ep, 'fedname', { placeholder: 'empty: the RTI chooses' }), null, { opt: true }),
			ca.field('Federate type', ca.input(ep, 'fedtype', { placeholder: 'SimwJunction' }), null, { opt: true }),
			ca.field('Federation', el('div.ca-checks', null, [
				ca.check(ep, 'createfed', 'Create it when missing'),
				ca.check(ep, 'destroyfed', 'Try to destroy it when the gateway stops')
			])),
			ca.field('Reconnect', ca.check(ep, 'reconnect', 'Rejoin when the RTI connection is lost')),
			ep.reconnect ? ca.field('Retry every', el('div.ca-inline', null, [ca.input(ep, 'reconnectSecs', { numeric: true }), el('span.ca-unit', { text: 's' })])) : null,
			ep.rti === 'jar' || fedpro ? ca.field('RtiFactory', ca.input(ep, 'rtifactory', { placeholder: 'empty: the default' }), 'Only when the library has several.', { opt: true }) : null,
			ca.field('Reference datatypes', ca.input(ep, 'reftypes', { placeholder: 'RTIobjectId' }), 'Datatypes that hold object instance names, rewritten when objects are renamed.', { opt: true }),
			talk ? ca.field('Talk on HLA', ca.select(ep, 'talkhla', [{ v: 'ieee1516-2010', l: 'HLA Evolved (default)' }, { v: 'ieee1516-2025', l: 'HLA 4' }]),
				'Which HLA API the Talk federation uses.') : null
		])]));
	}

	function disBlock(card, ep) {
		card.appendChild(ca.grid([
			ca.field('Receive on', ca.seg(ep, 'disIf', [{ v: 'all', l: 'All interfaces' }, { v: 'address', l: 'One interface' }, { v: 'multicast', l: 'Multicast group' }]),
				'Where the PDUs come in. Sending goes to the group, else the broadcast address of the interface (or the send address below).', { wide: true }),
			ep.disIf === 'address' ? ca.field('Interface', ca.input(ep, 'disAddr', { placeholder: '192.168.1.20 or eth0',
				validate: function (v) { return v.trim() ? null : 'Give an address or interface name'; } }), null, { req: true }) : null,
			ep.disIf === 'multicast' ? ca.field('Group', ca.input(ep, 'disGroup', { placeholder: '239.1.2.3' }), null, { req: true }) : null,
			ep.disIf === 'multicast' ? ca.field('On interface', ca.input(ep, 'disNic', { placeholder: 'eth0 (empty: the default)' }), null, { opt: true }) : null,
			ca.field('UDP port', ca.input(ep, 'disPort', { numeric: true, vars: true }), 'DIS uses 3000 by default.'),
			ca.field('Exercise', ca.input(ep, 'disExid', { vars: true, validate: function (v) { return ca.hasVar(v) || ca.isInt(v, 0, 255) ? null : '0 - 255'; } }),
				'0 receives every exercise (and sends as exercise 1).')
		]));
		card.appendChild(ca.more('More: sending, DIS ids, timing', [ca.grid([
			ca.field('Send address', ca.input(ep, 'sendaddr', { placeholder: 'e.g. 192.168.1.255 or host:port' }), 'Unicast, broadcast or multicast. Empty: see above.', { opt: true }),
			ep.disIf === 'multicast' ? ca.field('Multicast TTL', ca.input(ep, 'ttl', { numeric: true })) : null,
			ca.field('Gateway site id', ca.input(ep, 'siteid', { numeric: true })),
			ca.field('Gateway application id', ca.input(ep, 'appid', { numeric: true }), 'Entities from HLA get siteid-appid-n when their own id is taken.'),
			ca.field('Entity ids', ca.select(ep, 'entityids', [
				{ v: 'keep', l: 'keep - HLA EntityIdentifier when free' }, { v: 'assign', l: 'assign - always siteid-appid-n' }], { structural: false })),
			ca.field('Heartbeat', el('div.ca-inline', null, [ca.input(ep, 'heartbeat', { numeric: true }), el('span.ca-unit', { text: 's' })]), 'Entity State, Transmitter and Receiver PDUs are repeated this often.'),
			ca.field('Entity timeout', el('div.ca-inline', null, [ca.input(ep, 'timeout', { numeric: true }), el('span.ca-unit', { text: 's' })]), 'A DIS entity not heard from this long is removed.')
		])]));
		if (ep.standard === 'cdis') { cdisBlock(card, ep); }
	}

	function secs(ep, key) {
		return el('div.ca-inline', null, [ca.input(ep, key, { numeric: true }), el('span.ca-unit', { text: 's' })]);
	}

	/** C-DIS (SISO-STD-023-2024): update mode, full update multiplier, heartbeats and timeouts. */
	function cdisBlock(card, ep) {
		card.appendChild(ca.grid([
			ca.field('C-DIS updates', ca.seg(ep, 'cdisMode', [{ v: 'full', l: 'Full (every PDU complete)' }, { v: 'partial', l: 'Partial (only what changed)' }]),
				ca.rich('**Partial**: Entity State, EE, Designator, Transmitter and IFF PDUs carry only what changed, with a full update at least every multiplier x heartbeat. A federation agreement; decoding takes both.'), { wide: true }),
			ep.cdisMode === 'partial' ? ca.field('Full update multiplier', ca.input(ep, 'cdisMult', { numeric: true }), 'HBT_CDIS_FULL_UPDATE_MPLIER, 2.4 by default.') : null
		]));
		card.appendChild(ca.more('More: C-DIS heartbeats and timeouts', [ca.grid([
			ca.field('Timeout multiplier', ca.input(ep, 'cdisTimeout', { numeric: true }), 'The decoder forgets an object after this x its heartbeat without updates.'),
			ca.field('Entity State heartbeat', secs(ep, 'hbEs')),
			ca.field('EE heartbeat', secs(ep, 'hbEe')),
			ca.field('Designator heartbeat', secs(ep, 'hbDes')),
			ca.field('Transmitter heartbeat', secs(ep, 'hbTx')),
			ca.field('IFF heartbeat', secs(ep, 'hbIff'))
		])]));
		if (String(ep.disPort).trim() === '3000') {
			card.appendChild(ca.note('The C-DIS samples use UDP port **3100**, so a DIS network on 3000 and a C-DIS one can run side by side.'));
		}
	}

	function filterBlock(ep) {
		var f = ep.filters;
		return el('div', null, [
			ca.note('Per endpoint, on top of the router\'s filter. **In**: what this endpoint contributes; **out**: what it receives. Class patterns as in the router, `;` between.'),
			ca.grid([
				ca.field('In: objects allow', ca.input(f, 'inoa', { placeholder: 'all' })),
				ca.field('In: objects block', ca.input(f, 'inob', { placeholder: 'none' })),
				ca.field('In: interactions allow', ca.input(f, 'inia', { placeholder: 'all' })),
				ca.field('In: interactions block', ca.input(f, 'inib', { placeholder: 'none' })),
				ca.field('Out: objects allow', ca.input(f, 'outoa', { placeholder: 'all' })),
				ca.field('Out: objects block', ca.input(f, 'outob', { placeholder: 'none' })),
				ca.field('Out: interactions allow', ca.input(f, 'outia', { placeholder: 'all' })),
				ca.field('Out: interactions block', ca.input(f, 'outib', { placeholder: 'none' }))
			])
		]);
	}

	/* ---------- the router ---------- */

	var PRESETS = {
		all: { oa: '*', ob: '', ia: '*', ib: '' },
		entities: { oa: 'BaseEntity.*', ob: 'BaseEntity.AggregateEntity.*', ia: 'WeaponFire;MunitionDetonation;Fire.*;Detonation.*', ib: '*' },
		entitiesonly: { oa: 'BaseEntity.*', ob: '', ia: '', ib: '*' },
		noradio: { oa: '*', ob: 'EmbeddedSystem.*', ia: '*', ib: 'RadioSignal.*' }
	};

	function routerCard(g, server) {
		var rt = g.rt;
		var kind = routerKind(rt, server);
		var card = el('div.ca-card.router', { id: 'ca-box-rt' });
		var talkEps = g.eps.filter(function (e) { return famOf(e, server) === 'talk'; });
		card.appendChild(el('div.ca-card-head', null, [
			el('span.ca-boxrole', { text: 'Router' }),
			el('span.ca-badge', { text: kind === 'talk' ? 'TALK' : kind === 'pdu' ? 'PDU' : 'IEM' }),
			el('span.ca-card-title', { text: kind === 'talk' ? 'Pitch Talk router' : kind === 'pdu' ? 'PDU relay' : 'IEM router' }),
			el('span.ca-card-sub', { text: kind === 'talk' ? 'Talk channels and messages' : kind === 'pdu' ? 'DIS PDUs as they are' : 'objects and interactions by class' })
		]));
		card.appendChild(ca.goesInto(routerTargets(g, server)));
		card.appendChild(ca.grid([
			ca.field('Kind', ca.seg(rt, 'kind', [
				{ v: 'iem', l: 'HLA / DIS objects & interactions' }, { v: 'talk', l: 'Pitch Talk' }, { v: 'pdu', l: 'DIS PDU relay' }
			], { onChange: function (v) {
				var ks = routerKits(server).filter(function (k) { return k.kind === v; });
				if (rt.src === 'kit' && ks.length) { rt.kit = ks[0].source; }
			} }), kind === 'talk' ? 'Bridges Talk federations channel by channel, optionally with radio on a DIS or RPR endpoint.'
				: kind === 'pdu' ? 'Passes DIS PDUs between DIS 6, DIS 7 and C-DIS endpoints as they are, not through the RPR model (DIS 7 ⇄ C-DIS: all 22 C-DIS PDU types).'
				: 'Copies objects and interactions between the endpoints, with class filters, renaming and RPR v2/v3 conversion.', { wide: true }),
			ca.field('Where its settings live', ca.seg(rt, 'src', [{ v: 'kit', l: 'Use a kit (shared file)' }, { v: 'new', l: 'Write them in this gateway file' }]),
				ca.rich((rt.src === 'kit' ? 'The **kit** decides what passes; this gateway file only names it.' : 'The router\'s settings go into **this gateway\'s file**.')
					+ (kind === 'talk' ? ' Channel renames and the radio bridge (below) always go in the gateway file, because they name this gateway\'s endpoints.' : '')), { wide: true })
		]));
		if (kind === 'talk' && talkEps.length < 2) {
			card.appendChild(ca.note('The Pitch Talk router bridges **Talk** endpoints: this gateway needs at least two (a DIS or RPR endpoint can carry the radio).', 'warn'));
		}
		if (kind === 'pdu' && g.eps.some(function (e) { return famOf(e, server) !== 'dis'; })) {
			card.appendChild(ca.note('The PDU relay takes **DIS 6, DIS 7 and C-DIS** endpoints only.', 'warn'));
		}
		if (kind !== 'talk' && talkEps.length) {
			card.appendChild(ca.note('This gateway has Talk endpoints: choose the **Pitch Talk** router for them.', 'warn'));
		}

		if (rt.src === 'kit') {
			var ks = routerKits(server).filter(function (k) { return k.kind === rt.kind; });
			var k = rkitOf(rt, server);
			if (!k || k.kind !== rt.kind) { if (ks.length) { rt.kit = ks[0].source; k = ks[0]; } }
			card.appendChild(ca.grid([
				ca.field('Kit', ca.select(rt, 'kit', ks.map(function (x) { return { v: x.source, l: x.name, g: x.where }; })),
					k ? null : 'No kit of this kind.', { req: true, wide: true })
			]));
			if (k) { card.appendChild(kitBox(k, 'router', server, g)); }
			card.appendChild(ca.more('More: change kit settings for this gateway only', [ca.grid([
				ca.field('Other settings', ca.area(rt, 'extra', { rows: 3, placeholder: kind === 'talk' ? 'talk.match=description' : 'router.objects.block=EmbeddedSystem.*\nrouter.names.policy=prefix:BLUE-' }),
					ca.rich('One `key=value` per line with `router.` or `talk.` keys. They override the kit for this gateway.'), { wide: true })
			])]));
		} else if (kind === 'talk') {
			talkCore(card, rt);
		} else if (kind === 'pdu') {
			card.appendChild(ca.grid([
				ca.field('PDU types', ca.input(rt, 'pduTypes', { placeholder: 'all, or e.g. 1;2;3;25;26;27' }),
					'PDU type numbers passed, ; between. Empty: every type.', { wide: true })
			]));
		} else {
			iemCore(card, rt);
		}
		if (rt.src === 'new') {
			card.appendChild(ca.sub('Reuse'));
			card.appendChild(ca.grid([
				ca.field('Kit', ca.check(rt, 'saveKit', '**Also save as a router kit**: a shared file that other gateways can pick'), null, { wide: !rt.saveKit }),
				rt.saveKit ? ca.field('Kit file name', ca.input(rt, 'kitName', { placeholder: rkitFileName(rt, g).replace(/\.router\.settings$/, '') }),
					ca.rich('`etc/junctions/routers/' + rkitFileName(rt, g) + '`')) : null
			]));
			if (rt.saveKit) {
				card.appendChild(ca.note('The router settings above go into the **kit file** `routers/' + rkitFileName(rt, g) + '`; this gateway file only names it. Other gateways can pick it under **Use a kit**.'));
			}
		}
		if (kind === 'talk') { talkGateway(card, g, server); }
		return card;
	}

	function iemCore(card, rt) {
		card.appendChild(ca.grid([
			ca.field('What passes', ca.select(rt, 'preset', [
				{ v: 'all', l: 'Everything both sides have' },
				{ v: 'entities', l: 'Entities, weapon fire and detonations' },
				{ v: 'entitiesonly', l: 'Entities only, no interactions' },
				{ v: 'noradio', l: 'Everything except radios and radio signals' },
				{ v: 'custom', l: 'My own class patterns' }
			], { onChange: function (v) { var p = PRESETS[v]; if (p) { rt.oa = p.oa; rt.ob = p.ob; rt.ia = p.ia; rt.ib = p.ib; } } }),
				'A starting point for the class patterns below.', { wide: true }),
			ca.field('Objects: allow', ca.input(rt, 'oa', { placeholder: '* (all)', onInput: function () { rt.preset = 'custom'; } })),
			ca.field('Objects: block', ca.input(rt, 'ob', { placeholder: 'none', onInput: function () { rt.preset = 'custom'; } })),
			ca.field('Interactions: allow', ca.input(rt, 'ia', { placeholder: '* (all)', onInput: function () { rt.preset = 'custom'; } })),
			ca.field('Interactions: block', ca.input(rt, 'ib', { placeholder: 'none', onInput: function () { rt.preset = 'custom'; } }))
		]));
		card.appendChild(ca.note('Patterns without the root, `;` between: `BaseEntity.PhysicalEntity.Platform` is that class, `Platform.*` adds its subclasses, `**.Aircraft` matches at any depth, `*` is everything. The most specific rule wins; on a tie, block wins.'));
		card.appendChild(ca.grid([
			ca.field('Names of the copies', ca.select(rt, 'names', [
				{ v: 'same-or-suffix', l: 'Same name, else name@endpoint (default)' },
				{ v: 'prefix', l: 'A prefix: <text>name' },
				{ v: 'same-only', l: 'Same name only (skip when taken)' },
				{ v: 'rti', l: 'Let the RTI name them' }
			]), 'How an object copied into another endpoint is named there.'),
			rt.names === 'prefix' ? ca.field('Prefix', ca.input(rt, 'prefix', { placeholder: 'e.g. BLUE-' })) : null,
			ca.field('Options', el('div.ca-checks', null, [
				ca.check(rt, 'promote', 'Send a class the other side lacks as its nearest superclass'),
				ca.check(rt, 'del', 'Delete the copies when the gateway stops')
			]), null, { wide: true })
		]));
		card.appendChild(ca.more('More: queue, RPR conversion table', [ca.grid([
			ca.field('Queue capacity', ca.input(rt, 'queue', { numeric: true }), 'Events waiting for the router. Updates are dropped (and counted) when full.'),
			ca.field('RPR v2 ⇄ v3 table', ca.input(rt, 'mapping', { placeholder: 'mappings/rpr2-rpr3.mapping' }), 'Used between RPR v2 and v3 endpoints.', { opt: true })
		])]));
	}

	function talkCore(card, rt) {
		card.appendChild(ca.grid([
			ca.field('Mode', ca.seg(rt, 'mode', [
				{ v: 'full', l: 'Full: channels, clients' }, { v: 'messages', l: 'Messages only' }
			]), rt.mode === 'messages'
				? 'Only the Talk messages pass. Nothing is mirrored or created; channel objects are only watched (read-only) to match names.'
				: 'Messages pass with channel ids rewritten by name; clients, users and equipment are mirrored.', { wide: true }),
			ca.field('Messages', el('div.ca-checks', null, ['audio', 'radio', 'text', 'image'].map(function (m) {
				return ca.check(rt.msgs, m, m.charAt(0).toUpperCase() + m.slice(1), { structural: false });
			})), 'The PitchTalk.Message kinds bridged.'),
			ca.field('Match channels by', ca.select(rt, 'match', [{ v: 'name', l: 'Name' }, { v: 'description', l: 'Description' }], { structural: false })),
			ca.field('Channels bridged', ca.input(rt, 'channels', { placeholder: '*', vars: true }), 'Channel names, * and ? as wildcards, ; between.'),
			rt.mode === 'full' ? ca.field('Options', el('div.ca-checks', null, [
				ca.check(rt, 'mirror', 'Mirror the clients, users and equipment'),
				ca.check(rt, 'create', 'Create a channel the other side lacks')
			]), null, { wide: true }) : ca.field('Channel objects', ca.check(rt, 'track', 'Watch them (read-only) to match channels by name'),
				'Off: channel UUIDs pass unchanged, or as mapped by UUID below.')
		]));
	}

	function talkGateway(card, g, server) {
		var rt = g.rt;
		var talkIds = g.eps.filter(function (e) { return famOf(e, server) === 'talk'; }).map(function (e) { return e.id; });
		var radioIds = g.eps.filter(function (e) { return famOf(e, server) !== 'talk'; }).map(function (e) { return e.id; });
		var scopes = function () {
			return [{ v: '', l: 'Both ways' }].concat(talkIds.map(function (id) { return { v: id, l: 'Into ' + id }; }));
		};
		var messages = routerMode(rt, server) === 'messages';
		var track = rt.src === 'kit' || rt.track;
		card.appendChild(ca.sub('Channel names'));
		card.appendChild(ca.note('Channels are matched by name. Here a channel called **X** on one side can be called **Y** on the other. "Into site2" applies when sending into that endpoint (and back out of it); "Both ways" applies everywhere.'));
		card.appendChild(ca.rows(rt.cmap, [
			{ key: 'scope', options: scopes, label: 'Where', width: '150px' },
			{ key: 'from', placeholder: 'channel name there', vars: true, label: 'Channel', validate: chName },
			{ key: 'to', placeholder: 'is called here', vars: true, label: 'Is called', validate: chName }
		], { head: true, arrow: 1, addLabel: 'Rename a channel', make: function () { return { scope: talkIds[0] || '', from: '', to: '' }; } }));
		if (messages) {
			card.appendChild(ca.more('Channel UUIDs' + (track ? ' (checked first)' : ''), [
				ca.note(track ? 'Explicit UUID pairs win over the name match. 32 hex digits or 8-4-4-4-12.'
					: 'Without watching the channel objects, UUIDs pass unchanged unless paired here. 32 hex digits or 8-4-4-4-12.'),
				ca.rows(rt.umap, [
					{ key: 'scope', options: scopes, label: 'Where', width: '150px' },
					{ key: 'from', placeholder: 'UUID there', label: 'UUID', validate: uuidCheck },
					{ key: 'to', placeholder: 'UUID here', label: 'Is', validate: uuidCheck }
				], { head: true, arrow: 1, addLabel: 'Pair two UUIDs', make: function () { return { scope: '', from: '', to: '' }; } })
			], !track));
		}
		card.appendChild(ca.sub('Radio bridge'));
		card.appendChild(ca.grid([
			ca.field('Radio', ca.check(rt, 'radio', 'Put Talk channels on **RPR / DIS radio** frequencies'),
				'Each Talk sender becomes a radio transmitter on the radio endpoint; radio audio on the frequency comes back into the channel.', { wide: !rt.radio }),
			rt.radio ? ca.field('Radio endpoint', ca.select(rt, 'radioEp', [{ v: '', l: radioIds.length ? 'choose' : 'add a DIS or RPR endpoint' }].concat(radioIds.map(function (id) { return { v: id, l: id }; }))),
				'A DIS or RPR endpoint of this gateway.', { req: true }) : null
		]));
		if (rt.radio) {
			card.appendChild(ca.rows(rt.rmap, [
				{ key: 'ch', placeholder: messages && !track ? 'channel UUID' : 'channel name (or UUID)', vars: true, label: 'Channel' },
				{ key: 'mhz', placeholder: 'MHz, e.g. 251.000', numeric: true, label: 'Frequency (MHz)', width: '170px',
					validate: function (v) { return mhzToHz(v) === null ? 'MHz, e.g. 251.000' : null; } }
			], { head: true, arrow: 0, addLabel: 'Add a channel', make: function () { return { ch: '', mhz: '' }; } }));
			card.appendChild(ca.more('More: radio host and audio', [ca.grid([
				ca.field('Radio host entity', ca.input(rt, 'entityid', { placeholder: '900-99-1',
					validate: function (v) { return /^\d{1,5}-\d{1,5}-\d{1,5}$/.test(v.trim()) ? null : 'site-application-entity'; } }), 'The DIS entity that hosts the gateway\'s radios.'),
				ca.field('Talk sample rate', el('div.ca-inline', null, [ca.input(rt, 'sr', { numeric: true }), el('span.ca-unit', { text: 'Hz' })])),
				ca.field('Talk PCM byte order', ca.select(rt, 'order', [{ v: 'little', l: 'Little endian (default)' }, { v: 'big', l: 'Big endian' }], { structural: false })),
				ca.field('Sent as RPR audio', ca.select(rt, 'enc', [
					{ v: 'pcm16be', l: '16-bit PCM, big endian (DIS 4)' }, { v: 'pcm16le', l: '16-bit PCM, little endian (100)' }, { v: 'mulaw', l: 'G.711 mu-law (1)' }], { structural: false })),
				ca.field('RPR sample rate', el('div.ca-inline', null, [ca.input(rt, 'rprsr', { numeric: true, placeholder: 'Talk\'s' }), el('span.ca-unit', { text: 'Hz' })]), 'E.g. 8000 for older radios.', { opt: true }),
				ca.field('Hang time', el('div.ca-inline', null, [ca.input(rt, 'hang', { numeric: true }), el('span.ca-unit', { text: 'ms' })]), 'The transmitter stays "transmitting" this long after the last audio.')
			])]));
		}
	}

	function chName(v) { return /[;>]/.test(v) ? 'No ; or > in channel names here' : null; }
	function uuidCheck(v) { return !v || /^[0-9a-fA-F]{32}$/.test(v.replace(/-/g, '')) ? null : '32 hex digits'; }

	/* ---------- the group ---------- */

	function groupPanel(st, server) {
		var gr = st.group;
		var groups = (server && server.groups) || [];
		var listId = 'ca-groups';
		var members = st.gws.filter(function (g) { return g.inGroup; });
		var body = [
			el('p.ca-lead', null, ca.rich('Gateways with the same **group** start and stop together. **Group variables** (`GROUPVAR1`, `GROUPVAR2`, …) can stand in any setting, e.g. the federation or a channel name, and are filled in when the group is started.')),
			el('datalist', { id: listId }, groups.map(function (x) { return el('option', { value: x.group }); })),
			members.length && gr.name.trim()
				? ca.goesInto(members.map(function (g) { return { role: 'gateway', path: 'etc/junctions/' + gwId(g) + '.junction.settings', note: 'the group name, variable defaults' }; }),
					'A group is not a file of its own: it goes into each member\'s')
				: ca.note('A group is not a file of its own: its name (and the defaults of its variables) go into the **gateway file** of each member.'),
			ca.grid([
				ca.field('Group name', ca.input(gr, 'name', { placeholder: 'e.g. Exercise', list: listId, structural: true }),
					groups.length ? 'Groups on the server: ' + groups.map(function (x) { return x.group; }).join(', ') + '.' : 'Leave empty for no group.'),
				ca.field('Members', st.gws.length ? el('div.ca-checks', null, st.gws.map(function (g) {
					return ca.check(g, 'inGroup', g.name || gwId(g));
				})) : el('span.ca-help', { text: 'No gateways yet.' }), members.length ? null : 'Tick the gateways that belong to the group.')
			])
		];
		body.push(ca.sub('Group variables'));
		body.push(ca.rows(gr.vars, [
			{ key: 'name', label: 'Variable', width: '130px', validate: function (v) { return /^GROUPVAR\d+$/.test(v) ? null : 'GROUPVAR and a number'; } },
			{ key: 'def', placeholder: 'default (empty: asked at the start)', label: 'Default' },
			{ key: 'note', placeholder: 'what it is, e.g. Exercise federation (shown at the start)', label: 'Meaning' }
		], { head: true, addLabel: 'Add a variable', make: function () {
			var n = 1;
			while (gr.vars.some(function (v) { return v.name === 'GROUPVAR' + n; })) { n += 1; }
			return { name: 'GROUPVAR' + n, def: '', note: '' };
		} }));
		body.push(ca.note('Use a variable with the **+ VAR** button next to a field, or type it, e.g. federation `GROUPVAR1`, or `GROUPVAR1-Talk`, or a channel `radio_GROUPVAR1`: the token is replaced wherever it stands, the text around it stays. The whole number counts: `GROUPVAR10` is variable 10, not `GROUPVAR1` and a 0. Gateways outside the group may use variables too: their Start dialog asks for them.'));
		return el('div', { id: 'ca-box-group' }, panel('J3', 'Group', [el('span', { text: gr.name ? gr.name + ' · ' + members.length + ' member' + (members.length === 1 ? '' : 's') : 'no group' })], body));
	}

	ca.variables = function () {
		var st = ca.state && ca.state.junction;
		return st ? st.group.vars.filter(function (v) { return /^GROUPVAR\d+$/.test(v.name); }) : [];
	};

	/* ============================================================
	   Settings
	   ============================================================ */

	/** The endpoint's own settings (keys without "endpoint."), in file order. */
	function endpointSettings(ep, withTarget) {
		var t = typeOf(ep.standard);
		var s = [];
		function put(k, v, c) { s.push({ k: k, v: v, c: c }); }
		if (ep.name.trim()) { put('name', ep.name.trim()); }
		put('standard', ep.standard);
		if (t.fam === 'dis') {
			var iface = ep.disIf === 'address' ? ep.disAddr.trim() : ep.disIf === 'multicast' ? ep.disGroup.trim() + (ep.disNic.trim() ? '@' + ep.disNic.trim() : '') : '';
			put('dis.interface', iface, 'receive on: empty = all interfaces, an address or name, or group@interface');
			put('dis.port', String(ep.disPort).trim() || '3000');
			if (withTarget) { put('dis.exid', String(ep.disExid).trim() || '1', 'the default exercise (0 = every exercise; PDUs are then sent as exercise 1)'); }
			if (ep.sendaddr.trim()) { put('dis.sendaddress', ep.sendaddr.trim(), 'send: unicast, broadcast or multicast address'); }
			if (ep.disIf === 'multicast' && String(ep.ttl).trim() && String(ep.ttl).trim() !== '8') { put('dis.ttl', String(ep.ttl).trim()); }
			put('dis.siteid', String(ep.siteid).trim() || '900', 'the gateway\'s own DIS ids');
			put('dis.appid', String(ep.appid).trim() || '1');
			put('dis.entityids', ep.entityids);
			if (String(ep.heartbeat).trim() && String(ep.heartbeat).trim() !== '5') { put('dis.heartbeatsecs', String(ep.heartbeat).trim()); }
			if (String(ep.timeout).trim() && String(ep.timeout).trim() !== '12') { put('dis.entitytimeoutsecs', String(ep.timeout).trim()); }
			if (ep.standard === 'cdis') {
				put('cdis.updatemode', ep.cdisMode, 'C-DIS: full or partial updates');
				if (ep.cdisMode === 'partial' && String(ep.cdisMult).trim() && String(ep.cdisMult).trim() !== '2.4') { put('cdis.fullupdatemultiplier', String(ep.cdisMult).trim()); }
				if (String(ep.cdisTimeout).trim() && String(ep.cdisTimeout).trim() !== '2.4') { put('cdis.timeoutmultiplier', String(ep.cdisTimeout).trim()); }
				[['entitystate', 'hbEs', '5'], ['emission', 'hbEe', '10'], ['designator', 'hbDes', '5'], ['transmitter', 'hbTx', '2'], ['iff', 'hbIff', '10']].forEach(function (h) {
					var v = String(ep[h[1]]).trim();
					if (v && v !== h[2]) { put('cdis.heartbeat.' + h[0], v); }
				});
			}
		} else {
			var foms = ca.splitList(ep.foms);
			if (foms.length) { put('hla.foms', foms.join(';'), 'FOM modules (files or folders) in etc/fom/' + ca.fomFolder(ep.standard === 'talk' ? ep.talkhla : ep.standard)); }
			if (ep.standard === 'talk' && ep.talkhla !== 'ieee1516-2010') { put('talk.hlastandard', ep.talkhla); }
			if (withTarget && ep.federation.trim()) { put('hla.federation', ep.federation.trim(), 'the default target'); }
			var ownLib = ep.rti === 'jar' || ep.standard === 'ieee1516-2025-fedpro';
			if (ownLib) {
				put('hla.rtijar', ep.rtijar.trim(), 'RTI library, loaded in isolation (relative to Rift\'s folder; ; between jars)');
				if (ep.rtifactory.trim()) { put('hla.rtifactory', ep.rtifactory.trim()); }
				if (ep.rtiaddress.trim()) { put('hla.rtiaddress', ep.rtiaddress.trim(), ep.standard === 'ieee1516-2025-fedpro' ? 'the Federate Protocol server' : null); }
				var rs = String(ep.rtisettings || '').split(/\r?\n/).map(function (x) { return x.trim(); }).filter(function (x) { return x; });
				if (rs.length) { put('hla.rtisettings', rs.join('\n'), 'connect settings for the library'); }
			} else if (ep.lsd.trim()) {
				put('hla.lsd', ep.lsd.trim(), 'local settings designator');
			}
			if (!ep.createfed) { put('hla.createfederation', 'false'); }
			if (ep.destroyfed) { put('hla.destroyfederation', 'true'); }
			if (ep.fedname.trim()) { put('hla.federatename', ep.fedname.trim()); }
			if (ep.fedtype.trim() && ep.fedtype.trim() !== 'SimwJunction') { put('hla.federatetype', ep.fedtype.trim()); }
			if (!ep.reconnect) { put('hla.reconnect', 'false'); }
			else if (String(ep.reconnectSecs).trim() && String(ep.reconnectSecs).trim() !== '5') { put('hla.reconnectintervalseconds', String(ep.reconnectSecs).trim()); }
			if (ep.reftypes.trim()) { put('hla.referencetypes', ep.reftypes.trim()); }
		}
		filterSettings(ep).forEach(function (x) { s.push(x); });
		return s;
	}

	function filterSettings(ep) {
		var f = ep.filters, s = [];
		[['in', 'objects.allow', 'inoa'], ['in', 'objects.block', 'inob'], ['in', 'interactions.allow', 'inia'], ['in', 'interactions.block', 'inib'],
			['out', 'objects.allow', 'outoa'], ['out', 'objects.block', 'outob'], ['out', 'interactions.allow', 'outia'], ['out', 'interactions.block', 'outib']]
			.forEach(function (x) {
				var v = String(f[x[2]] || '').trim();
				if (v) { s.push({ k: 'filter.' + x[0] + '.' + x[1], v: v, c: s.length ? null : 'class filters of this endpoint (in: what it contributes, out: what it receives)' }); }
			});
		return s;
	}

	/** Router settings that belong in a kit (router.* and talk.* keys). */
	function routerSettings(rt) {
		var s = [];
		function put(k, v, c) { s.push({ k: k, v: v, c: c }); }
		put('router.kind', rt.kind);
		if (rt.kind === 'pdu') {
			if (String(rt.pduTypes).trim()) { put('router.pdu.types', ca.splitList(rt.pduTypes).join(';'), 'PDU types passed (numbers; empty = all)'); }
			return s;
		}
		if (rt.kind === 'iem') {
			put('router.objects.allow', rt.oa.trim(), 'class patterns, ; between (X, X.*, *, **.Y)');
			if (rt.ob.trim()) { put('router.objects.block', rt.ob.trim()); }
			put('router.interactions.allow', rt.ia.trim());
			if (rt.ib.trim()) { put('router.interactions.block', rt.ib.trim()); }
			if (rt.names !== 'same-or-suffix') { put('router.names.policy', rt.names === 'prefix' ? 'prefix:' + rt.prefix : rt.names, 'names of the copies'); }
			if (!rt.promote) { put('router.promote', 'false'); }
			if (!rt.del) { put('router.deleteobjectsonstop', 'false'); }
			if (String(rt.queue).trim() && String(rt.queue).trim() !== '50000') { put('router.queue.capacity', String(rt.queue).trim()); }
			if (rt.mapping.trim()) { put('router.rpr.mapping', rt.mapping.trim()); }
		} else {
			put('talk.mode', rt.mode);
			var kinds = ['audio', 'radio', 'text', 'image'].filter(function (m) { return rt.msgs[m]; });
			put('talk.messages', kinds.join(';'), 'message kinds bridged');
			put('talk.match', rt.match);
			put('talk.channels', rt.channels.trim() || '*', 'channels bridged (* and ? as wildcards)');
			if (rt.mode === 'full') {
				put('talk.mirrorclients', rt.mirror ? 'true' : 'false');
				put('talk.createmissingchannels', rt.create ? 'true' : 'false');
			} else {
				put('talk.trackchannels', rt.track ? 'true' : 'false', 'watch the channel objects read-only to match channels by name');
			}
		}
		return s;
	}

	/** Talk settings that name this gateway's endpoints or channels (always in the gateway file). */
	function talkGatewaySettings(rt, issues, label, epIds) {
		var s = [];
		function put(k, v, c) { s.push({ k: k, v: v, c: c }); }
		var byScope = {};
		rt.cmap.forEach(function (r) {
			if (!String(r.from).trim() && !String(r.to).trim()) { return; }
			if (!String(r.from).trim() || !String(r.to).trim()) { issues.push({ level: 'err', text: label + ': a channel rename needs both names.' }); return; }
			if (chName(r.from) || chName(r.to)) { issues.push({ level: 'err', text: label + ': channel names here cannot contain `;` or `>`.' }); return; }
			if (r.scope && epIds.indexOf(r.scope) < 0) { issues.push({ level: 'err', text: label + ': a channel rename is for endpoint `' + r.scope + '`, which the gateway does not have.' }); return; }
			(byScope[r.scope] = byScope[r.scope] || []).push(String(r.from).trim() + '>' + String(r.to).trim());
		});
		Object.keys(byScope).forEach(function (sc, i) {
			put(sc ? 'talk.channelmap.' + sc : 'talk.channelmap', byScope[sc].join('; '),
				i === 0 ? 'channel X there is channel Y here (both ways; .<endpoint>: into that endpoint)' : null);
		});
		var ub = {};
		rt.umap.forEach(function (r) {
			if (!String(r.from).trim() && !String(r.to).trim()) { return; }
			if (uuidCheck(r.from) || uuidCheck(r.to) || !r.from || !r.to) { issues.push({ level: 'err', text: label + ': a UUID pair needs two UUIDs of 32 hex digits.' }); return; }
			(ub[r.scope] = ub[r.scope] || []).push(String(r.from).trim() + '>' + String(r.to).trim());
		});
		Object.keys(ub).forEach(function (sc, i) {
			put(sc ? 'talk.channeluuidmap.' + sc : 'talk.channeluuidmap', ub[sc].join('; '), i === 0 ? 'channel UUID pairs (checked first)' : null);
		});
		if (rt.radio) {
			put('talk.radio.endpoint', rt.radioEp, 'radio bridge: the DIS or RPR endpoint, and channel = frequency in Hz');
			if (!rt.radioEp) { issues.push({ level: 'err', text: label + ': choose the radio endpoint.' }); }
			var any = false;
			rt.rmap.forEach(function (r) {
				var ch = String(r.ch || '').trim();
				if (!ch && !String(r.mhz).trim()) { return; }
				var hz = mhzToHz(r.mhz);
				if (!ch || hz === null) { issues.push({ level: 'err', text: label + ': each radio channel needs a name and a frequency in MHz.' }); return; }
				any = true;
				put('talk.radio.map.' + ch, String(hz), null);
			});
			if (!any) { issues.push({ level: 'err', text: label + ': the radio bridge needs at least one channel and frequency.' }); }
			if (rt.entityid.trim() && rt.entityid.trim() !== '900-99-1') { put('talk.radio.entityid', rt.entityid.trim(), 'the DIS entity hosting the gateway\'s radios'); }
			if (String(rt.sr).trim() && String(rt.sr).trim() !== '16000') { put('talk.audio.samplerate', String(rt.sr).trim()); }
			if (rt.order !== 'little') { put('talk.audio.pcmbyteorder', rt.order); }
			if (rt.enc !== 'pcm16be') { put('talk.audio.rprencoding', rt.enc); }
			if (String(rt.rprsr).trim()) { put('talk.audio.rprsamplerate', String(rt.rprsr).trim()); }
			if (String(rt.hang).trim() && String(rt.hang).trim() !== '500') { put('talk.radio.hangtimems', String(rt.hang).trim()); }
		}
		return s;
	}

	/** Extra "key=value" lines -> gateway keys with the given prefix rule. */
	function extraLines(text, mapKey, issues, label) {
		var out = [];
		ca.parseExtra(text).forEach(function (x) {
			if (!x.key) { issues.push({ level: 'err', text: label + ': `' + x.line + '` is not key=value.' }); return; }
			out.push({ key: mapKey(x.key), value: x.value });
		});
		return out;
	}

	/* ============================================================
	   Files
	   ============================================================ */

	ca.junctionOutput = function (st, server) {
		var issues = [], files = [], steps = [];
		var kitFiles = {};          // path -> content (new kits; the same kit from two gateways must be equal)
		var ids = {};
		var serverIds = ((server && server.gateways) || []).map(function (g) { return g.id; });
		var defined = {};
		st.group.vars.forEach(function (v) { if (/^GROUPVAR\d+$/.test(v.name)) { defined[v.name] = v; } });
		var usedAnywhere = {};
		var missingKits = {};       // kit -> users, for kits the server does not have
		var kitUse = {};            // new kit -> the gateways that use it
		var members = st.gws.filter(function (g) { return g.inGroup; });
		if (members.length && !st.group.name.trim()) { issues.push({ level: 'err', text: 'Gateways are marked as group members, but the group has no name (Group panel).' }); }
		if (st.group.name.trim() && !members.length) { issues.push({ level: 'warn', text: 'The group **' + st.group.name.trim() + '** has no member gateways.' }); }
		var seenVar = {};
		st.group.vars.forEach(function (v) {
			if (seenVar[v.name]) { issues.push({ level: 'err', text: 'The group variable ' + v.name + ' is listed twice.' }); }
			seenVar[v.name] = true;
		});

		var kitMeta = {};           // new kit -> {role, from}
		st.gws.forEach(function (g, gi) {
			var id = gwId(g);
			var label = 'Gateway "' + (g.name || id) + '"';
			if (!g.name.trim()) { issues.push({ level: 'err', text: 'A gateway has no name.' }); }
			if (!/^[A-Za-z0-9_.-]+$/.test(id)) { issues.push({ level: 'err', text: label + ': the file name may only have letters, digits, . _ -' }); }
			if (ids[id]) { issues.push({ level: 'err', text: 'Two gateways would be saved as `' + id + '.junction.settings`.' }); }
			ids[id] = true;

			var refs = [], own = [];    // kits this file names; what it holds itself
			function ref(src, label) {
				refs.push({ src: src, label: label });
				(kitUse[src] = kitUse[src] || []).push(id);
			}
			var p = new ca.Props();
			p.comment('Junction gateway: ' + (g.name || id));
			p.comment('Made with the Config Assistant. Read again whenever the Junction page lists the gateways.');
			p.blank();
			p.kv('junction.name', g.name.trim());
			if (g.description.trim()) { p.kv('junction.description', g.description.trim()); }
			if (g.inGroup && st.group.name.trim()) { p.kv('junction.group', st.group.name.trim(), 'gateways of one group start and stop together'); }
			var epIds = g.eps.map(function (e) { return e.id; });
			p.kv('junction.endpoints', epIds.join(';'));

			if (g.eps.length < 2) { issues.push({ level: 'err', text: label + ': a gateway needs at least two endpoints.' }); }
			var seenEp = {};
			var targets = {};
			g.eps.forEach(function (ep, ei) {
				var elabel = label + ', endpoint `' + (ep.id || '?') + '`';
				if (!/^[A-Za-z0-9_-]+$/.test(ep.id)) { issues.push({ level: 'err', text: elabel + ': the id may only have letters, digits, - and _.' }); }
				if (seenEp[ep.id]) { issues.push({ level: 'err', text: label + ': two endpoints are called `' + ep.id + '`.' }); }
				seenEp[ep.id] = true;
				var pre = 'junction.endpoint.' + ep.id;
				var std = stdOf(ep, server);
				var fam = typeOf(std).fam;
				p.blank();
				if (ep.src === 'kit') {
					var k = kitOf(ep, server);
					p.comment('endpoint ' + ep.id + ': ' + (k ? k.name : 'kit') + ' (' + typeOf(std).l + ')');
					p.kv(pre, ep.kit);
					ref(ep.kit, k && k.isNew ? 'new, below' : k && k.onServer ? 'on the server' : 'delivered with Junction');
					if (!k) { issues.push({ level: 'err', text: elabel + ': choose a kit.' }); }
					else if (!k.onServer && server && server.junction) { (missingKits[k.source] = missingKits[k.source] || []).push(id + '/' + ep.id); }
					if (ep.name.trim()) { p.kv(pre + '.name', ep.name.trim()); }
					if (String(ep.target).trim()) {
						p.kv(pre + (fam === 'dis' ? '.dis.exid' : '.hla.federation'), String(ep.target).trim(), fam === 'dis' ? 'the exercise' : 'the federation');
						if (fam === 'dis' && !ca.hasVar(ep.target) && !ca.isInt(ep.target, 0, 255)) { issues.push({ level: 'err', text: elabel + ': the exercise must be 0 - 255.' }); }
					}
					extraLines(ep.extra, function (key) { return pre + '.' + key.replace(/^endpoint\./, ''); }, issues, elabel)
						.forEach(function (x) { p.raw(ca.escKey(x.key) + '=' + x.value); });
					filterSettings(ep).forEach(function (x) { p.kv(pre + '.' + x.k, x.v, x.c); });
				} else {
					checkNewEndpoint(ep, elabel, issues);
					if (ep.saveKit) {
						var kf = 'endpoints/' + kitFileName(ep);
						var kp = new ca.Props();
						kp.comment('Endpoint kit: ' + (ep.name.trim() || typeOf(ep.standard).l));
						kp.comment('Made with the Config Assistant. Gateways refer to it with junction.endpoint.<id>=' + kf);
						kp.blank();
						endpointSettings(ep, !ca.hasVar(ep.federation) && !ca.hasVar(ep.disExid)).forEach(function (x) { kp.kv('endpoint.' + x.k, x.v, x.c); });
						addKit(kitFiles, kf, kp.text(), issues);
						kitMeta[kf] = { role: 'epkit', from: { text: 'the **Endpoint** box `' + ep.id + '` of the gateway "' + (g.name || id) + '", saved as a kit',
							ref: { section: 'junction', gw: gi, box: 'ep-' + ei } } };
						p.comment('endpoint ' + ep.id + ': ' + (ep.name.trim() || typeOf(ep.standard).l) + ' (kit ' + kf + ')');
						p.kv(pre, kf);
						ref(kf, 'new, below');
						if (fam === 'dis') { p.kv(pre + '.dis.exid', String(ep.disExid).trim() || '1', 'the exercise'); }
						else if (ep.federation.trim()) { p.kv(pre + '.hla.federation', ep.federation.trim(), 'the federation'); }
					} else {
						own.push('endpoint `' + ep.id + '`');
						p.comment('endpoint ' + ep.id + ': ' + (ep.name.trim() || typeOf(ep.standard).l));
						endpointSettings(ep, true).forEach(function (x) { p.kv(pre + '.' + x.k, x.v, x.c); });
					}
				}
				var tg = targetOf(ep, server);
				// DIS and C-DIS are told apart: a DIS 7 and a C-DIS endpoint of the same exercise are the usual relay
				var tkey = fam + '|' + (fam === 'dis' ? (stdOf(ep, server) === 'cdis' ? 'cdis|' : 'dis|') + tg : tg.toLowerCase());
				if (tg && fam !== 'dis' && targets[tkey]) {
					issues.push({ level: 'warn', text: label + ': endpoints `' + targets[tkey] + '` and `' + ep.id + '` both join **' + tg + '**. On the same RTI that is refused (traffic would loop); fine on two different RTIs.' });
				}
				if (tg && fam === 'dis' && targets[tkey]) {
					issues.push({ level: 'warn', text: label + ': endpoints `' + targets[tkey] + '` and `' + ep.id + '` both use DIS exercise ' + tg + '. On the same network and port that is refused.' });
				}
				targets[tkey] = targets[tkey] || ep.id;
			});

			// router
			var rt = g.rt;
			var kind = routerKind(rt, server);
			p.blank();
			if (rt.src === 'kit') {
				var rk = rkitOf(rt, server);
				p.comment('router: ' + (rk ? rk.name : 'kit'));
				p.kv('junction.router', rt.kit);
				ref(rt.kit, rk && rk.isNew ? 'new, below' : rk && rk.onServer ? 'on the server' : 'delivered with Junction');
				if (!rk) { issues.push({ level: 'err', text: label + ': choose a router kit.' }); }
				else if (!rk.onServer && server && server.junction) { (missingKits[rk.source] = missingKits[rk.source] || []).push(id); }
				extraLines(rt.extra, function (key) {
					return /^(router|talk)\./.test(key) ? 'junction.' + key : 'junction.router.' + key;
				}, issues, label + ', router').forEach(function (x) { p.raw(ca.escKey(x.key) + '=' + x.value); });
			} else if (rt.saveKit) {
				var rf = 'routers/' + rkitFileName(rt, g);
				var rp = new ca.Props();
				rp.comment('Router kit' + (rt.kind === 'talk' ? ' (Pitch Talk)' : ''));
				rp.comment('Made with the Config Assistant. Gateways refer to it with junction.router=' + rf);
				rp.blank();
				routerSettings(rt).forEach(function (x) { rp.kv(x.k, x.v, x.c); });
				addKit(kitFiles, rf, rp.text(), issues);
				kitMeta[rf] = { role: 'rtkit', from: { text: 'the **Router** box of the gateway "' + (g.name || id) + '", saved as a kit',
					ref: { section: 'junction', gw: gi, box: 'rt' } } };
				p.comment('router (kit ' + rf + ')');
				p.kv('junction.router', rf);
				ref(rf, 'new, below');
			} else {
				own.push('the router');
				p.comment('router: written here (junction.router.<key> = router.<key>, junction.talk.<key> = talk.<key>)');
				routerSettings(rt).forEach(function (x) { p.kv('junction.' + x.k, x.v, x.c); });
			}
			if (kind === 'iem' && rt.src === 'new') {
				if (!rt.oa.trim() && !rt.ia.trim()) { issues.push({ level: 'warn', text: label + ': the router allows no classes at all.' }); }
			}
			if (kind === 'pdu') {
				g.eps.forEach(function (e) {
					if (famOf(e, server) !== 'dis') { issues.push({ level: 'err', text: label + ': `' + e.id + '` is not a DIS or C-DIS endpoint; the PDU relay takes those only.' }); }
				});
				if (rt.src === 'new' && ca.splitList(rt.pduTypes).some(function (x) { return !ca.isInt(x, 1, 255); })) {
					issues.push({ level: 'err', text: label + ': PDU types are numbers 1 - 255.' });
				}
			} else if (kind === 'talk') {
				var talkEps = g.eps.filter(function (e) { return famOf(e, server) === 'talk'; });
				if (talkEps.length < 2) { issues.push({ level: 'err', text: label + ': the Pitch Talk router needs at least two Talk endpoints.' }); }
				g.eps.forEach(function (e) {
					if (famOf(e, server) !== 'talk' && !(rt.radio && rt.radioEp === e.id)) {
						issues.push({ level: 'warn', text: label + ': `' + e.id + '` is not a Talk endpoint; with the Pitch Talk router it only works as the radio endpoint.' });
					}
				});
				if (rt.src === 'new' && !['audio', 'radio', 'text', 'image'].some(function (m) { return rt.msgs[m]; })) {
					issues.push({ level: 'err', text: label + ': choose at least one message kind.' });
				}
				var gs = talkGatewaySettings(rt, issues, label, epIds);
				if (gs.length) { p.blank(); gs.forEach(function (x) { p.kv('junction.' + x.k, x.v, x.c); }); }
				if (rt.radio && routerMode(rt, server) === 'messages' && rt.src === 'new' && !rt.track &&
					rt.rmap.some(function (r) { return r.ch && !/^[0-9a-fA-F-]{32,36}$/.test(String(r.ch).trim()); })) {
					issues.push({ level: 'err', text: label + ': without watching the channel objects, radio channels must be given by UUID.' });
				}
			} else {
				var talkHere = g.eps.filter(function (e) { return famOf(e, server) === 'talk'; });
				if (talkHere.length) { issues.push({ level: 'err', text: label + ': Talk endpoints need the Pitch Talk router.' }); }
				var v3 = g.eps.some(function (e) { return e.src === 'kit' ? /rpr3/.test(e.kit) : /rpr3|_v3\.0/.test(e.foms); });
				var v2 = g.eps.some(function (e) { return famOf(e, server) === 'dis' || (e.src === 'kit' ? !/rpr3/.test(e.kit) : /_v2\.0|RPR_FOM_v2/.test(e.foms)); });
				if (v2 && v3) { issues.push({ level: 'info', text: label + ': RPR v2 and v3 endpoints: the router converts with `mappings/rpr2-rpr3.mapping`. The page\'s **Compatibility** button shows what passes.' }); }
			}
			if (g.autostart) { p.blank(); p.kv('junction.autostart', 'true', 'started when the server starts'); }

			// group variables used here (in this file and the new kits it uses)
			var text = p.text();
			var kitText = Object.keys(kitFiles).filter(function (kp) { return text.indexOf(kp) >= 0; }).map(function (kp) { return kitFiles[kp]; }).join('\n');
			var used = ca.varsIn(text + '\n' + kitText);
			used.forEach(function (v) { usedAnywhere[v] = true; });
			var defs = used.filter(function (v) { return defined[v] && String(defined[v].def).trim(); });
			if (defs.length) {
				p.blank();
				p.comment('group variable defaults (a value given at the start wins)');
				defs.forEach(function (v) { p.kv('junction.groupvar.' + v, String(defined[v].def).trim()); });
			}
			// 0.9.16: the meaning becomes the variable's label in the start dialogs and on the page
			var descs = used.filter(function (v) { return defined[v] && String(defined[v].note || '').trim(); });
			if (descs.length) {
				p.blank();
				p.comment('what the group variables mean (shown instead of GROUPVARn where values are asked for)');
				descs.forEach(function (v) { p.kv('junction.groupvar.' + v + '.desc', String(defined[v].note).trim()); });
			}
			used.filter(function (v) { return !defined[v]; }).forEach(function (v) {
				issues.push({ level: 'info', text: label + ' uses ' + v + ', which the Group panel does not list. It is asked for at the start.' });
			});
			var serverHas = serverIds.indexOf(id) >= 0;
			var seenRef = {};
			var refText = refs.filter(function (r) { if (seenRef[r.src]) { return false; } seenRef[r.src] = true; return true; })
				.map(function (r) { return '`' + r.src + '` (' + r.label + ')'; });
			var about = (serverHas ? '**Replaces** the server\'s gateway `' + id + '`. ' : '')
				+ (g.inGroup && st.group.name ? 'Gateway of the group **' + st.group.name + '**. ' : 'Gateway file. ')
				+ (refText.length ? 'Uses the kits ' + refText.join(', ') + '. ' : 'Uses no kits. ')
				+ (own.length ? 'Written in this file: ' + own.join(', ') + '.' : 'It only names kits and sets their targets.');
			files.push({ path: 'etc/junctions/' + id + '.junction.settings', kind: serverHas ? 'replace' : 'new', about: about, content: p.text(),
				role: 'gateway', from: { text: 'the **Gateway** "' + (g.name || id) + '" (tab ' + (gi + 1) + '): its Gateway, Endpoint and Router boxes'
					+ (g.inGroup && st.group.name ? ', and the Group' : ''), ref: { section: 'junction', gw: gi, box: 'gw' } } });
		});

		Object.keys(missingKits).forEach(function (kp) {
			if (kitFiles[kp] !== undefined) { return; }
			var users = missingKits[kp];
			issues.push({ level: 'warn', text: 'The kit `' + kp + '` is not on the server (used by ' + users.slice(0, 4).join(', ')
				+ (users.length > 4 ? ' and ' + (users.length - 4) + ' more' : '') + '). Copy it from the kits delivered with Junction.' });
		});
		Object.keys(defined).forEach(function (v) {
			if (!usedAnywhere[v]) { issues.push({ level: 'info', text: v + ' is not used by these gateways (only by existing kits, if any).' }); }
		});

		var serverKitSources = ((server && server.endpointKits) || []).map(function (k) { return normSource(k.source, 'endpoints/'); })
			.concat(((server && server.routerKits) || []).map(function (k) { return normSource(k.source, 'routers/'); }));
		Object.keys(kitFiles).forEach(function (kp) {
			var delivered = EP_KITS.concat(RT_KITS).some(function (k) { return k.source === kp; });
			var replace = delivered || serverKitSources.indexOf(kp) >= 0;
			if (replace) { issues.push({ level: 'warn', text: 'The kit `' + kp + '` exists already: the new one replaces it, for every gateway that uses it.' }); }
			var usedBy = (kitUse[kp] || []).filter(function (u, i, a) { return a.indexOf(u) === i; });
			files.push({ path: 'etc/junctions/' + kp, kind: replace ? 'replace' : 'new',
				about: (kp.indexOf('endpoints/') === 0 ? '**Endpoint kit**: shared endpoint settings.' : '**Router kit**: shared router settings.')
					+ ' Used by ' + (usedBy.length ? 'the gateway' + (usedBy.length > 1 ? 's ' : ' ') + usedBy.map(function (u) { return '`' + u + '`'; }).join(', ') : 'no gateway yet')
					+ '; other gateways can pick it later.', content: kitFiles[kp],
				role: kitMeta[kp] ? kitMeta[kp].role : null, from: kitMeta[kp] ? kitMeta[kp].from : null });
		});

		steps.push('Put each file on the server at the path shown, relative to Rift\'s installation folder (`' + ((server && server.junctionDir) || 'etc/junctions') + '` is the gateway folder).');
		steps.push('Open **Junction gateways** and press **Reload**: no restart needed. **Compatibility** on a gateway card shows what will pass between its endpoints, without connecting.');
		if (st.gws.some(function (g) { return g.eps.some(function (e) { return e.src === 'new' && (e.rti === 'jar' || e.standard === 'ieee1516-2025-fedpro'); }) || g.eps.some(function (e) { return e.src === 'kit' && /prti6|fedpro/.test(e.kit); }); })) {
			steps.push('Copy the RTI libraries the endpoints name (e.g. `lib/rti/...`) to the server. A missing one shows as **RTI MISSING** on the page.');
		}
		var gname = st.group.name.trim();
		if (gname && members.length) {
			var vars = Object.keys(defined);
			var body = '{"vars":{' + vars.map(function (v) { return '"' + v + '":"' + (String(defined[v].def).trim() || '...') + '"'; }).join(',') + '}}';
			steps.push('Start the group with **Start group** on the Junction page; it asks for the variables. Or with the REST API: `curl -k -X POST -H "Content-Type: application/json" -d \'' + body
				+ '\' https://<server>:8843/api/junction/groups/' + encodeURIComponent(gname) + '/start`');
		} else {
			steps.push('Press **Start** on the gateway\'s card; the dialog proposes each endpoint\'s target and lists the federations or exercises it finds.');
		}
		return { issues: issues, files: files, steps: steps, legend: ['gateway', 'epkit', 'rtkit', 'existing'] };
	};

	function checkNewEndpoint(ep, label, issues) {
		var t = typeOf(ep.standard);
		if (t.fam === 'dis') {
			if (!ca.hasVar(ep.disExid) && !ca.isInt(ep.disExid, 0, 255)) { issues.push({ level: 'err', text: label + ': the exercise must be 0 - 255.' }); }
			if (!ca.hasVar(ep.disPort) && !ca.isInt(ep.disPort, 1, 65535)) { issues.push({ level: 'err', text: label + ': the UDP port is not a port number.' }); }
			if (ep.disIf === 'address' && !ep.disAddr.trim()) { issues.push({ level: 'err', text: label + ': give the interface to receive on.' }); }
			if (ep.standard === 'cdis') {
				[['cdisMult', 'full update multiplier'], ['cdisTimeout', 'timeout multiplier'], ['hbEs', 'Entity State heartbeat'], ['hbEe', 'EE heartbeat'],
					['hbDes', 'Designator heartbeat'], ['hbTx', 'Transmitter heartbeat'], ['hbIff', 'IFF heartbeat']].forEach(function (f) {
					var v = String(ep[f[0]]).trim();
					if (v && !(parseFloat(v) > 0 && /^[0-9]*\.?[0-9]+$/.test(v))) { issues.push({ level: 'err', text: label + ': the C-DIS ' + f[1] + ' must be a number above 0.' }); }
				});
			}
			return;
		}
		if (t.fam === 'hla' && !ca.splitList(ep.foms).length) { issues.push({ level: 'err', text: label + ': choose the FOM modules.' }); }
		if (!ep.federation.trim()) { issues.push({ level: 'info', text: label + ': no federation set; it must be given when the gateway starts.' }); }
		var ownLib = ep.rti === 'jar' || ep.standard === 'ieee1516-2025-fedpro';
		if (ownLib && !ep.rtijar.trim()) { issues.push({ level: 'err', text: label + ': give the RTI library jar.' }); }
		if (ep.standard === 'ieee1516-2025-fedpro' && !ep.rtiaddress.trim()) { issues.push({ level: 'err', text: label + ': give the Federate Protocol server (host:port).' }); }
		var foms = ca.splitList(ep.foms);
		var hla4 = ep.standard === 'ieee1516-2025' || ep.standard === 'ieee1516-2025-fedpro';
		if (hla4 && foms.some(function (f) { return /_1516-2010\.xml$/.test(f); })) { issues.push({ level: 'warn', text: label + ': an HLA Evolved FOM file for an HLA 4 endpoint; HLA 4 needs modules in the 1516-2025 format.' }); }
		if (ep.standard === 'ieee1516-2010' && foms.some(function (f) { return /^RPR-.*_v2\.0\.xml$/.test(f); })) { issues.push({ level: 'warn', text: label + ': the `RPR-*_v2.0.xml` modules delivered are in the HLA 4 format.' }); }
	}

	function addKit(kitFiles, path, content, issues) {
		if (kitFiles[path] !== undefined && kitFiles[path] !== content) {
			issues.push({ level: 'err', text: 'Two different kits would be saved as `' + path + '`. Give one another kit file name.' });
			return;
		}
		kitFiles[path] = content;
	}
})(window);
