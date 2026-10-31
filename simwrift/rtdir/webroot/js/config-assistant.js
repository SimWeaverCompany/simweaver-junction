/* ============================================================
   SimWeaver Config Assistant - the page.
   Two sections (Rift, Junction). The forms build settings files in the
   browser; nothing is written to the server. What the server already has
   (datasource configurations, gateways, kits, groups) is read once, to
   offer it and to warn about files that would be replaced.
   The state is remembered in this browser (localStorage) when allowed.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var ca = Rift.ca;

	var VERSION = 1;
	var state = null;
	var server = { loaded: false, junction: false, dsConfigs: [], gateways: [], endpointKits: [], routerKits: [], groups: [], junctionDir: '' };
	var nodes = {};
	var outTimer = null;

	function fresh() {
		return { v: VERSION, section: 'rift', rift: ca.riftDefaults(), junction: ca.junctionDefaults() };
	}

	function restore() {
		var s = ca.load();
		// 0.9.0 saved the gateway section as "junktion"; carry it over
		if (s && s.junktion && !s.junction) { s.junction = s.junktion; }
		if (s) { delete s.junktion; if (s.section === 'junktion') { s.section = 'junction'; } }
		if (location.hash === '#junktion') { history.replaceState(null, '', '#junction'); }
		if (!s || s.v !== VERSION || !s.rift || !s.junction || !Array.isArray(s.rift.dsList) || !Array.isArray(s.junction.gws)) { s = fresh(); }
		if (location.hash === '#junction' || location.hash === '#rift') { s.section = location.hash.slice(1); }
		return s;
	}

	/* ---------- drawing ---------- */

	function drawSwitch() {
		[['rift', nodes.swRift], ['junction', nodes.swJx]].forEach(function (x) {
			x[1].setAttribute('aria-pressed', state.section === x[0] ? 'true' : 'false');
		});
	}

	function drawForm() {
		var y = window.scrollY;
		if (state.section === 'rift') { ca.renderRift(nodes.form, state.rift, server); }
		else { ca.renderJunction(nodes.form, state.junction, server); }
		window.scrollTo(0, y);
	}

	function drawOutput() {
		var r;
		try {
			r = state.section === 'rift' ? ca.riftOutput(state.rift, server) : ca.junctionOutput(state.junction, server);
		} catch (e) {
			r = { issues: [{ level: 'err', text: 'The assistant could not build the files: ' + (e && e.message ? e.message : e) }], files: [] };
			if (window.console) { console.error(e); }
		}
		ca.renderOutput(nodes.out, r);
		var n = (r.files || []).length;
		var errs = (r.issues || []).filter(function (i) { return i.level === 'err'; }).length;
		nodes.outMeta.textContent = n + ' file' + (n === 1 ? '' : 's') + (errs ? ' · ' + errs + ' to fix' : '');
		nodes.outMeta.className = errs ? 'ca-bad' : '';
	}

	function drawAll() {
		drawSwitch();
		drawForm();
		drawOutput();
	}

	ca.onChange = function (structural) {
		ca.save(state);
		if (structural) {
			drawForm();
			drawOutput();
			return;
		}
		clearTimeout(outTimer);
		outTimer = setTimeout(drawOutput, 120);
	};

	/** "Show in the form": go to the box a file comes from. */
	ca.reveal = function (ref) {
		if (!ref) { return; }
		state.section = ref.section || state.section;
		if (ref.section === 'junction' && typeof ref.gw === 'number') { state.junction.sel = ref.gw; }
		if (ref.section === 'rift' && typeof ref.ds === 'number') { state.rift.dsSel = ref.ds; }
		ca.save(state);
		drawAll();
		var box = document.getElementById('ca-box-' + ref.box);
		if (!box) { return; }
		box.scrollIntoView({ behavior: 'smooth', block: 'start' });
		box.classList.remove('ca-flash');
		void box.offsetWidth;
		box.classList.add('ca-flash');
	};

	/* ---------- what the server has ---------- */

	function loadServer() {
		var api = Rift.api;
		var p1 = api.fetchDatasourceConfigs().then(function (list) {
			server.dsConfigs = list.map(function (c) { return { name: c.name, file: String(c.file || '').split(/[\\\/]/).pop(), origin: c.origin }; })
				.filter(function (c) { return !c.origin; });
			return true;
		}, function () { return false; });
		var p2 = Rift.junction ? Rift.junction.fetchConfigs().then(function (d) {
			server.junction = true;
			server.gateways = (d && d.gateways) || [];
			server.endpointKits = (d && d.endpointkits) || [];
			server.routerKits = (d && d.routerkits) || [];
			server.groups = (d && d.groups) || [];
			server.junctionDir = (d && d.directory) || '';
			return true;
		}, function () { return false; }) : Promise.resolve(false);
		Promise.all([p1, p2]).then(function (ok) {
			server.loaded = ok[0] || ok[1];
			var s = nodes.server;
			ui.clear(s);
			if (!server.loaded) {
				s.className = 'ca-server off';
				s.appendChild(document.createTextNode('The server did not answer: kits and names are those delivered with Rift and Junction, and existing files are not checked.'));
			} else {
				s.className = 'ca-server ok';
				ui.append(s, ['On the server: ', el('b', { text: String(server.dsConfigs.length) }), ' datasource configurations',
					server.junction ? ', ' : '', server.junction ? el('b', { text: String(server.gateways.length) }) : null, server.junction ? ' gateways, ' : '',
					server.junction ? el('b', { text: String(server.endpointKits.length + server.routerKits.length) }) : null, server.junction ? ' kits, ' : '',
					server.junction ? el('b', { text: String(server.groups.length) }) : null, server.junction ? (server.groups.length === 1 ? ' group' : ' groups') : '',
					server.junction ? '' : ' (Junction is off)',
					'. Files that would replace them are marked.']);
			}
			drawAll();
		});
	}

	/* ---------- start-up ---------- */

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		nodes.form = document.getElementById('ca-form');
		nodes.out = document.getElementById('ca-files');
		nodes.outMeta = document.getElementById('ca-files-meta');
		nodes.server = document.getElementById('ca-server');
		nodes.swRift = document.getElementById('ca-sw-rift');
		nodes.swJx = document.getElementById('ca-sw-junction');

		state = restore();
		ca.state = state;

		nodes.swRift.addEventListener('click', function () { state.section = 'rift'; ca.save(state); history.replaceState(null, '', '#rift'); drawAll(); });
		nodes.swJx.addEventListener('click', function () { state.section = 'junction'; ca.save(state); history.replaceState(null, '', '#junction'); drawAll(); });
		document.getElementById('ca-reset').addEventListener('click', function () {
			var which = state.section === 'rift' ? 'Rift' : 'Junction';
			Rift.confirmDialog.open({
				title: 'Start over', tag: 'NEW', subject: which + ' section',
				message: 'Everything you filled in for the ' + which + ' section goes back to the starting values. The other section is kept.',
				confirmLabel: '↺ Start over', busyLabel: 'Clearing…', failTitle: 'Not cleared',
				action: function () { return Promise.resolve(true); },
				onDone: function () {
					if (state.section === 'rift') { state.rift = ca.riftDefaults(); } else { state.junction = ca.junctionDefaults(); }
					ca.save(state);
					drawAll();
				}
			});
		});
		document.getElementById('ca-download-all').addEventListener('click', function () {
			var r = state.section === 'rift' ? ca.riftOutput(state.rift, server) : ca.junctionOutput(state.junction, server);
			(r.files || []).forEach(function (f, i) {
				setTimeout(function () { ca.download(f.path.split('/').pop(), f.content); }, i * 250);
			});
		});

		drawAll();
		loadServer();
	});
})(window, document);
