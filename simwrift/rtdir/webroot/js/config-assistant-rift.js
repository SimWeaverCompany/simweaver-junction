/* ============================================================
   SimWeaver Config Assistant - the Rift part:
   datasource configurations (etc/datasources/*.datasource.settings),
   server settings (etc/server.root.settings) and the object list
   attributes (etc/listprofiles.properties).
   Keys as read by com.simweaver.rift.DatasourceConfig and Server.
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var ca = Rift.ca;

	/* ============================================================
	   FOM modules delivered with Rift (etc/fom/<folder>), shared with the Junction part
	   ============================================================ */

	var RPR2_2025 = ['RPR-Base_v2.0.xml', 'RPR-Foundation_v2.0.xml', 'RPR-Enumerations_v2.0.xml', 'RPR-Switches_v2.0.xml',
		'RPR-Physical_v2.0.xml', 'RPR-Warfare_v2.0.xml', 'RPR-Communication_v2.0.xml', 'RPR-Aggregate_v2.0.xml',
		'RPR-SE_v2.0.xml', 'RPR-Minefield_v2.0.xml', 'RPR-Logistics_v2.0.xml', 'RPR-DER_v2.0.xml', 'RPR-SIMAN_v2.0.xml',
		'RPR-UA_v2.0.xml'];
	ca.RPR2_2025_CORE = RPR2_2025.slice(0, 7).join(';');

	var CATALOG = {
		'ieee1516-2010': [
			{ v: 'RPR_FOM_v2.0_Link16_1516-2010.xml', l: 'RPR FOM v2.0 (one file, with Link 16)', g: 'RPR FOM' },
			{ v: 'rpr3', l: 'rpr3/ - RPR FOM v3.0 (all 15 modules)', g: 'RPR FOM', title: 'The folder etc/fom/ieee1516-2010/rpr3: every module in it' },
			{ v: 'netn4-hla3', l: 'netn4-hla3/ - NETN 4 (all modules)', g: 'NETN', title: 'The folder etc/fom/ieee1516-2010/netn4-hla3: every module in it' }
		],
		'ieee1516-2025': RPR2_2025.map(function (f) { return { v: f, l: f.replace('_v2.0.xml', ''), g: 'RPR FOM v2.0 modules (HLA 4 format)' }; })
	};

	/*
	 * Rift passes each FOM entry to the RTI as it is, so a Rift datasource lists files, not folders.
	 * Junction endpoints may name a folder (every module in it).
	 */
	var RPR3 = ['RPR-Base_v3.0.xml', 'RPR-Foundation_v3.0.xml', 'RPR-Enumerations_v3.0.xml', 'RPR-Switches_v3.0.xml',
		'RPR-Physical_v3.0.xml', 'RPR-Warfare_v3.0.xml', 'RPR-Communication_v3.0.xml', 'RPR-Aggregate_v3.0.xml',
		'RPR-Minefield_v3.0.xml', 'RPR-SE_v3.0.xml', 'RPR-Logistics_v3.0.xml', 'RPR-DER_v3.0.xml', 'RPR-SIMAN_v3.0.xml',
		'RPR-UA_v3.0.xml', 'RPR-IO_v3.0.xml'].map(function (f) { return 'rpr3/' + f; }).join(';');
	var NETN4 = ['NETN-BASE.xml', 'NETN-ENTITY.xml', 'NETN-ORG.xml', 'NETN-SMC.xml', 'NETN-LOG.xml', 'NETN-MRM.xml',
		'NETN-TMR.xml', 'NETN-COM.xml', 'NETN-ETR.xml', 'NETN-METOC.xml', 'NETN-CBRN.xml', 'NETN-AIS.xml', 'NETN-DIM.xml',
		'NETN-SE.xml'].map(function (f) { return 'netn4-hla3/' + f; }).join(';');
	ca.RIFT_RPR3 = RPR3;
	var RIFT_CATALOG = {
		'ieee1516-2010': CATALOG['ieee1516-2010'].map(function (c) {
			if (c.v === 'rpr3') { return { v: RPR3, l: 'RPR FOM v3.0 (the 15 rpr3/ modules)', g: c.g, title: 'rpr3/RPR-*_v3.0.xml' }; }
			if (c.v === 'netn4-hla3') { return { v: NETN4, l: 'NETN 4 (the 14 netn4-hla3/ modules)', g: c.g, title: 'netn4-hla3/NETN-*.xml' }; }
			return c;
		}),
		'ieee1516-2025': CATALOG['ieee1516-2025']
	};

	/** The FOM folder of a standard: etc/fom/ieee1516-2010 or etc/fom/ieee1516-2025. */
	ca.fomFolder = function (standard) {
		return standard === 'ieee1516-2025' || standard === 'ieee1516-2025-fedpro' ? 'ieee1516-2025' : 'ieee1516-2010';
	};

	/**
	 * FOM picker: chips for the delivered modules, plus a text field for anything else.
	 * @param {Object} obj  holds the ';'-list in obj[key]
	 * @param {string} folder ieee1516-2010 | ieee1516-2025
	 * @param {Object} [o] {vars, files: offer files only (Rift datasources), no folders}
	 */
	ca.fomPicker = function (obj, key, folder, o) {
		o = o || {};
		var list = (o.files ? RIFT_CATALOG : CATALOG)[folder] || [];
		var known = [];
		list.forEach(function (c) { ca.splitList(c.v).forEach(function (x) { known.push(x); }); });
		var others = { v: ca.splitList(obj[key]).filter(function (f) { return known.indexOf(f) < 0; }).join(';') };
		var otherInput = ca.input(others, 'v', {
			placeholder: 'other files or folders, ; between',
			vars: o.vars,
			label: 'Other FOM modules',
			onInput: function (v) {
				var keep = ca.splitList(obj[key]).filter(function (f) { return known.indexOf(f) >= 0; });
				obj[key] = keep.concat(ca.splitList(v)).join(';');
			}
		});
		return el('div', null, [
			ca.chips(obj, key, list),
			el('div', { style: 'margin-top:6px' }, otherInput)
		]);
	};

	/* ============================================================
	   State
	   ============================================================ */

	function newDatasource(over) {
		var d = {
			name: '', file: '', fileAuto: true,
			standard: 'ieee1516-2010',
			foms: 'RPR_FOM_v2.0_Link16_1516-2010.xml',
			lsdMode: 'crc', crcHost: 'localhost', crcPort: '8989', lsd: '',
			federation: '', fedType: '', reconnect: true, reconnectSecs: '5', fomRoot: '',
			disIf: 'all', disAddr: '', disGroup: '239.1.2.3', disNic: '',
			disPort: '3000', disExid: '1', disTimeout: '13', cdisTimeout: '2.4'
		};
		Object.keys(over || {}).forEach(function (k) { d[k] = over[k]; });
		return d;
	}

	ca.riftDefaults = function () {
		return {
			dsList: [newDatasource({ name: 'Blue federation', federation: 'Blue' })],
			dsSel: 0,
			server: { on: false, port: '8843', root: 'webroot', keystore: 'keystore.jks', pwd: '', logdir: './log',
				jxEnabled: true, jxDir: 'etc/junctions', jxList: true, cn: 'localhost', alias: 'simweaver', validity: '730',
				mapEnabled: true, mapFile: '', mapDir: 'maps/raster', mapCache: 'maps/cache', mapMaxZoom: '' },
			lp: { on: false, rows: [
				{ cls: 'BaseEntity', attrs: 'EntityIdentifier, EntityType, ForceIdentifier' },
				{ cls: 'EmbeddedSystem.RadioTransmitter', attrs: 'HostObjectIdentifier, Frequency, TransmitterOperationalStatus' }
			] }
		};
	};

	var SCENARIOS = [
		{ t: 'HLA Evolved federation', s: 'IEEE 1516-2010 with the RPR FOM v2.0, e.g. on Pitch pRTI.',
			d: { name: 'HLA Evolved RPR', standard: 'ieee1516-2010', foms: 'RPR_FOM_v2.0_Link16_1516-2010.xml', federation: 'Blue' } },
		{ t: 'HLA 4 federation', s: 'IEEE 1516-2025 with the RPR FOM v2.0 modules.',
			d: { name: 'HLA 4 RPR', standard: 'ieee1516-2025', foms: ca.RPR2_2025_CORE, lsdMode: 'empty', federation: 'Green' } },
		{ t: 'RPR FOM v3.0 federation', s: 'HLA Evolved with all RPR v3 modules (the rpr3 folder).',
			d: { name: 'RPR v3', standard: 'ieee1516-2010', foms: RPR3, federation: 'Rpr3' } },
		{ t: 'Talk federation', s: 'See the Talk clients, channels and messages, next to RPR traffic. The FOM comes from the running federation.',
			d: { name: 'Talk', standard: 'ieee1516-2010', foms: 'dynamic', federation: 'Talk' } },
		{ t: 'DIS 7 network', s: 'Listen to DIS 7 PDUs on UDP, shown as RPR FOM objects.',
			d: { name: 'DIS 7', standard: 'dis7' } },
		{ t: 'DIS 6 network', s: 'DIS 6 (and 5) PDUs, shown as RPR FOM objects.',
			d: { name: 'DIS 6', standard: 'dis6' } },
		{ t: 'C-DIS network', s: 'Compressed DIS (SISO-STD-023-2024), decoded and shown as RPR FOM objects.',
			d: { name: 'C-DIS', standard: 'cdis', disPort: '3100' } }
	];

	var STANDARDS = [
		{ v: 'ieee1516-2010', l: 'HLA Evolved (IEEE 1516-2010)' },
		{ v: 'ieee1516-2025', l: 'HLA 4 (IEEE 1516-2025)' },
		{ v: 'dis7', l: 'DIS 7 (IEEE 1278.1-2012)' },
		{ v: 'dis6', l: 'DIS 6 (IEEE 1278.1a-1998)' },
		{ v: 'cdis', l: 'C-DIS (SISO-STD-023-2024)' }
	];

	function isDis(std) { return std === 'dis6' || std === 'dis7' || std === 'cdis'; }

	function fileOf(d) {
		var f = d.fileAuto || !d.file ? ca.slug(d.name, 'datasource') : d.file;
		return f.replace(/\.datasource\.settings$/, '');
	}

	function lsdOf(d) {
		switch (d.lsdMode) {
			case 'crc': return 'crcAddress=' + (d.crcHost || 'localhost') + ':' + (d.crcPort || '8989');
			case 'default': return 'default';
			case 'custom': return d.lsd || '';
			default: return '';
		}
	}

	function disInterface(d) {
		if (d.disIf === 'address') { return d.disAddr || ''; }
		if (d.disIf === 'multicast') { return (d.disGroup || '') + (d.disNic ? '@' + d.disNic : ''); }
		return '0.0.0.0';
	}

	/* ============================================================
	   Form
	   ============================================================ */

	ca.renderRift = function (host, st, server) {
		ui.clear(host);
		host.appendChild(scenarioPanel(st));
		host.appendChild(datasourcePanel(st, server));
		host.appendChild(serverPanel(st));
		host.appendChild(listPanel(st));
	};

	function panel(idx, title, meta, body) {
		return el('section.panel', null, [
			el('div.panel-head', null, [el('span.idx', { text: idx }), el('h2.title', { text: title }),
				meta ? el('span.meta', null, meta) : null]),
			el('div.panel-body', null, body)
		]);
	}

	function scenarioPanel(st) {
		return panel('R1', 'What do you want to look at?', null, [
			el('p.ca-lead', null, ca.rich('A **datasource** joins one HLA federation (or listens to one DIS network) and shows what it sees. Pick what fits; you can change every setting below.')),
			el('div.ca-scen', null, SCENARIOS.map(function (sc) {
				return el('button', { type: 'button', on: { click: function () {
					var d = newDatasource(sc.d);
					var cur = st.dsList[st.dsSel];
					if (cur && JSON.stringify(cur) === JSON.stringify(newDatasource({ name: 'Blue federation', federation: 'Blue' }))) {
						st.dsList[st.dsSel] = d;
					} else {
						st.dsList.push(d);
						st.dsSel = st.dsList.length - 1;
					}
					ca.changed(true);
				} } }, [el('span.t', { text: sc.t }), el('span.s', { text: sc.s })]);
			}))
		]);
	}

	function datasourcePanel(st, server) {
		if (!st.dsList.length) { st.dsList.push(newDatasource()); }
		if (st.dsSel >= st.dsList.length) { st.dsSel = st.dsList.length - 1; }
		var d = st.dsList[st.dsSel];

		var tabs = el('div.ca-tabs', null, st.dsList.map(function (x, i) {
			return el('button.tab', { type: 'button', 'aria-pressed': i === st.dsSel ? 'true' : 'false', title: fileOf(x) + '.datasource.settings',
				on: { click: function () { st.dsSel = i; ca.changed(true); } } }, x.name || '(no name)');
		}).concat([
			el('span.ca-spacer'),
			el('button.btn', { type: 'button', on: { click: function () { st.dsList.push(newDatasource()); st.dsSel = st.dsList.length - 1; ca.changed(true); } } }, '+ New'),
			el('button.btn', { type: 'button', title: 'A copy of this datasource', on: { click: function () {
				var c = JSON.parse(JSON.stringify(d)); c.name = (c.name || 'Datasource') + ' copy'; c.fileAuto = true;
				st.dsList.push(c); st.dsSel = st.dsList.length - 1; ca.changed(true);
			} } }, 'Duplicate'),
			st.dsList.length > 1 ? el('button.btn.btn-danger', { type: 'button', on: { click: function () {
				st.dsList.splice(st.dsSel, 1); st.dsSel = Math.max(0, st.dsSel - 1); ca.changed(true);
			} } }, 'Remove') : null
		]));

		var body = [tabs,
			el('p.ca-help', { style: 'margin:-6px 0 10px 0' }, ca.rich('Each tab is **one datasource configuration** and becomes **one file**. Add more with the starting points above or **+ New**.')),
			ca.goesInto([{ role: 'ds', path: 'etc/datasources/' + fileOf(d) + '.datasource.settings' }])];
		var fileIn = ca.input(d, 'file', {
			placeholder: fileOf(d),
			validate: function (v) { return v && !/^[A-Za-z0-9_.-]+$/.test(v) ? 'Letters, digits, . _ - only' : null; },
			onInput: function (v) { d.fileAuto = !v; }
		});
		body.push(ca.grid([
			ca.field('Name', ca.input(d, 'name', { placeholder: 'e.g. Blue federation', validate: function (v) { return v.trim() ? null : 'A name is needed'; } }),
				'Shown on the card in the Web UI.', { req: true }),
			ca.field('File name', fileIn, ca.rich('Saved as `etc/datasources/' + fileOf(d) + '.datasource.settings`. Leave empty to derive it from the name.'), { opt: true }),
			ca.field('Standard', ca.select(d, 'standard', STANDARDS, { onChange: function (v) {
				if (v === 'ieee1516-2025' && /RPR_FOM_v2\.0_Link16/.test(d.foms)) { d.foms = ca.RPR2_2025_CORE; d.lsdMode = 'empty'; }
				if (v === 'ieee1516-2010' && /RPR-Base_v2\.0/.test(d.foms)) { d.foms = 'RPR_FOM_v2.0_Link16_1516-2010.xml'; }
			} }), standardHelp(d.standard), { req: true })
		]));

		if (isDis(d.standard)) {
			body.push(ca.sub('DIS network'));
			body.push(ca.note('Rift receives the PDUs on UDP and shows them as **RPR FOM v2.0** objects and interactions. It only listens: nothing is sent to the network.'));
			body.push(ca.grid([
				ca.field('Receive on', ca.seg(d, 'disIf', [
					{ v: 'all', l: 'All interfaces' }, { v: 'address', l: 'One interface' }, { v: 'multicast', l: 'Multicast group' }
				]), d.disIf === 'all' ? 'Broadcast and unicast PDUs on any interface of the server.'
					: d.disIf === 'address' ? 'An address of the server (e.g. 192.168.1.20) or an interface name (e.g. eth0).'
						: 'Joins the multicast group, optionally on one interface.', { wide: true }),
				d.disIf === 'address' ? ca.field('Interface', ca.input(d, 'disAddr', { placeholder: '192.168.1.20 or eth0',
					validate: function (v) { return v.trim() ? null : 'Give an address or interface name'; } }), null, { req: true }) : null,
				d.disIf === 'multicast' ? ca.field('Group', ca.input(d, 'disGroup', { placeholder: '239.1.2.3',
					validate: function (v) { return /^2(2[4-9]|3\d)\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(v.trim()) ? null : 'A multicast address, 224.0.0.0 - 239.255.255.255'; } }), null, { req: true }) : null,
				d.disIf === 'multicast' ? ca.field('On interface', ca.input(d, 'disNic', { placeholder: 'eth0 (empty: the default)' }), null, { opt: true }) : null,
				ca.field('UDP port', ca.input(d, 'disPort', { numeric: true, validate: portCheck }), 'DIS uses 3000 by default.'),
				ca.field('Exercise', ca.input(d, 'disExid', { numeric: true, validate: function (v) { return ca.isInt(v, 0, 255) ? null : '0 - 255'; } }),
					'The default exercise id, 0-255; 0 = every exercise. The Web UI asks again when the datasource is created.'),
				ca.field('Entity timeout', el('div.ca-inline', null, [ca.input(d, 'disTimeout', { numeric: true, validate: function (v) { return ca.isInt(v, 1) ? null : 'Seconds'; } }), el('span.ca-unit', { text: 's' })]),
					'An entity not heard from this long is removed. DIS heartbeats are 5 s.')
			]));
			if (d.standard === 'cdis') {
				body.push(ca.grid([
					ca.field('C-DIS timeouts', el('div.ca-inline', null, [ca.input(d, 'cdisTimeout', { numeric: true }), el('span.ca-unit', { text: 'x heartbeat' })]),
						'The decoder forgets an object after this many heartbeats without updates (2.4; heartbeats: the DIS 7 ones).')
				]));
				if (String(d.disPort).trim() === '3000') {
					body.push(ca.note('The C-DIS samples use UDP port **3100**.'));
				}
			}
			body.push(ca.note('The FOM setting stays: it is the RPR FOM v2.0 (`RPR_FOM_v2.0_Link16_1516-2010.xml`) that the PDUs are mapped to.'));
		} else {
			var folder = ca.fomFolder(d.standard);
			body.push(ca.sub('Federation and FOM'));
			body.push(ca.grid([
				ca.field('Federation', ca.input(d, 'federation', { placeholder: 'e.g. Blue' }),
					'Filled in when a user creates the datasource; they can pick another one the RTI knows.', { opt: true }),
				ca.field('FOM modules', ca.fomPicker(d, 'foms', folder, { files: true }),
					ca.rich('Files in `etc/fom/' + folder + '` (a sub folder as `rpr3/RPR-Base_v3.0.xml`), or full paths. The modules must match the federation\'s FOM.'), { req: true, wide: true })
			]));
			body.push(ca.sub('RTI connection'));
			var hla4 = d.standard === 'ieee1516-2025';
			body.push(ca.grid([
				ca.field(hla4 ? 'RTI configuration' : 'Local settings designator', ca.seg(d, 'lsdMode', hla4 ? [
					{ v: 'empty', l: 'RTI default' }, { v: 'custom', l: 'Configuration name' }
				] : [
					{ v: 'crc', l: 'CRC address (Pitch)' }, { v: 'empty', l: 'RTI default' }, { v: 'default', l: '"default"' }, { v: 'custom', l: 'Custom text' }
				]), hla4 ? 'HLA 4: the name of an RTI configuration, passed to connect(). Empty: the RTI\'s own default.'
					: 'Passed to the RTI in connect(). Pitch pRTI takes crcAddress=host:port; other RTIs have their own syntax.', { wide: true }),
				d.lsdMode === 'crc' && !hla4 ? ca.field('CRC host', ca.input(d, 'crcHost', { placeholder: 'localhost' })) : null,
				d.lsdMode === 'crc' && !hla4 ? ca.field('CRC port', ca.input(d, 'crcPort', { numeric: true, validate: portCheck }), 'pRTI\'s default is 8989.') : null,
				d.lsdMode === 'custom' ? ca.field(hla4 ? 'Configuration name' : 'Designator', ca.input(d, 'lsd', { placeholder: hla4 ? 'e.g. MyRtiConfig' : 'vendor text' })) : null
			]));
			body.push(ca.more('More: federate type, reconnect, FOM folder', [ca.grid([
				ca.field('Federate type', ca.input(d, 'fedType', { placeholder: 'SimwRift' }), 'How the RTI and other tools see Rift.', { opt: true }),
				ca.field('Reconnect', ca.check(d, 'reconnect', 'Rejoin when the connection to the RTI is lost')),
				d.reconnect ? ca.field('Retry every', el('div.ca-inline', null, [ca.input(d, 'reconnectSecs', { numeric: true, validate: function (v) { return ca.isInt(v, 1) ? null : 'Seconds'; } }), el('span.ca-unit', { text: 's' })])) : null,
				ca.field('FOM base folder', ca.input(d, 'fomRoot', { placeholder: './etc/fom' }),
					ca.rich('Only when your FOM files are elsewhere. Rift adds the standard\'s folder: `<base>/' + folder + '`.'), { opt: true })
			])]));
		}
		var sec = panel('R2', 'Datasource configurations', [el('span', { text: st.dsList.length + ' file' + (st.dsList.length > 1 ? 's' : '') })], body);
		sec.id = 'ca-box-ds';
		return sec;
	}

	function standardHelp(std) {
		switch (std) {
			case 'ieee1516-2025': return 'Needs an HLA 4 RTI library on Rift\'s class path (RTI_JAR).';
			case 'dis7': return 'No RTI needed. Receives DIS 6 and 7.';
			case 'dis6': return 'No RTI needed. Receives DIS 5 and 6.';
			case 'cdis': return 'No RTI needed. Receives C-DIS (full and partial updates) and decodes it to DIS 7.';
			default: return 'Needs an HLA Evolved RTI library on Rift\'s class path (RTI_JAR), e.g. prti1516e.jar.';
		}
	}

	function portCheck(v) { return ca.isInt(v, 1, 65535) ? null : 'A port, 1 - 65535'; }

	function serverPanel(st) {
		var s = st.server;
		// state saved by an older page: the map keys are new
		if (s.mapEnabled === undefined) { s.mapEnabled = true; s.mapDir = 'maps/raster'; s.mapCache = 'maps/cache'; s.mapMaxZoom = ''; }
		if (s.mapFile === undefined) { s.mapFile = ''; }
		if (s.jxList === undefined) { s.jxList = true; }
		var body = [
			el('p.ca-lead', null, ca.rich('Server-wide settings: the HTTPS port, the key store, the log folder, and whether Junction runs. Only needed when the defaults do not suit you.')),
			ca.check(s, 'on', '**Write server settings** (`etc/server.root.settings`)')
		];
		if (s.on) { body.push(ca.goesInto([{ role: 'server', path: 'etc/server.root.settings' }])); }
		if (s.on) {
			body.push(ca.grid([
				ca.field('HTTPS port', ca.input(s, 'port', { numeric: true, validate: portCheck }), 'The Web UI and the REST API share it. Default 8843.'),
				ca.field('Log folder', ca.input(s, 'logdir', { placeholder: 'logs' })),
				ca.field('Junction', ca.check(s, 'jxEnabled', 'Run Junction gateways in this server')),
				s.jxEnabled ? ca.field('Gateway folder', ca.input(s, 'jxDir', { placeholder: 'etc/junctions' }), 'Where the gateway configurations are.') : null,
				s.jxEnabled ? ca.field('Gateway endpoints in the list', ca.check(s, 'jxList', 'List each gateway endpoint as a datasource configuration'),
					'Off: the datasource list shows only the files in etc/datasources. Inspect on the Junction page still works.') : null
			]));
			body.push(ca.sub('Map'));
			body.push(ca.note('The datasource page can show its objects on a map. The background is a latitude/longitude raster, e.g. Natural Earth `HYP_HR_SR_W_DR.tif`, in a folder of its own under the map folder: `maps/raster/HYP_HR_SR_W_DR/HYP_HR_SR_W_DR.tif`. Tiles are made from it when first needed and kept in the cache folder.'));
			body.push(ca.grid([
				ca.field('Map', ca.check(s, 'mapEnabled', 'Show objects on a map (datasource page, map tab)'), 'Off: no map buttons, and the map API answers MAP_DISABLED.'),
				s.mapEnabled ? ca.field('Default map file', ca.input(s, 'mapFile', { placeholder: 'the first map in the map folder' }),
					'The background map users see first, e.g. maps/raster/HYP_HR_SR_W_DR/HYP_HR_SR_W_DR.tif, or a file anywhere on the server. Users can pick another map of the server in the map view.') : null,
				s.mapEnabled ? ca.field('Map folder', ca.input(s, 'mapDir', { placeholder: 'maps/raster' }), 'One sub folder per map, with a .tif (also .png, .jpg) in it.') : null,
				s.mapEnabled ? ca.field('Tile cache', ca.input(s, 'mapCache', { placeholder: 'maps/cache' }), 'Made tiles, on disk. Rebuilt when the raster file changes.') : null,
				s.mapEnabled ? ca.field('Deepest tile zoom', ca.input(s, 'mapMaxZoom', { numeric: true, placeholder: 'from the raster (Natural Earth 10 m: 6)',
					validate: function (v) { return v && !ca.isInt(v, 1, 14) ? '1 - 14' : null; } }), 'Deeper zooms scale these tiles up.', { opt: true }) : null
			]));
			body.push(ca.sub('Certificate (HTTPS)'));
			body.push(ca.note('The server only speaks HTTPS. It needs a Java key store with a certificate. The command in the result creates a self-signed one with Java\'s `keytool`; browsers warn about it once.'));
			body.push(ca.grid([
				ca.field('Key store file', ca.input(s, 'keystore', { placeholder: 'keystore.jks' })),
				ca.field('Key store password', ca.input(s, 'pwd', { placeholder: 'password',
					validate: function (v) { return v && v.length < 6 ? 'keytool needs at least 6 characters' : null; } }),
					'Empty: the built-in default "password". Choose your own for real use.'),
				ca.field('Host name in the certificate', ca.input(s, 'cn', { placeholder: 'localhost' }), 'The name browsers use to reach the server.'),
				ca.field('Valid for', el('div.ca-inline', null, [ca.input(s, 'validity', { numeric: true }), el('span.ca-unit', { text: 'days' })])),
				ca.field('Web folder', ca.input(s, 'root', { placeholder: 'webroot' }), 'Where the Web UI files are.', { opt: true })
			]));
		}
		var sec = panel('R3', 'Server settings', [el('span', { text: s.on ? 'included' : 'not included' })], body);
		sec.id = 'ca-box-server';
		return sec;
	}

	function listPanel(st) {
		var lp = st.lp;
		var body = [
			el('p.ca-lead', null, ca.rich('Which attributes the object list shows for a class (and its subclasses). Without this file Rift uses RPR defaults.')),
			ca.check(lp, 'on', '**Write object list attributes** (`etc/listprofiles.properties`)')
		];
		if (lp.on) { body.push(ca.goesInto([{ role: 'lp', path: 'etc/listprofiles.properties' }])); }
		if (lp.on) {
			body.push(ca.rows(lp.rows, [
				{ key: 'cls', placeholder: 'class, e.g. BaseEntity.PhysicalEntity.Platform.Aircraft', label: 'Class' },
				{ key: 'attrs', placeholder: 'attributes in order, comma separated', label: 'Attributes' }
			], { head: true, addLabel: 'Add class', make: function () { return { cls: '', attrs: '' }; } }));
			body.push(ca.note('The `HLAobjectRoot.` prefix may be left out. An empty attribute list removes the default for that class. Classes without a line show their first three attributes.'));
		}
		var sec = panel('R4', 'Object list attributes', [el('span', { text: lp.on ? 'included' : 'not included' })], body);
		sec.id = 'ca-box-lp';
		return sec;
	}

	/* ============================================================
	   Files
	   ============================================================ */

	ca.riftOutput = function (st, server) {
		var issues = [], files = [], steps = [];
		var existing = (server && server.dsConfigs) || [];
		var seenFiles = {}, seenNames = {};

		st.dsList.forEach(function (d, di) {
			var file = fileOf(d) + '.datasource.settings';
			var label = '"' + (d.name || file) + '"';
			if (!d.name.trim()) { issues.push({ level: 'err', text: 'A datasource has no name.' }); }
			if (seenFiles[file]) { issues.push({ level: 'err', text: 'Two datasources would be saved as `' + file + '`.' }); }
			seenFiles[file] = true;
			var lname = d.name.trim().toLowerCase();
			if (lname && seenNames[lname]) { issues.push({ level: 'warn', text: 'Two datasources are called ' + label + '.' }); }
			seenNames[lname] = true;
			var same = existing.filter(function (c) { return String(c.file || '').split(/[\\\/]/).pop() === file; })[0];
			var named = existing.filter(function (c) { return String(c.name || '').toLowerCase() === lname && String(c.file || '').split(/[\\\/]/).pop() !== file; })[0];
			if (named) { issues.push({ level: 'warn', text: 'The server already has a datasource called ' + label + ' (`' + named.file + '`).' }); }

			var p = new ca.Props();
			p.comment('SimWeaver Rift datasource configuration: ' + (d.name || file));
			p.comment('Made with the Config Assistant. Listed in the Web UI without a restart.');
			p.blank();
			p.kv('datasource.name', d.name.trim());
			p.kv('datasource.standard', d.standard);
			p.blank();
			if (isDis(d.standard)) {
				p.comment('the RPR FOM that the PDUs are mapped to (in etc/fom/ieee1516-2010)');
				p.kv('datasource.hla.foms', 'RPR_FOM_v2.0_Link16_1516-2010.xml');
				p.blank();
				p.comment('DIS: receive interface (0.0.0.0 = all; an address or name; a multicast group, e.g. 239.1.2.3@eth0)');
				p.kv('datasource.dis.interface', disInterface(d));
				p.kv('datasource.dis.port', String(d.disPort).trim());
				p.comment('default exercise, 0 = every exercise');
				p.kv('datasource.dis.exid', String(d.disExid).trim());
				p.kv('datasource.dis.entitytimeoutsecs', String(d.disTimeout).trim());
				if (d.standard === 'cdis') {
					var tm = String(d.cdisTimeout || '').trim();
					if (tm && tm !== '2.4') { p.kv('datasource.cdis.timeoutmultiplier', tm, 'C-DIS: forget an object after this x its heartbeat'); }
					else { p.hint('datasource.cdis.timeoutmultiplier', '2.4', 'C-DIS: forget an object after this x its heartbeat'); }
					if (tm && !(parseFloat(tm) > 0)) { issues.push({ level: 'err', text: label + ': the C-DIS timeout multiplier must be above 0.' }); }
				}
				if (portCheck(d.disPort)) { issues.push({ level: 'err', text: label + ': the UDP port is not a port number.' }); }
				if (!ca.isInt(d.disExid, 0, 255)) { issues.push({ level: 'err', text: label + ': the exercise must be 0 - 255.' }); }
				if (d.disIf === 'address' && !d.disAddr.trim()) { issues.push({ level: 'err', text: label + ': give the interface to receive on.' }); }
			} else {
				var foms = ca.splitList(d.foms);
				p.comment('FOM module files, relative to ' + (d.fomRoot ? d.fomRoot : './etc/fom') + '/' + ca.fomFolder(d.standard));
				p.kv('datasource.hla.foms', foms.join(';'));
				p.blank();
				p.comment(d.standard === 'ieee1516-2025' ? 'the RTI configuration name (empty: the RTI\'s default)' : 'local settings designator, passed to the RTI\'s connect()');
				p.kv('datasource.hla.lsd', lsdOf(d));
				if (d.federation.trim()) { p.kv('datasource.hla.federation', d.federation.trim(), 'the federation proposed when a datasource is created'); }
				else { p.hint('datasource.hla.federation', 'Blue', 'the federation proposed when a datasource is created'); }
				if (d.fedType.trim() && d.fedType.trim() !== 'SimwRift') { p.kv('datasource.hla.customfederatetype', d.fedType.trim()); }
				else { p.hint('datasource.hla.customfederatetype', 'SimwRift'); }
				p.kv('datasource.hla.reconnectondisconnect', d.reconnect ? 'true' : 'false');
				if (d.reconnect) { p.kv('datasource.hla.reconnectintervalseconds', String(d.reconnectSecs).trim() || '5'); }
				if (d.fomRoot.trim()) { p.kv('datasource.hla.customfomrootdir', d.fomRoot.trim(), 'FOM base folder; the standard\'s folder is added to it'); }
				if (!foms.length) { issues.push({ level: 'err', text: label + ': choose at least one FOM module.' }); }
				foms.filter(function (f) { return !/\.xml$/i.test(f) && !ca.hasVar(f); }).forEach(function (f) {
					issues.push({ level: 'err', text: label + ': `' + f + '` is not an .xml file. Rift hands each entry to the RTI as a FOM module, so list the files, not a folder.' });
				});
				if (d.standard === 'ieee1516-2025' && foms.some(function (f) { return /_1516-2010\.xml$/.test(f); })) {
					issues.push({ level: 'warn', text: label + ': `' + foms.filter(function (f) { return /_1516-2010\.xml$/.test(f); })[0] + '` is an HLA Evolved FOM; HLA 4 needs modules in the 1516-2025 format.' });
				}
				if (d.standard === 'ieee1516-2010' && foms.some(function (f) { return /^RPR-.*_v2\.0\.xml$/.test(f); })) {
					issues.push({ level: 'warn', text: label + ': the `RPR-*_v2.0.xml` modules delivered with Rift are in the HLA 4 format (etc/fom/ieee1516-2025).' });
				}
				if (!d.federation.trim()) { issues.push({ level: 'info', text: label + ': no federation set; it is typed or picked when the datasource is created.' }); }
				if (d.lsdMode === 'crc' && portCheck(d.crcPort)) { issues.push({ level: 'err', text: label + ': the CRC port is not a port number.' }); }
			}
			files.push({
				path: 'etc/datasources/' + file,
				kind: same ? 'replace' : 'new',
				about: same ? 'A file with this name is on the server already (**' + (same.name || file) + '**): this replaces it.'
					: 'Read again each time the configuration list is shown.',
				content: p.text(),
				role: 'ds', from: { text: 'the **Datasource configuration** "' + (d.name || file) + '" (tab ' + (di + 1) + ')', ref: { section: 'rift', ds: di, box: 'ds' } }
			});
		});

		var s = st.server;
		if (s.on) {
			var sp = new ca.Props();
			sp.comment('SimWeaver Rift server settings (read at startup: restart the server after a change)');
			sp.blank();
			sp.kv('webserver.port', String(s.port).trim() || '8843', 'HTTPS port of the Web UI and the REST API');
			if (s.root.trim() && s.root.trim() !== 'webroot') { sp.kv('webserver.root', s.root.trim(), 'web folder'); }
			sp.kv('webserver.keystore.path', s.keystore.trim() || 'keystore.jks', 'key store with the server certificate');
			sp.kv('webserver.keystore.pwd', s.pwd || 'password');
			sp.blank();
			sp.kv('logdir', s.logdir.trim() || 'logs', 'log folder');
			sp.blank();
			sp.kv('junction.enabled', s.jxEnabled ? 'true' : 'false', 'SimWeaver Junction gateways');
			if (s.jxEnabled) { sp.kv('junction.dir', s.jxDir.trim() || 'etc/junctions'); }
			if (s.jxEnabled && s.jxList === false) { sp.kv('junction.datasourceconfigs', 'false', 'gateway endpoints are not listed as datasource configurations'); }
			sp.blank();
			sp.kv('map.enabled', s.mapEnabled === false ? 'false' : 'true', 'the map view of the datasource page');
			if (s.mapEnabled !== false) {
				if (String(s.mapFile || '').trim()) { sp.kv('map.file', String(s.mapFile).trim().replace(/\\/g, '/'), 'the default background map'); }
				sp.kv('map.dir', String(s.mapDir || '').trim() || 'maps/raster', 'one sub folder per background map (a .tif in it)');
				sp.kv('map.cache.dir', String(s.mapCache || '').trim() || 'maps/cache', 'tiles made from the maps');
				if (String(s.mapMaxZoom || '').trim()) { sp.kv('map.maxzoom', String(s.mapMaxZoom).trim()); }
			}
			files.push({ path: 'etc/server.root.settings', kind: 'replace',
				about: 'Every installation has this file: replace it, or copy the lines you changed into it.', content: sp.text(),
				role: 'server', from: { text: 'the **Server settings** box', ref: { section: 'rift', box: 'server' } } });
			if (portCheck(s.port)) { issues.push({ level: 'err', text: 'Server settings: the HTTPS port is not a port number.' }); }
			if (!s.pwd) { issues.push({ level: 'warn', text: 'Server settings: the key store password is the built-in default "password".' }); }
		}

		if (st.lp.on) {
			var lp = new ca.Props();
			lp.comment('SimWeaver Rift: attributes the object list shows, per class (and its subclasses), in order.');
			lp.comment('Read at startup: restart the server after a change.');
			lp.blank();
			st.lp.rows.forEach(function (r) {
				var cls = String(r.cls || '').trim().replace(/^HLAobjectRoot\./, '');
				if (!cls) { return; }
				lp.kv('rift.listprofile.' + cls, String(r.attrs || '').split(',').map(function (a) { return a.trim(); }).filter(function (a) { return a; }).join(', '));
			});
			files.push({ path: 'etc/listprofiles.properties', kind: 'replace', about: 'Replaces the file if there is one.', content: lp.text(),
				role: 'lp', from: { text: 'the **Object list attributes** box', ref: { section: 'rift', box: 'lp' } } });
			if (!st.lp.rows.some(function (r) { return String(r.cls || '').trim(); })) { issues.push({ level: 'warn', text: 'Object list attributes: no class given.' }); }
		}

		steps.push('Put each file on the server at the path shown, relative to Rift\'s installation folder (where `run.sh` / `run.bat` are).');
		if (st.dsList.length) {
			steps.push('Open **Rift datasources** and press **Reload**: the new configuration appears as a card. Press **Create datasource** on it to join.');
		}
		if (st.dsList.some(function (d) { return d.standard === 'ieee1516-2010' || d.standard === 'ieee1516-2025'; })) {
			steps.push('HLA needs the RTI library on Rift\'s class path: the `RTI_JAR` line of the start script (several jars: `:` on Linux, `;` on Windows).');
		}
		if (s.on) {
			steps.push('If you want a new certificate, run in the installation folder: `keytool -genkeypair -keyalg RSA -keysize 2048 -alias ' + (s.alias || 'simweaver')
				+ ' -keystore ' + (s.keystore.trim() || 'keystore.jks') + ' -storepass ' + (s.pwd || 'password') + ' -validity ' + (String(s.validity).trim() || '730')
				+ ' -dname "CN=' + (s.cn.trim() || 'localhost') + '"`');
			steps.push('Restart the server, then browse to `https://' + (s.cn.trim() || '<server>') + ':' + (String(s.port).trim() || '8843') + '/`.');
		} else if (st.lp.on) {
			steps.push('Restart the server for the object list attributes to apply.');
		}
		return { issues: issues, files: files, steps: steps, legend: ['ds', 'server', 'lp'] };
	};
})(window);
