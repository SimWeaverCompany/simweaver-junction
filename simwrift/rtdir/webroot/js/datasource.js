/* ============================================================
   SimWeaver Rift - datasource page (datasource.html?id=...).

   Header (name, state, federation, collapsible config snapshot) and three
   cross-linked panels: class tree | object instances | interaction log,
   kept live by one SSE stream (contract: docs/datasource-page-contract.md).

   Start-up: header first; then the stream. Every (re)opened connection after a
   loss reloads all snapshots (the hub has no replay). If the stream does not
   answer within a few seconds the snapshots are loaded anyway, without live updates.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var dsid = new URLSearchParams(window.location.search).get('id');
	var dsName = null;
	var stream, tree, list, log;
	var mapView = null;                       // NEW: the map of panel 02 (made when first shown)
	var loadedOnce = false;
	var fallbackTimer = null;

	function $(id) { return document.getElementById(id); }

	/* ---------- header ---------- */

	function setName(name) {
		$('crumb-name').textContent = name;
		$('ds-title').textContent = name;
		document.title = name + ' · SimWeaver Rift';
	}

	var STATE_CLASS = { JOINED: 'ok', CONNECTING: 'warn', RECONNECTING: 'warn', DISCONNECTED: 'err', ERROR: 'err' };

	function setState(state, message) {
		var node = $('ds-state');
		node.textContent = state || '—';
		node.className = 'ds-state ' + (STATE_CLASS[state] || '');
		node.title = message || '';
		var msg = ui.clear($('ds-msg'));
		if (message && state !== 'JOINED') {
			msg.appendChild(el('div.ds-statemsg.' + (STATE_CLASS[state] || 'warn'), null, [
				el('span.err-tag', { text: state || 'STATE' }), el('span', { text: message })
			]));
		}
	}

	function renderHeader(ds) {
		dsName = ds.name ? String(ds.name) : null;
		setName(dsName || 'Datasource ' + dsid);
		setState(ds.state, ds.statemessage);

		var cfg = ds.config || null;
		var facts = ui.clear($('ds-facts'));
		var std = cfg ? (cfg.standardlabel || cfg.standard) : ds.standard;
		var dis = cfg ? Rift.configCard.isDis(cfg) : Rift.configCard.familyOf(ds.standard) === 'DIS';
		var exercise = ds.federation || (cfg && cfg.disexid !== undefined && cfg.disexid !== null ? String(cfg.disexid) : '');
		(dis ? [
			std ? ['STD', std] : null,
			exercise !== '' ? ['EX', exercise === '0' ? 'all' : exercise] : null,
			(cfg && cfg.disport) ? ['UDP', String(cfg.disport)] : null,
			ds.created ? ['SINCE', ui.zulu(ds.created, false)] : null
		] : [
			std ? ['STD', std] : null,
			(ds.federation || (cfg && cfg.hlafederation)) ? ['FED', ds.federation || cfg.hlafederation] : null,
			(ds.hlalsd !== undefined ? ds.hlalsd : (cfg && cfg.hlalsd)) ? ['LSD', ds.hlalsd !== undefined ? ds.hlalsd : cfg.hlalsd] : null,
			ds.created ? ['SINCE', ui.zulu(ds.created, false)] : null
		]).forEach(function (f) {
			if (f) { facts.appendChild(el('span.fact', null, [el('span.fact-k', { text: f[0] }), el('span.fact-v', { text: f[1] })])); }
		});

		var det = $('ds-config');
		if (cfg) {
			det.hidden = false;
			ui.clear($('ds-config-summary')).appendChild(el('span', null, [
				el('b', { text: cfg.name || ('#' + (ds.configid || cfg.id)) }),
				cfg.file ? el('span.dim', { text: ' · ' + cfg.file }) : null,
				Array.isArray(cfg.hlafoms) ? el('span.dim', { text: ' · ' + cfg.hlafoms.length + ' FOM module' + (cfg.hlafoms.length === 1 ? '' : 's') }) : null
			]));
			ui.clear($('ds-config-body')).appendChild(Rift.configCard.render(cfg, { federation: ds.federation }));
		} else if (ds.configid !== undefined) {
			det.hidden = false;
			ui.clear($('ds-config-summary')).appendChild(el('span.dim', { text: '#' + ds.configid + ' (no snapshot sent by the server)' }));
			ui.clear($('ds-config-body'));
		} else {
			det.hidden = true;
		}
	}

	/** Header from GET /api/datasources/{id}; falls back to the list for the name. */
	function loadHeader() {
		return Rift.api.fetchDatasource(dsid).then(function (ds) {
			renderHeader(ds || {});
			return true;
		}, function (err) {
			if (err.status === 404) {
				return Rift.api.fetchDatasources().then(function (all) {
					var hit = all.filter(function (d) { return String(d.id) === String(dsid); })[0];
					if (hit) { renderHeader({ name: hit.name }); setState('', ''); return true; }
					notFound();
					return false;
				}, function () { notFound(); return false; });
			}
			setName(dsName || '#' + dsid);
			ui.clear($('ds-msg')).appendChild(ui.errorRow('Could not load the datasource header', err, loadHeader));
			return true;
		});
	}

	function notFound() {
		setName('#' + dsid);
		setState('', '');
		ui.clear($('ds-msg')).appendChild(ui.emptyBlock('Datasource not found',
			'It may have been removed. Go back to the overview to see the running datasources.'));
		document.querySelector('.ds-grid').hidden = true;
	}

	/* ---------- stream + panels ---------- */

	/* XrSseHub topics (exact match, fixed per connection):
	     ds:<id>               datasource.state, datasource.removed, interaction.stats  (publish)
	     ds:<id>:objects       { "<iid>": listView | null }   discovered / list view changed / removed (patch)
	     ds:<id>:classes       { "<classnameq>": count }       class counts (patch)
	     ds:<id>:interactions  interaction                     every interaction, capped by the backend (publish)
	     obj:<id>:<iid>        { "<attribute>": attr, "@rev", "@lastupdated", ... }  (patch)
	                           for visible rows, expanded and pinned objects             */
	function topics() {
		var base = 'ds:' + dsid;
		return [base, base + ':objects', base + ':classes', base + ':interactions']
			.concat(list.liveIds().map(function (iid) { return 'obj:' + dsid + ':' + iid; }));
	}

	function onPatch(topic, patch) {
		var base = 'ds:' + dsid;
		if (topic === base + ':objects') { list.onObjectsPatch(patch); return; }
		if (topic === base + ':classes') { tree.applyCounts(patch); updateTreeMeta(); return; }
		var objPrefix = 'obj:' + dsid + ':';
		if (topic.indexOf(objPrefix) === 0) { list.onObjectPatch(topic.substring(objPrefix.length), patch); }
	}

	function onTopicsChanged(added) {
		var objPrefix = 'obj:' + dsid + ':';
		var ids = added.filter(function (t) { return t.indexOf(objPrefix) === 0; })
			.map(function (t) { return t.substring(objPrefix.length); });
		list.refreshIds(ids);
	}

	function setStreamStatus(status) {
		var box = $('ds-stream');
		var lamp = box.querySelector('.lamp');
		var text = box.querySelector('.ds-stream-text');
		lamp.className = 'lamp' + (status === 'live' ? '' : status === 'lost' ? ' err' : ' off');
		text.textContent = status === 'live' ? 'LIVE' : status === 'lost' ? 'STREAM LOST · RETRYING' : 'CONNECTING';
		box.className = 'ds-stream ' + status;
	}

	function loadTree() {
		return Rift.api.fetchClasses(dsid).then(function (reply) {
			treeReply(reply);
		}, function (err) {
			ui.clear($('tree-box')).appendChild(ui.errorRow('Could not load classes', err, loadTree));
		});
	}

	function treeReply(reply) {
		tree.setClasses(reply);
		updateTreeMeta();
	}

	function updateTreeMeta() {
		ui.clear($('tree-meta')).appendChild(el('span', { text: (tree.total || 0).toLocaleString('sv-SE') + ' OBJ' }));
	}

	/** Snapshot reload of all three panels (first load, new stream, or resync). */
	function resyncAll() {
		loadedOnce = true;
		clearTimeout(fallbackTimer);
		loadTree();
		list.resync();
		log.resync();
	}

	function onEvent(name, data) {
		if (name.indexOf('interaction') === 0) { log.onEvent(name, data); return; }
		if (name === 'datasource.state') { setState(data.state, data.statemessage); return; }
		if (name === 'datasource.removed') {
			ui.flash('Datasource ' + (dsName || dsid) + ' was removed.', 'info');
			window.location.href = 'index.html';
		}
	}

	function startPanels() {
		stream = new Rift.LiveStream({
			topics: topics,
			onReady: resyncAll,
			onTopicsChanged: onTopicsChanged,
			onPatch: onPatch,
			onEvent: onEvent,
			onStatus: setStreamStatus
		});

		tree = new Rift.ClassTree($('tree-box'), $('tree-filter'), {
			onChange: function (sel) {
				$('tree-clear').hidden = sel.length === 0;
				list.setClasses(sel);
			}
		});
		$('tree-clear').addEventListener('click', function () { tree.clearSelection(); });

		list = new Rift.ObjectList({
			dsid: dsid, stream: stream,
			els: { box: $('obj-box'), scroller: $('obj-scroller'), search: $('obj-search'), sort: $('obj-sort'),
				related: $('obj-related'), banner: $('obj-banner'), meta: $('obj-meta'), pinned: $('obj-pinned') },
			onClassClick: function (q) { tree.selectOnly(q); },
			onFocusLog: function (target) { log.setFocus(target); },
			onShowOnMap: function (target) { showMap(true, target.instanceid); }
		});
		setupMap();
		// NEW: datasource.html?id=..&focus=<object id> (e.g. "Details" on the map page) opens that object
		var focusId = new URLSearchParams(window.location.search).get('focus');
		if (focusId) { setTimeout(function () { list.focus({ instanceid: focusId }); }, 1200); }

		log = new Rift.InteractionLog({
			dsid: dsid, stream: stream,
			els: { ticker: $('ix-ticker'), scroller: $('ix-scroller'), tape: $('ix-tape'), banner: $('ix-banner'),
				pause: $('ix-pause'), focus: $('ix-focus'), meta: $('ix-meta'), clear: $('ix-clear') },
			onSelectObject: function (t) { list.focus(t); }
		});

		// Object chips anywhere on the page select the object in the middle panel.
		Rift.valueView.onSelect = function (target) { list.focus(target); };

		stream.start();
		// No open stream within 3 s (e.g. no SSE on the server yet): load without live updates.
		fallbackTimer = setTimeout(function () { if (!loadedOnce) { resyncAll(); } }, 3000);
	}

	/* ---------- NEW: map view of panel 02 ---------- */

	var VIEW_KEY = 'rift-ds-view';

	/** The filter the list shows now, for the map (class tree, search, + related). */
	function currentFilter() {
		var f = list ? list.filter : {};
		return { 'class': (f['class'] || []).slice(), subclasses: f.subclasses !== false, q: f.q || '', related: !!f.related };
	}

	function mapTabHref() {
		var f = currentFilter();
		var p = new URLSearchParams();
		p.set('id', dsid);
		if (f['class'].length) { p.set('class', f['class'].join(',')); }
		if (!f.subclasses) { p.set('subclasses', 'false'); }
		if (f.q) { p.set('q', f.q); }
		if (f.related) { p.set('related', 'true'); }
		if (mapView && !mapView.isDefaultMap()) { p.set('map', mapView.currentMap()); }
		return 'map.html?' + p.toString();
	}

	function setupMap() {
		Rift.MapView.available().then(function (on) {
			if (!on) { return; }                      // map.enabled=false: no map buttons at all
			$('obj-view').hidden = false;
			$('obj-map-tab').hidden = false;
			var tab = $('obj-map-tab');
			var refresh = function () { tab.href = mapTabHref(); };
			refresh();
			tab.addEventListener('mouseenter', refresh);
			tab.addEventListener('focus', refresh);
			tab.addEventListener('click', refresh);
			$('obj-view-list').addEventListener('click', function () { showMap(false); });
			$('obj-view-map').addEventListener('click', function () { showMap(true); });
			var saved = null;
			try { saved = window.localStorage.getItem(VIEW_KEY); } catch (e) { /* storage blocked */ }
			if (saved === 'map') { showMap(true); }
		});
	}

	/** Shows the map (or the list) in panel 02; with an id, goes to that object. */
	function showMap(on, goToId) {
		$('obj-view-list').setAttribute('aria-pressed', on ? 'false' : 'true');
		$('obj-view-map').setAttribute('aria-pressed', on ? 'true' : 'false');
		$('obj-scroller').hidden = on;
		$('obj-banner').hidden = on || $('obj-banner').hidden;
		$('obj-map').hidden = !on;
		try { window.localStorage.setItem(VIEW_KEY, on ? 'map' : 'list'); } catch (e) { /* storage blocked */ }
		if (!on) {
			if (mapView) { mapView.stop(); }
			return;
		}
		if (!mapView) {
			mapView = new Rift.MapView($('obj-map'), {
				dsid: dsid,
				getFilter: currentFilter,
				storageKey: 'rift-map:' + dsid,
				onOpenObject: function (t) { showMap(false); list.focus(t); }
			});
		}
		mapView.start();
		mapView.resize();
		if (goToId) {
			mapView.goTo(goToId);
		} else {
			mapView.poll(true);
		}
	}

	/* ---------- remove ---------- */

	function remove() {
		Rift.removeDatasource.confirm({ id: dsid, name: dsName }, function (outcome) {
			if (stream) { stream.stop(); }
			ui.flash(outcome.alreadyGone
				? 'Datasource ' + outcome.name + ' was already gone.'
				: 'Datasource ' + outcome.name + ' disconnected and removed.', outcome.alreadyGone ? 'info' : 'ok');
			window.location.href = 'index.html';
		}, { toast: false });
	}

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();

		if (!dsid) {
			setName('unknown');
			ui.clear($('ds-msg')).appendChild(ui.errorRow('No datasource selected', new Error('The page was opened without ?id=')));
			document.querySelector('.ds-grid').hidden = true;
			return;
		}

		var removeBtn = $('btn-remove-ds');
		removeBtn.hidden = false;
		removeBtn.addEventListener('click', remove);
		$('btn-reload-ds').addEventListener('click', function () {
			ui.busy($('btn-reload-ds'), loadHeader().then(function (ok) { if (ok && stream) { resyncAll(); } }));
		});

		loadHeader().then(function (ok) { if (ok) { startPanels(); } });
	});
})(window, document);
